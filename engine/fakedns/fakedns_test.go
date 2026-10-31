package fakedns

import (
	"fmt"
	"net/netip"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func mustPool(t *testing.T, cidr string, reserved, max int, opts ...PoolOption) *Pool {
	t.Helper()
	p, err := NewPool(netip.MustParsePrefix(cidr), reserved, max, opts...)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func mustLookup(t *testing.T, p *Pool, name string) netip.Addr {
	t.Helper()
	a, ok := p.Lookup(name)
	if !ok {
		t.Fatalf("lookup %s refused", name)
	}
	return a
}

// clock is a manual time source for reuse-delay tests.
type clock struct{ t time.Time }

func (c *clock) now() time.Time          { return c.t }
func (c *clock) advance(d time.Duration) { c.t = c.t.Add(d) }

func TestPoolAllocationAndReverse(t *testing.T) {
	p := mustPool(t, "198.18.0.0/15", 256, 0)
	a := mustLookup(t, p, "example.com")
	b := mustLookup(t, p, "example.org")
	if a == b {
		t.Fatalf("distinct names must get distinct addresses")
	}
	lo, hi := netip.MustParseAddr("198.18.1.0"), netip.MustParseAddr("198.19.255.254")
	for _, x := range []netip.Addr{a, b} {
		if x.Less(lo) || hi.Less(x) {
			t.Fatalf("%s is outside the allocatable range %s-%s", x, lo, hi)
		}
	}
	if again := mustLookup(t, p, "example.com"); again != a {
		t.Fatalf("lookup must be stable, got %s then %s", a, again)
	}
	if name, ok := p.Reverse(b); !ok || name != "example.org" {
		t.Fatalf("reverse(%s) = %q %v", b, name, ok)
	}
	if _, ok := p.Reverse(netip.MustParseAddr("198.18.0.2")); ok {
		t.Fatalf("reserved DNS address must not map to a name")
	}
	if name, ok := p.Reverse(netip.AddrFrom16(a.As16())); !ok || name != "example.com" {
		t.Fatalf("an IPv4-mapped address must reverse like the IPv4 address")
	}
	if p.Contains(netip.MustParseAddr("8.8.8.8")) || !p.Contains(netip.MustParseAddr("198.19.255.1")) {
		t.Fatalf("Contains is wrong")
	}
}

func TestPoolRejectsBadPrefixes(t *testing.T) {
	for _, c := range []struct {
		cidr     string
		reserved int
	}{
		{"fd00::/64", 0}, {"10.0.0.0/30", 2}, {"10.0.0.0/31", 0}, {"0.0.0.0/0", 0}, {"10.0.0.0/24", -1},
	} {
		if _, err := NewPool(netip.MustParsePrefix(c.cidr), c.reserved, 0); err == nil {
			t.Fatalf("NewPool(%s, %d) must fail", c.cidr, c.reserved)
		}
	}
	if _, err := NewPool(netip.MustParsePrefix("10.0.0.0/24"), 2, 0, withStart(netip.MustParseAddr("10.0.0.1"))); err == nil {
		t.Fatalf("a start inside the reserved block must be refused")
	}
}

// After a process restart an app may still hold addresses from the previous
// run; a random first address makes it unlikely that they map to a name.
func TestPoolStartsAtARandomAddress(t *testing.T) {
	seen := map[netip.Addr]bool{}
	for range 8 {
		seen[mustLookup(t, mustPool(t, "198.18.0.0/15", 256, 0), "example.com")] = true
	}
	if len(seen) < 2 {
		t.Fatalf("eight pools all started at %v", seen)
	}
}

// A mapping is dropped only after ReuseAfter without use; until then a new
// name is refused instead of taking an address an app may still use.
func TestPoolNeverReusesARecentAddress(t *testing.T) {
	c := &clock{t: time.Unix(1_000_000, 0)}
	start := netip.MustParseAddr("10.0.0.2")
	// 16 addresses, 2 reserved, last skipped -> 13 usable, all of them mappable.
	p := mustPool(t, "10.0.0.0/28", 2, 0, withClock(c.now), withStart(start), WithReuseAfter(time.Minute))
	addrs := map[string]netip.Addr{}
	for i := range 13 { // n<i> answered at T+i s
		n := fmt.Sprintf("n%d.test", i)
		addrs[n] = mustLookup(t, p, n)
		c.advance(time.Second)
	}
	if addrs["n0.test"] != start {
		t.Fatalf("first address %s, want %s", addrs["n0.test"], start)
	}
	if _, ok := p.Lookup("new.test"); ok {
		t.Fatalf("a full pool must refuse a new name while every mapping is recent")
	}
	c.advance(46 * time.Second) // T+59 s: n0 is 59 s old
	if _, ok := p.Lookup("new.test"); ok {
		t.Fatalf("a mapping unused for less than the reuse delay must not be recycled")
	}
	// A connection to n0's address counts as use.
	if name, ok := p.Reverse(addrs["n0.test"]); !ok || name != "n0.test" {
		t.Fatalf("n0 must still be mapped, got %q %v", name, ok)
	}
	c.advance(2500 * time.Millisecond) // T+61.5 s: n1 60.5 s old, n2 59.5 s old
	got := mustLookup(t, p, "new.test")
	if got != addrs["n1.test"] {
		t.Fatalf("the least recently used mapping (n1) must be the one recycled, got %s", got)
	}
	if name, _ := p.Reverse(addrs["n0.test"]); name != "n0.test" {
		t.Fatalf("a mapping used by a connection must survive, got %q", name)
	}
	if name, _ := p.Reverse(got); name != "new.test" {
		t.Fatalf("recycled address must map to the new name, got %q", name)
	}
	if _, ok := p.Lookup("newer.test"); ok {
		t.Fatalf("n2 is 59.5 s old and must not be recycled yet")
	}
	if p.Len() != 13 {
		t.Fatalf("pool size must stay bounded, got %d", p.Len())
	}
}

// When the pool has more addresses than mappings, a freed address goes to the
// back of the cycle: the next names get addresses never used before.
func TestPoolFreedAddressesWaitForAFullCycle(t *testing.T) {
	c := &clock{t: time.Unix(1_000_000, 0)}
	start := netip.MustParseAddr("10.0.0.2")
	// 13 usable addresses, at most 4 mappings.
	p := mustPool(t, "10.0.0.0/28", 2, 4, withClock(c.now), withStart(start), WithReuseAfter(time.Minute))
	var order []netip.Addr
	for i := range 13 {
		order = append(order, mustLookup(t, p, fmt.Sprintf("n%d.test", i)))
		c.advance(2 * time.Minute) // every older mapping may be reused
		if p.Len() > 4 {
			t.Fatalf("more than 4 mappings: %d", p.Len())
		}
	}
	seen := map[netip.Addr]bool{}
	for i, a := range order {
		if seen[a] {
			t.Fatalf("address %s handed out twice within one cycle (name %d)", a, i)
		}
		seen[a] = true
	}
	if _, ok := p.Reverse(start); ok {
		t.Fatalf("the evicted first address must no longer map to a name")
	}
	if again := mustLookup(t, p, "wrap.test"); again != start {
		t.Fatalf("after a full cycle the first address comes back, got %s", again)
	}
}

func TestResponderAnswersServfailWhenExhausted(t *testing.T) {
	c := &clock{t: time.Unix(1_000_000, 0)}
	r := &Responder{Pool: mustPool(t, "10.0.0.0/28", 2, 2, withClock(c.now)), TTL: 5}
	for _, n := range []string{"a.test.", "b.test."} {
		if _, out, _ := r.Handle(query(t, 1, n, dnsmessage.TypeA)); out != OutcomeMapped {
			t.Fatalf("%s: %s", n, out)
		}
	}
	resp, out, name := r.Handle(query(t, 0x77, "c.test.", dnsmessage.TypeA))
	h, ans := parse(t, resp)
	if out != OutcomeExhausted || name != "c.test" || h.ID != 0x77 || h.RCode != dnsmessage.RCodeServerFailure || len(ans) != 0 {
		t.Fatalf("exhausted pool: out=%s name=%q hdr=%+v answers=%d", out, name, h, len(ans))
	}
	if _, out, _ := r.Handle(query(t, 2, "a.test.", dnsmessage.TypeA)); out != OutcomeMapped {
		t.Fatalf("names already mapped must still be answered, got %s", out)
	}
	c.advance(DefaultReuseAfter)
	if _, out, _ := r.Handle(query(t, 3, "c.test.", dnsmessage.TypeA)); out != OutcomeMapped {
		t.Fatalf("after the reuse delay the new name must be mapped, got %s", out)
	}
}

func query(t *testing.T, id uint16, name string, typ dnsmessage.Type) []byte {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: id, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name), Type: typ, Class: dnsmessage.ClassINET})
	out, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func parse(t *testing.T, b []byte) (dnsmessage.Header, []dnsmessage.Resource) {
	t.Helper()
	var m dnsmessage.Message
	if err := m.Unpack(b); err != nil {
		t.Fatalf("unpack response: %v", err)
	}
	return m.Header, m.Answers
}

