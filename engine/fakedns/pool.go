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
// An address is never handed to another name while an app may still hold it
// from an earlier answer: a mapping is dropped only after ReuseAfter without
// use (DNS answers and connections both count as use), and a freed address
// goes to the back of the allocation cycle instead of straight to the next
// name. When every mapping is in use, new names are refused until one ages
// out (Lookup reports false). Allocation starts at a random address, so an
// address an app cached before the process restarted rarely maps to any name.
type Pool struct {
	mu         sync.Mutex
	prefix     netip.Prefix
	first      uint32 // first allocatable address
	last       uint32 // last allocatable address
	next       uint32 // allocation cursor
	max        int
	reuseAfter time.Duration
	now        func() time.Time
	byIP       map[uint32]*list.Element
	byName     map[string]*list.Element
	lru        *list.List // front = most recently used
}

type entry struct {
	ip       uint32
	name     string
	lastUsed time.Time
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

// NewPool creates a pool over prefix (IPv4 only), skipping the first
// `reserved` addresses (used for the TUN and DNS server addresses).
// maxEntries bounds the number of mappings (and so the memory, a few hundred
// bytes each); <= 0 means "the whole prefix". Keep it well below the prefix
// size so that a freed address waits for a full allocation cycle.
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
		prefix: prefix, first: first, last: last, next: first + uint32(start.Int64()), max: maxEntries,
		reuseAfter: DefaultReuseAfter, now: time.Now,
		byIP: map[uint32]*list.Element{}, byName: map[string]*list.Element{}, lru: list.New(),
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

// Lookup returns the address for name, allocating one if needed. It reports
// false when the pool is full of mappings used within ReuseAfter. name must
// already be validated and normalised by the caller.
func (p *Pool) Lookup(name string) (netip.Addr, bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	now := p.now()
	if el, ok := p.byName[name]; ok {
		e := el.Value.(*entry)
		e.lastUsed = now
		p.lru.MoveToFront(el)
		return addr(e.ip), true
	}
	if p.lru.Len() >= p.max {
		el := p.lru.Back()
		old := el.Value.(*entry)
		if now.Sub(old.lastUsed) < p.reuseAfter {
			return netip.Addr{}, false
		}
		delete(p.byName, old.name)
		delete(p.byIP, old.ip)
		p.lru.Remove(el)
	}
	// The cursor walks the whole prefix, so a freed address comes back only
	// after every other free address was handed out once.
	var ip uint32
	for {
		ip = p.next
		if p.next == p.last {
			p.next = p.first
		} else {
			p.next++
		}
		if _, used := p.byIP[ip]; !used {
			break
		}
	}
	el := p.lru.PushFront(&entry{ip: ip, name: name, lastUsed: now})
	p.byIP[ip] = el
	p.byName[name] = el
	return addr(ip), true
}

// Reverse returns the hostname mapped to ip; a connection counts as use.
func (p *Pool) Reverse(ip netip.Addr) (string, bool) {
	ip = ip.Unmap()
	if !ip.Is4() || !p.prefix.Contains(ip) {
		return "", false
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	el, ok := p.byIP[u32(ip)]
	if !ok {
		return "", false
	}
	e := el.Value.(*entry)
	e.lastUsed = p.now()
	p.lru.MoveToFront(el)
	return e.name, true
}

// Len is the number of live mappings.
func (p *Pool) Len() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.lru.Len()
}

func u32(a netip.Addr) uint32 { b := a.As4(); return binary.BigEndian.Uint32(b[:]) }
func addr(v uint32) netip.Addr {
	var b [4]byte
	binary.BigEndian.PutUint32(b[:], v)
	return netip.AddrFrom4(b)
}
