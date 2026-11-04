package tunnel

// M6 routing behaviour (ADR-0011): DNS over TCP answered locally, TCP to the
// engine's own addresses refused locally, and the counters the app's
// diagnostics show (proxy timeouts, failures of flows to names).

import (
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"testing"
	"time"

	"github.com/bibekthapamgr/app/engine/fakedns"
	"golang.org/x/net/dns/dnsmessage"
)

// tcpQuery sends one DNS query over c with RFC 7766 framing and reads the answer.
func tcpQuery(t *testing.T, c net.Conn, id uint16, name string, typ dnsmessage.Type) dnsmessage.Message {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: id, RecursionDesired: true})
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name + "."), Type: typ, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	framed := binary.BigEndian.AppendUint16(nil, uint16(len(q)))
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write(append(framed, q...)); err != nil {
		t.Fatalf("writing the query for %s: %v", name, err)
	}
	head := make([]byte, 2)
	if _, err := io.ReadFull(c, head); err != nil {
		t.Fatalf("no answer for %s over TCP: %v", name, err)
	}
	body := make([]byte, binary.BigEndian.Uint16(head))
	if _, err := io.ReadFull(c, body); err != nil {
		t.Fatalf("short answer for %s over TCP: %v", name, err)
	}
	var m dnsmessage.Message
	if err := m.Unpack(body); err != nil {
		t.Fatal(err)
	}
	if m.Header.ID != id || !m.Header.Response {
		t.Fatalf("answer for %s has the wrong header: %+v", name, m.Header)
	}
	return m
}

func TestDNSOverTCPIsAnsweredLocally(t *testing.T) {
	e, d := startEngine(t, nil, func(c *Config) { c.DNSIdleTimeout = 500 * time.Millisecond })
	addIPv6(t, e.cs)
	viaUDP := resolve(t, e.cs, "tcp.test")
	servers := []netip.Addr{
		netip.MustParseAddr("198.18.0.2"),           // the VPN's DNS server
		netip.MustParseAddr("8.8.8.8"),              // a resolver an app chose itself
		netip.MustParseAddr("2001:4860:4860::8888"), // the same over IPv6
	}
	queries := 0
	for i, server := range servers {
		c := dialOK(t, e, server, 53)
		m := tcpQuery(t, c, uint16(100+i), "tcp.test", dnsmessage.TypeA)
		queries++
		if m.Header.RCode != dnsmessage.RCodeSuccess || len(m.Answers) != 1 {
			t.Fatalf("A over TCP to %s: %v, %d answers", server, m.Header.RCode, len(m.Answers))
		}
		if got := netip.AddrFrom4(m.Answers[0].Body.(*dnsmessage.AResource).A); got != viaUDP {
			t.Fatalf("A over TCP to %s gave %s, over UDP %s: one name, one address", server, got, viaUDP)
		}
		// More than one query per connection (RFC 7766 §6.2.1).
		m = tcpQuery(t, c, uint16(200+i), "tcp.test", dnsmessage.TypeAAAA)
		queries++
		if m.Header.RCode != dnsmessage.RCodeSuccess || len(m.Answers) != 0 {
			t.Fatalf("AAAA over TCP to %s: %v, %d answers; want no records", server, m.Header.RCode, len(m.Answers))
		}
		// An idle connection is closed by the engine.
		_ = c.SetReadDeadline(time.Now().Add(3 * time.Second))
		if _, err := c.Read(make([]byte, 1)); err == nil {
			t.Fatalf("the idle DNS connection to %s stayed open", server)
		}
		c.Close()
	}
	for _, target := range d.targets {
		if target.Port == 53 {
			t.Fatalf("a DNS query went to the proxy: %+v", target)
		}
	}
	waitFor(t, func() bool { return e.Stats().Flows == 0 }, "the DNS connections to end")
	s := e.Stats()
	if s.DNSOverTCP != int64(queries) || s.DNS["mapped"] < 4 || s.DNS["nodata"] < 3 {
		t.Fatalf("queries over TCP must be counted: tcp=%d dns=%v", s.DNSOverTCP, s.DNS)
	}
	if len(s.Failures) != 0 || s.TCPFailed != 0 {
		t.Fatalf("DNS over TCP is no failure: %v", s.Failures)
	}
}

