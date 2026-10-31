package fakedns

import (
	"fmt"
	"net/netip"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func mustPool(t *testing.T, cidr string, reserved, max int) *Pool {
	t.Helper()
	p, err := NewPool(netip.MustParsePrefix(cidr), reserved, max)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func TestPoolAllocationAndReverse(t *testing.T) {
	p := mustPool(t, "198.18.0.0/15", 256, 0)
	a := p.Lookup("example.com")
	b := p.Lookup("example.org")
	if a == b {
		t.Fatalf("distinct names must get distinct addresses")
	}
	if a != netip.MustParseAddr("198.18.1.0") {
		t.Fatalf("first allocation must skip the reserved block, got %s", a)
	}
	if again := p.Lookup("example.com"); again != a {
		t.Fatalf("lookup must be stable, got %s then %s", a, again)
	}
	if name, ok := p.Reverse(b); !ok || name != "example.org" {
		t.Fatalf("reverse(%s) = %q %v", b, name, ok)
	}
	if _, ok := p.Reverse(netip.MustParseAddr("198.18.0.2")); ok {
		t.Fatalf("reserved DNS address must not map to a name")
	}
	if p.Contains(netip.MustParseAddr("8.8.8.8")) || !p.Contains(netip.MustParseAddr("198.19.255.1")) {
		t.Fatalf("Contains is wrong")
	}
}

func TestPoolLRURecycling(t *testing.T) {
	p := mustPool(t, "10.0.0.0/28", 2, 0) // 16 addresses, 2 reserved, last skipped -> 13 usable
	first := p.Lookup("n0.test")
	for i := 1; i < 13; i++ {
		p.Lookup(fmt.Sprintf("n%d.test", i))
	}
	p.Lookup("n1.test") // touch so n0 is the LRU entry
	recycled := p.Lookup("new.test")
	if recycled != first {
		t.Fatalf("expected LRU address %s to be recycled, got %s", first, recycled)
	}
	if name, _ := p.Reverse(first); name != "new.test" {
		t.Fatalf("recycled address must map to the new name, got %q", name)
	}
	if p.Len() != 13 {
		t.Fatalf("pool size must stay bounded, got %d", p.Len())
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
		if out == OutcomeMapped {
			if name == "" || resp == nil {
				t.Fatalf("mapped without name/response")
			}
		}
	})
}
