// Package fakedns implements "fake-IP" DNS for the tunnel: A queries are
// answered locally with an address from a reserved pool, and the engine maps
// the address back to the hostname when the app connects, so the hostname —
// not a locally resolved IP — is sent to the proxy. No DNS query ever leaves
// the device directly (ADR-0003).
package fakedns

import (
	"container/list"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"math/big"
	"net/netip"
	"sync"
	"time"
)

// DefaultReuseAfter is how long a mapping must go unused before its address
// may be given to another name. Answers carry a 5 s TTL, but apps and
// libraries keep addresses far longer than that.
const DefaultReuseAfter = 10 * time.Minute

// Pool maps hostnames to IPv4 addresses in a reserved prefix.
//
// An address goes to another name only after its mapping was unused for
// ReuseAfter: no DNS answer for the name, no new connection to the address,
// and no connection to it still open (Acquire pins a mapping until the
// connection ends). Freed addresses then queue behind every address never
// handed out and every address freed before them, so a freed address returns
// only after a full cycle of the pool. When every mapping is in use, new
// names are refused (Lookup reports false) until one ages out. Allocation
// starts at a random address, so an address an app cached before the process
// restarted rarely maps to any name.
type Pool struct {
	mu         sync.Mutex
	prefix     netip.Prefix
	first      uint32 // first allocatable address
	last       uint32 // last allocatable address
	next       uint32 // next address never handed out
	virgin     int    // how many addresses were never handed out
	free       ring   // freed addresses, oldest first
	max        int
	reuseAfter time.Duration
	now        func() time.Time
	byIP       map[uint32]*entry
	byName     map[string]*entry
	idle       *list.List // mappings without open connections; front = most recently used
}

type entry struct {
	ip       uint32
	name     string
	lastUsed time.Time
	refs     int           // open connections (Acquire without Release)
	el       *list.Element // in idle while refs == 0
}

// PoolOption adjusts a Pool.
type PoolOption func(*Pool)

// WithReuseAfter sets how long a mapping must go unused before its address
// can be reused (default DefaultReuseAfter; 0 recycles the least recently
// used mapping at once, for tests).
func WithReuseAfter(d time.Duration) PoolOption { return func(p *Pool) { p.reuseAfter = d } }

// withClock replaces time.Now (tests).
func withClock(now func() time.Time) PoolOption { return func(p *Pool) { p.now = now } }

// withStart fixes the first address handed out (tests).
func withStart(a netip.Addr) PoolOption { return func(p *Pool) { p.next = u32(a) } }

// NewPool creates a pool over prefix (IPv4, /8 or longer), skipping the first
// `reserved` addresses (used for the TUN and DNS server addresses).
// maxEntries bounds the number of mappings (and so the memory, a few hundred
// bytes each); <= 0 means "the whole prefix". Keep it well below the prefix
// size, so that freed addresses wait for a long cycle.
func NewPool(prefix netip.Prefix, reserved int, maxEntries int, opts ...PoolOption) (*Pool, error) {
	if !prefix.Addr().Is4() {
		return nil, errors.New("fake-IP pool must be IPv4")
	}
	if prefix.Bits() < 8 {
		return nil, errors.New("fake-IP pool too large")
	}
	prefix = prefix.Masked()
	base := u32(prefix.Addr())
	size := uint64(1) << (32 - prefix.Bits())
	if reserved < 0 || size <= uint64(reserved)+2 {
		return nil, errors.New("fake-IP pool too small")
	}
	first := base + uint32(reserved)
	last := base + uint32(size) - 2 // skip broadcast-looking last address
	capacity := int(last - first + 1)
	if maxEntries <= 0 || maxEntries > capacity {
		maxEntries = capacity
	}
	start, err := rand.Int(rand.Reader, big.NewInt(int64(capacity)))
	if err != nil {
		return nil, err
	}
	p := &Pool{
		prefix: prefix, first: first, last: last, next: first + uint32(start.Int64()), virgin: capacity,
		max: maxEntries, reuseAfter: DefaultReuseAfter, now: time.Now,
		byIP: map[uint32]*entry{}, byName: map[string]*entry{}, idle: list.New(),
		// Every address is never used, mapped or freed. Mappings are dropped
		// only to make room for a new one when there are max of them, so at
		// most capacity-max addresses wait, plus the one freed for the
		// allocation under way.
		free: ring{size: capacity - maxEntries + 1},
	}
	for _, o := range opts {
		o(p)
	}
	if p.next < first || p.next > last {
		return nil, errors.New("fake-IP pool start outside the pool")
	}
	return p, nil
}

// Contains reports whether ip lies in the pool prefix.
func (p *Pool) Contains(ip netip.Addr) bool { return p.prefix.Contains(ip.Unmap()) }