func TestDNSOverTCPEndsOnAnUnanswerableMessage(t *testing.T) {
	e, _ := startEngine(t, nil)
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 7, Response: true})
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("reply.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	response, _ := b.Finish()
	for name, framed := range map[string][]byte{
		"empty message": {0, 0},
		"too long":      {0xff, 0xff},
		"a response":    append(binary.BigEndian.AppendUint16(nil, uint16(len(response))), response...),
	} {
		c := dialOK(t, e, netip.MustParseAddr("198.18.0.2"), 53)
		_ = c.SetDeadline(time.Now().Add(3 * time.Second))
		_, _ = c.Write(framed)
		if n, err := c.Read(make([]byte, 16)); err == nil {
			t.Fatalf("%s: the engine answered %d bytes instead of closing", name, n)
		}
		c.Close()
	}
	waitFor(t, func() bool { return e.Stats().Flows == 0 }, "the DNS connections to end")
	if got := e.Stats().DNSOverTCP; got != 0 {
		t.Fatalf("dns_tcp_queries counts answered queries only, got %d", got)
	}
}

// DNS-over-TCP connections have their own slots: connections an app keeps open cannot take the ones DNS over UDP
// needs (with shared slots, one app holding TCP connections to port 53 would stop every app's lookups).
func TestDNSOverTCPHasItsOwnSlots(t *testing.T) {
	e, _ := startEngine(t, nil, func(c *Config) {
		c.MaxDNSOverTCP, c.MaxDNSFlows = 1, 1
		c.DNSIdleTimeout = 10 * time.Second // the held connection keeps its slot for the whole test
	})
	server := netip.MustParseAddr("198.18.0.2")
	held := dialOK(t, e, server, 53)
	tcpQuery(t, held, 1, "held.test", dnsmessage.TypeA) // its handler now holds the one DNS-over-TCP slot
	if _, err := dialTCP(e.cs, server, 53); !isRefused(err) {
		t.Fatalf("a second DNS-over-TCP connection beyond MaxDNSOverTCP: %v, want a refusal", err)
	}
	if got := e.Stats().DNSDropped; got != 1 {
		t.Fatalf("the refused DNS-over-TCP connection must be counted as dropped: %d", got)
	}
	resolve(t, e.cs, "udp.test") // DNS over UDP still has its own slot
	if got := e.Stats().DNSDropped; got != 1 {
		t.Fatalf("a lookup over UDP was dropped while a DNS-over-TCP connection was held: %d dropped", got)
	}
	held.Close()
	waitFor(t, func() bool { return len(e.tcpDNS) == 0 }, "the held connection's slot to be released")
	c := dialOK(t, e, server, 53)
	tcpQuery(t, c, 2, "again.test", dnsmessage.TypeA)
	c.Close()
}

func TestTCPToTheEnginesOwnAddressesIsRefusedLocally(t *testing.T) {
	// LocalAddrs holds the TUN's IPv6 address in the app (fd00:66::1); the test client already uses that one as its
	// own source address, so another stands in for it here.
	local6 := netip.MustParseAddr("fd00:66::53")
	e, d := startEngine(t, nil, func(c *Config) { c.LocalAddrs = []netip.Addr{local6} })
	addIPv6(t, e.cs)
	for _, target := range []struct {
		addr string
		port uint16
	}{
		{"198.18.0.2", 853},    // Android's opportunistic DNS-over-TLS probe to the VPN's DNS server
		{"198.18.0.1", 443},    // the TUN's own address
		{local6.String(), 853}, // an address in LocalAddrs
		{"198.18.0.200", 8080}, // anywhere else in the reserved start of the range
	} {
		start := time.Now()
		if _, err := dialTCP(e.cs, netip.MustParseAddr(target.addr), target.port); !isRefused(err) {
			t.Fatalf("%s:%d: got %v, want a RST", target.addr, target.port, err)
		}
		if el := time.Since(start); el > 2*time.Second {
			t.Fatalf("%s:%d: the refusal took %v", target.addr, target.port, el)
		}
	}
	if len(d.targets) != 0 {
		t.Fatalf("the proxy was asked for the engine's own addresses: %+v", d.targets)
	}
	s := e.Stats()
	if s.LocalRefused < 4 {
		t.Fatalf("local refusals not counted: %d", s.LocalRefused)
	}
	if len(s.Failures) != 0 || s.TCPFailed != 0 || e.events.count(EvConnFail) != 0 {
		t.Fatalf("a connection to the engine's own address is no failure of the proxy or an app: %v", s.Failures)
	}
}

