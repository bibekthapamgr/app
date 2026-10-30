// Package fakedns implements "fake-IP" DNS for the tunnel: A queries are
// answered locally with an address from a reserved pool, and the engine maps
// the address back to the hostname when the app connects, so the hostname —
// not a locally resolved IP — is sent to the proxy. No DNS query ever leaves
// the device directly (ADR-0003).
//
// SPIKE CODE (Milestone 1).
package fakedns

import (
	"container/list"
	"encoding/binary"
	"errors"
	"net/netip"
	"sync"
)

// Pool maps hostnames to IPv4 addresses in a reserved prefix, recycling the
// least-recently-used entry when full.
type Pool struct {
	mu     sync.Mutex
	prefix netip.Prefix
	first  uint32 // first allocatable address
	last   uint32 // last allocatable address
	next   uint32
	max    int
	byIP   map[uint32]*list.Element
	byName map[string]*list.Element
	lru    *list.List // front = most recently used
}

type entry struct {
	ip   uint32
	name string
}

// NewPool creates a pool over prefix (IPv4 only), skipping the first `reserved`
// addresses (used for the TUN and DNS server addresses). maxEntries <= 0 means
// "the whole prefix".
func NewPool(prefix netip.Prefix, reserved int, maxEntries int) (*Pool, error) {
	if !prefix.Addr().Is4() {
		return nil, errors.New("fake-IP pool must be IPv4")
	}
	prefix = prefix.Masked()
	base := u32(prefix.Addr())
	size := uint64(1) << (32 - prefix.Bits())
	if size <= uint64(reserved)+2 {
		return nil, errors.New("fake-IP pool too small")
	}
	first := base + uint32(reserved)
	last := base + uint32(size) - 2 // skip broadcast-looking last address
	capacity := int(last - first + 1)
	if maxEntries <= 0 || maxEntries > capacity {
		maxEntries = capacity
	}
	return &Pool{
		prefix: prefix, first: first, last: last, next: first, max: maxEntries,
		byIP: map[uint32]*list.Element{}, byName: map[string]*list.Element{}, lru: list.New(),
	}, nil
}

// Contains reports whether ip lies in the pool prefix.
func (p *Pool) Contains(ip netip.Addr) bool { return p.prefix.Contains(ip.Unmap()) }

// Lookup returns the address for name, allocating one if needed. name must
// already be validated and normalised by the caller.
func (p *Pool) Lookup(name string) netip.Addr {
	p.mu.Lock()
	defer p.mu.Unlock()
	if el, ok := p.byName[name]; ok {
		p.lru.MoveToFront(el)
		return addr(el.Value.(*entry).ip)
	}
	var ip uint32
	if p.lru.Len() >= p.max {
		// Recycle the least recently used mapping.
		el := p.lru.Back()
		old := el.Value.(*entry)
		delete(p.byName, old.name)
		delete(p.byIP, old.ip)
		p.lru.Remove(el)
		ip = old.ip
	} else {
		for {
			ip = p.next
			p.next++
			if p.next > p.last {
				p.next = p.first
			}
			if _, used := p.byIP[ip]; !used {
				break
			}
		}
	}
	el := p.lru.PushFront(&entry{ip: ip, name: name})
	p.byIP[ip] = el
	p.byName[name] = el
	return addr(ip)
}

// Reverse returns the hostname mapped to ip.
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
	p.lru.MoveToFront(el)
	return el.Value.(*entry).name, true
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