func TestResponder(t *testing.T) {
	r := &Responder{Pool: mustPool(t, "198.18.0.0/15", 256, 0), TTL: 5}

	resp, out, name := r.Handle(query(t, 0x1234, "Web.Test.", dnsmessage.TypeA))
	h, ans := parse(t, resp)
	if out != OutcomeMapped || name != "web.test" || h.ID != 0x1234 || !h.Response || h.RCode != dnsmessage.RCodeSuccess || len(ans) != 1 {
		t.Fatalf("A query: out=%s name=%s hdr=%+v answers=%d", out, name, h, len(ans))
	}
	a := ans[0].Body.(*dnsmessage.AResource).A
	ip := netip.AddrFrom4(a)
	if got, _ := r.Pool.Reverse(ip); got != "web.test" || ans[0].Header.TTL != 5 {
		t.Fatalf("answer %s must map back to web.test with TTL 5", ip)
	}

	for _, typ := range []dnsmessage.Type{dnsmessage.TypeAAAA, dnsmessage.TypeHTTPS, dnsmessage.TypeSVCB} {
		resp, out, _ = r.Handle(query(t, 1, "web.test.", typ))
		h, ans = parse(t, resp)
		if out != OutcomeNoData || h.RCode != dnsmessage.RCodeSuccess || len(ans) != 0 {
			t.Fatalf("%v must be NOERROR/NODATA, got %s rcode=%v answers=%d", typ, out, h.RCode, len(ans))
		}
	}
	resp, out, _ = r.Handle(query(t, 2, "web.test.", dnsmessage.TypeTXT))
	h, _ = parse(t, resp)
	if out != OutcomeRefused || h.RCode != dnsmessage.RCodeRefused {
		t.Fatalf("TXT must be REFUSED in V1, got %s %v", out, h.RCode)
	}
}

