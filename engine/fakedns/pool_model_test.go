package fakedns

import (
	"fmt"
	"math/rand"
	"net/netip"
	"testing"
	"time"
)

// A model test written during the M4 review, kept as a regression test.

// checkInv verifies the pool's internal invariants (caller must not hold p.mu).
func checkInv(t *testing.T, p *Pool, capacity int) {
	t.Helper()
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.byName) != len(p.byIP) {
		t.Fatalf("byName %d != byIP %d", len(p.byName), len(p.byIP))
	}
	if len(p.byName) > p.max {
		t.Fatalf("more mappings than max: %d > %d", len(p.byName), p.max)
	}
	idleCount := 0
	for name, e := range p.byName {
		if e.name != name {
			t.Fatalf("name mismatch")
		}
		if p.byIP[e.ip] != e {
			t.Fatalf("byIP[%v] is not the entry of %s", addr(e.ip), name)
		}
		if e.ip < p.first || e.ip > p.last {
			t.Fatalf("ip outside range")
		}
		if e.refs < 0 {
			t.Fatalf("negative refs")
		}
		if (e.refs == 0) != (e.el != nil) {
			t.Fatalf("refs=%d el=%v", e.refs, e.el != nil)
		}
		if e.refs == 0 {
			idleCount++
		}
	}
	if p.idle.Len() != idleCount {
		t.Fatalf("idle list %d, idle entries %d", p.idle.Len(), idleCount)
	}
	var prev time.Time
	first := true
	for el := p.idle.Front(); el != nil; el = el.Next() {
		e := el.Value.(*entry)
		if e.el != el {
			t.Fatalf("el back-pointer wrong")
		}
		if p.byName[e.name] != e {
			t.Fatalf("idle entry not mapped")
		}
		if !first && e.lastUsed.After(prev) {
			t.Fatalf("idle list not sorted")
		}
		prev, first = e.lastUsed, false
	}
	if p.free.overflowed {
		t.Fatalf("the freed-address ring overflowed")
	}
	q := make([]uint32, 0, p.free.n)
	for i := 0; i < p.free.n; i++ {
		q = append(q, p.free.buf[(p.free.head+i)%len(p.free.buf)])
	}
	if len(q) > capacity-p.max+1 {
		t.Fatalf("%d addresses queued, the ring has %d slots", len(q), capacity-p.max+1)
	}
	seen := map[uint32]bool{}
	for _, ip := range q {
		if seen[ip] {
			t.Fatalf("address %v twice in free queue", addr(ip))
		}
		seen[ip] = true
		if _, ok := p.byIP[ip]; ok {
			t.Fatalf("address %v both free and mapped", addr(ip))
		}
	}
	if p.virgin+len(p.byIP)+len(q) != capacity {
		t.Fatalf("partition broken: virgin %d + mapped %d + free %d != %d", p.virgin, len(p.byIP), len(q), capacity)
	}
}

type modelMapping struct {
	name    string
	lastUse time.Time
	pins    int
}

// TestPoolModel drives random pools (any size limit, reuse delays of 0, 1 or
// 2 minutes) with random lookups, connections opening and closing, and time
// passing, and checks after every step against a model: no address is mapped
// to two names, no mapping is dropped while pinned or before the reuse delay,
// freed addresses come back oldest first after every never-used one, and every
// address is never used, mapped or queued, exactly once.
func TestPoolModel(t *testing.T) {
	for seed := int64(1); seed <= 150; seed++ {
		r := rand.New(rand.NewSource(seed))
		c := &clock{t: time.Unix(1_000_000, 0)}
		// /27: 32 addresses, 2 reserved, last skipped -> 29 usable
		capacity := 29
		max := 1 + r.Intn(29)
		reuse := time.Duration(r.Intn(3)) * time.Minute
		p := mustPool(t, "10.0.0.0/27", 2, max, withClock(c.now), WithReuseAfter(reuse))
		model := map[netip.Addr]*modelMapping{}
		type pin struct {
			ip      netip.Addr
			name    string
			release func()
			done    bool
		}
		var pins []*pin
		var freedQueue []netip.Addr
		virginSeen := map[netip.Addr]bool{}
		for step := 0; step < 2000; step++ {
			switch op := r.Intn(10); {
			case op < 4:
				name := fmt.Sprintf("n%d.test", r.Intn(60))
				before := map[netip.Addr]modelMapping{}
				for a, m := range model {
					before[a] = *m
				}
				a, ok := p.Lookup(name)
				if !ok {
					break
				}
				// was name mapped already?
				var existing netip.Addr
				for ia, m := range before {
					if m.name == name {
						existing = ia
					}
				}
				if existing.IsValid() {
					if existing != a {
						t.Fatalf("seed %d: name %s moved from %v to %v", seed, name, existing, a)
					}
					model[a].lastUse = c.t
					break
				}
				// new mapping: any mapping that disappeared must have been evictable
				for ia, m := range before {
					if got, ok := peek(p, ia); !ok || got != m.name {
						if m.pins > 0 {
							t.Fatalf("seed %d: pinned mapping %v (%s) evicted", seed, ia, m.name)
						}
						if c.t.Sub(m.lastUse) < reuse {
							t.Fatalf("seed %d: mapping %v (%s) evicted %v after last use", seed, ia, m.name, c.t.Sub(m.lastUse))
						}
						delete(model, ia)
						freedQueue = append(freedQueue, ia)
					}
				}
				if m, ok := model[a]; ok {
					t.Fatalf("seed %d: address %v given to %s while mapped to %s", seed, a, name, m.name)
				}
				// FIFO: after virgins are gone, the address must be the oldest freed one
				if virginSeen[a] {
					if len(freedQueue) == 0 || freedQueue[0] != a {
						t.Fatalf("seed %d: reused %v not oldest freed (queue %v)", seed, a, freedQueue)
					}
					freedQueue = freedQueue[1:]
				} else {
					virginSeen[a] = true
				}
				model[a] = &modelMapping{name: name, lastUse: c.t}
			case op < 6:
				a := addr(p.first + uint32(r.Intn(capacity+3)) - 1)
				name, rel, ok := p.Acquire(a)
				m, mapped := model[a]
				if ok != mapped {
					t.Fatalf("seed %d: Acquire(%v) ok=%v mapped=%v", seed, a, ok, mapped)
				}
				if ok {
					if name != m.name {
						t.Fatalf("seed %d: Acquire name %s want %s", seed, name, m.name)
					}
					m.pins++
					m.lastUse = c.t
					pins = append(pins, &pin{ip: a, name: name, release: rel})
				}
			case op < 8:
				if len(pins) == 0 {
					break
				}
				i := r.Intn(len(pins))
				pn := pins[i]
				if got, ok := peek(p, pn.ip); !ok || got != pn.name {
					t.Fatalf("seed %d: pinned %v lost its mapping (%q %v)", seed, pn.ip, got, ok)
				}
				pn.release()
				if r.Intn(3) == 0 {
					pn.release() // idempotent
				}
				m := model[pn.ip]
				m.pins--
				if m.pins == 0 {
					m.lastUse = c.t
				}
				pins = append(pins[:i], pins[i+1:]...)
				if r.Intn(4) == 0 {
					pn.release() // late extra call after removal from our list
				}
			default:
				c.advance(time.Duration(r.Intn(90)) * time.Second)
			}
			checkInv(t, p, capacity)
		}
	}
}