// Reserved reports whether ip lies in the pool prefix but is never handed out:
// the reserved addresses at its start (the TUN's and the DNS server's own) and
// the last address. No name maps to them, ever.
func (p *Pool) Reserved(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.Is4() || !p.prefix.Contains(ip) {
		return false
	}
	v := u32(ip)
	return v < p.first || v > p.last
}

// Lookup returns the address for name, allocating one if needed. It reports
// false when every mapping was used within ReuseAfter or has a connection
// open. name must already be validated and normalised by the caller.
func (p *Pool) Lookup(name string) (netip.Addr, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	if e, ok := p.byName[name]; ok {
		p.touch(e, now)
		return addr(e.ip), true
	}
	if len(p.byName) >= p.max && !p.evict(now) {
		return netip.Addr{}, false
	}
	ip, ok := p.allocate()
	if !ok {
		return netip.Addr{}, false // cannot happen: an eviction frees an address
	}
	e := &entry{ip: ip, name: name, lastUsed: now}
	e.el = p.idle.PushFront(e)
	p.byIP[ip] = e
	p.byName[name] = e
	return addr(ip), true
}

// Reverse returns the hostname mapped to ip, counting as use of the mapping.
func (p *Pool) Reverse(ip netip.Addr) (string, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	e := p.find(ip)
	if e == nil {
		return "", false
	}
	p.touch(e, p.now())
	return e.name, true
}

// Acquire returns the hostname mapped to ip and pins the mapping until
// release is called, so the address cannot go to another name while a
// connection to it is open; the reuse delay starts when the last one closes.
// release may be called more than once.
func (p *Pool) Acquire(ip netip.Addr) (name string, release func(), ok bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	e := p.find(ip)
	if e == nil {
		return "", nil, false
	}
	if e.refs == 0 {
		p.idle.Remove(e.el)
		e.el = nil
	}
	e.refs++
	e.lastUsed = p.now()
	var once sync.Once
	return e.name, func() { once.Do(func() { p.release(e) }) }, true
}

func (p *Pool) release(e *entry) {
	p.mu.Lock()
	defer p.mu.Unlock()
	e.refs--
	if e.refs == 0 {
		e.lastUsed = p.now()
		e.el = p.idle.PushFront(e)
	}
}

// Len is the number of live mappings.
func (p *Pool) Len() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.byName)
}

func (p *Pool) find(ip netip.Addr) *entry {
	ip = ip.Unmap()
	if !ip.Is4() || !p.prefix.Contains(ip) {
		return nil
	}
	return p.byIP[u32(ip)]
}

func (p *Pool) touch(e *entry, now time.Time) {
	e.lastUsed = now
	if e.el != nil {
		p.idle.MoveToFront(e.el)
	}
}

// evict drops the least recently used mapping without open connections if it
// has been unused for ReuseAfter, and queues its address behind the others.
func (p *Pool) evict(now time.Time) bool {
	el := p.idle.Back()
	if el == nil {
		return false // every mapping has a connection open
	}
	e := el.Value.(*entry)
	if now.Sub(e.lastUsed) < p.reuseAfter {
		return false
	}
	p.idle.Remove(el)
	delete(p.byName, e.name)
	delete(p.byIP, e.ip)
	p.free.push(e.ip)
	return true
}

// allocate hands out an address never handed out before, else the address
// freed longest ago.
func (p *Pool) allocate() (uint32, bool) {
	if p.virgin > 0 {
		ip := p.next
		if p.next == p.last {
			p.next = p.first
		} else {
			p.next++
		}
		p.virgin--
		return ip, true
	}
	return p.free.pop()
}

// ring is a FIFO queue of addresses with a fixed number of slots, allocated
// when the first address is queued (4 bytes per slot).
type ring struct {
	buf        []uint32
	head, n    int
	size       int
	overflowed bool // a push found no slot (cannot happen; see NewPool)
}

func (r *ring) push(v uint32) {
	if r.buf == nil {
		r.buf = make([]uint32, r.size)
	}
	if r.n == len(r.buf) {
		r.overflowed = true // keep the older addresses; this one is lost to the pool
		return
	}
	r.buf[(r.head+r.n)%len(r.buf)] = v
	r.n++
}

func (r *ring) pop() (uint32, bool) {
	if r.n == 0 {
		return 0, false
	}
	v := r.buf[r.head]
	r.head = (r.head + 1) % len(r.buf)
	r.n--
	return v, true
}

func u32(a netip.Addr) uint32 { b := a.As4(); return binary.BigEndian.Uint32(b[:]) }
func addr(v uint32) netip.Addr {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	return netip.AddrFrom4(b)
}