func TestResponderRejectsInjectionNames(t *testing.T) {
	r := &Responder{Pool: mustPool(t, "198.18.0.0/15", 256, 0), TTL: 5}
	// Build a raw query whose single label contains CR LF and a colon.
	label := "a\r\nx:1"
	msg := []byte{0xab, 0xcd, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, byte(len(label))}
	msg = append(msg, label...)
	msg = append(msg, 4, 't', 'e', 's', 't', 0, 0, 1, 0, 1)
	resp, out, _ := r.Handle(msg)
	if resp == nil {
		t.Fatalf("expected a REFUSED response")
	}
	h, _ := parse(t, resp)
	if out != OutcomeRefused || h.RCode != dnsmessage.RCodeRefused || r.Pool.Len() != 0 {
		t.Fatalf("crafted name must be refused and never mapped (out=%s rcode=%v len=%d)", out, h.RCode, r.Pool.Len())
	}
}

func TestResponderMalformed(t *testing.T) {
	r := &Responder{Pool: mustPool(t, "198.18.0.0/15", 256, 0), TTL: 5}
	if resp, out, _ := r.Handle([]byte{1, 2, 3}); resp != nil || out != OutcomeIgnored {
		t.Fatalf("short garbage must be dropped")
	}
	// A response (QR=1) must not be answered (no reflection loops).
	q := query(t, 9, "web.test.", dnsmessage.TypeA)
	q[2] |= 0x80
	if resp, out, _ := r.Handle(q); resp != nil || out != OutcomeIgnored {
		t.Fatalf("DNS responses must be ignored")
	}
	// Two questions -> FORMERR.
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 7})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("a.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("b.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	two, _ := b.Finish()
	resp, out, _ := r.Handle(two)
	h, _ := parse(t, resp)
	if out != OutcomeFormErr || h.RCode != dnsmessage.RCodeFormatError {
		t.Fatalf("multi-question query must be FORMERR, got %s", out)
	}
}

func FuzzResponder(f *testing.F) {
	r := &Responder{TTL: 5}
	p, _ := NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 1024)
	r.Pool = p
	f.Add([]byte{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 3, 'w', 'e', 'b', 4, 't', 'e', 's', 't', 0, 0, 1, 0, 1})
	f.Fuzz(func(t *testing.T, data []byte) {
		resp, out, name := r.Handle(data)
		switch out {
		case OutcomeMapped, OutcomeExhausted:
			if name == "" || resp == nil {
				t.Fatalf("%s without name/response", out)
			}
		}
		if p.Len() > 1024 {
			t.Fatalf("pool grew past its bound: %d", p.Len())
		}
	})
}