func TestProxyTimeoutsAreCounted(t *testing.T) {
	e, _ := startEngine(t, nil, func(c *Config) { c.DialTimeout = 300 * time.Millisecond })
	blocked := resolve(t, e.cs, "block.test") // the fake proxy never answers: HANDSHAKE_TIMEOUT at the deadline
	if _, err := dialTCP(e.cs, blocked, 80); !isRefused(err) {
		t.Fatalf("a timed-out tunnel must refuse the app's connection, got %v", err)
	}
	failed := resolve(t, e.cs, "fail.test")
	if _, err := dialTCP(e.cs, failed, 80); !isRefused(err) {
		t.Fatalf("got %v, want a RST", err)
	}
	s := e.Stats()
	if s.ProxyTimeouts < 1 || s.Failures["HANDSHAKE_TIMEOUT"] < 1 {
		t.Fatalf("the proxy timeout must be counted: timeouts=%d failures=%v", s.ProxyTimeouts, s.Failures)
	}
	if s.ProxyTimeouts != s.Failures["HANDSHAKE_TIMEOUT"] {
		t.Fatalf("only timeouts are proxy timeouts: timeouts=%d failures=%v", s.ProxyTimeouts, s.Failures)
	}
}

func TestFailuresOfFlowsToNamesAreToldApart(t *testing.T) {
	e, _ := startEngine(t, nil)
	byName := resolve(t, e.cs, "unreach.test")
	if _, err := dialTCP(e.cs, byName, 80); !isRefused(err) {
		t.Fatalf("got %v, want a RST", err)
	}
	if _, err := dialTCP(e.cs, netip.MustParseAddr("203.0.113.99"), 80); !isRefused(err) {
		t.Fatalf("got %v, want a RST", err)
	}
	s := e.Stats()
	unreachable, byNameOnly := s.Failures["TARGET_UNREACHABLE"], s.HostnameFailures["TARGET_UNREACHABLE"]
	if byNameOnly < 1 || unreachable <= byNameOnly {
		t.Fatalf("a name and an address the proxy can't reach: failures=%v hostname_failures=%v", s.Failures, s.HostnameFailures)
	}
	if s.ConsecutiveProxyFails != 0 {
		t.Fatalf("target-level failures leave the escalation count alone: %d", s.ConsecutiveProxyFails)
	}
}

// Home counts the names apps looked up during this connection, each once. The placeholder pool is shared by every
// engine in the app's process (pfengine), so its size would count earlier connections' names too.
func TestNamesAreCountedOncePerEngine(t *testing.T) {
	shared, _ := fakedns.NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 0)
	withShared := func(c *Config) { c.FakeDNS = &fakedns.Responder{Pool: shared, TTL: 5} }
	first, _ := startEngine(t, nil, withShared)
	for _, name := range []string{"a.test", "b.test", "a.test", "A.TEST"} {
		resolve(t, first.cs, name)
	}
	if got := first.Stats().DNSNames; got != 2 {
		t.Fatalf("two different names (one asked three times, in two spellings), got %d", got)
	}
	second, _ := startEngine(t, nil, withShared)
	resolve(t, second.cs, "a.test")
	resolve(t, second.cs, "c.test")
	if got := second.Stats().DNSNames; got != 2 {
		t.Fatalf("the second engine counts its own names only, got %d", got)
	}
	if shared.Len() != 3 {
		t.Fatalf("the shared pool holds every engine's names: %d", shared.Len())
	}
}
