package engine

// Hermetic engine tests: no root, no TUN. The engine's fd is one end of a
// datagram socketpair; a second gVisor stack on the other end plays the role
// of "apps on the phone". This exercises the real fdbased link, netstack TCP
// handshake, fake DNS and the dial-before-accept / fail-closed logic.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/link/sniffer"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"

	"proxyflow/spike/engine/adapters"
	"proxyflow/spike/engine/fakedns"
)

type fakeDialer struct {
	mu      sync.Mutex
	targets []adapters.Target
}

func (d *fakeDialer) Dial(ctx context.Context, t adapters.Target) (net.Conn, error) {
	d.mu.Lock()
	d.targets = append(d.targets, t)
	d.mu.Unlock()
	if t.Host == "fail.test" {
		return nil, &adapters.Error{Code: adapters.CodeAuthFailed, Detail: "test"}
	}
	a, b := net.Pipe()
	go func() { defer b.Close(); io.Copy(b, b) }() // echo server "behind the proxy"
	return a, nil
}

func (d *fakeDialer) last() adapters.Target {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.targets[len(d.targets)-1]
}

func socketpair(t *testing.T) (int, int) {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	for _, fd := range fds {
		unix.SetNonblock(fd, true)
	}
	return fds[0], fds[1]
}

func clientStack(t *testing.T, fd int) *stack.Stack {
	t.Helper()
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep, err := fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: 1500})
	if err != nil {
		t.Fatal(err)
	}
	if e := s.CreateNIC(1, ep); e != nil {
		t.Fatal(e)
	}
	s.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{10, 0, 0, 2}).WithPrefix()}, stack.AddressProperties{})
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return s
}

func startEngine(t *testing.T) (*Engine, *stack.Stack, *fakeDialer) {
	t.Helper()
	efd, cfd := socketpair(t)
	pool, _ := fakedns.NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 0)
	d := &fakeDialer{}
	cfg := Config{FD: efd, Dialer: d, FakeDNS: &fakedns.Responder{Pool: pool, TTL: 5}, DialTimeout: 2 * time.Second}
	if dir := os.Getenv("PF_TEST_PCAP_DIR"); dir != "" {
		f, _ := os.Create(filepath.Join(dir, fmt.Sprintf("%s-%d.pcap", t.Name(), time.Now().UnixNano())))
		t.Cleanup(func() { f.Close() })
		cfg.WrapLink = func(l stack.LinkEndpoint) stack.LinkEndpoint {
			w, err := sniffer.NewWithWriter(l, f, 256)
			if err != nil {
				t.Fatal(err)
			}
			return w
		}
	}
	cfg.Events = func(ev Event) { recordEvent(ev) }
	e, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	cs := clientStack(t, cfd)
	t.Cleanup(func() { e.Stop(); cs.Close(); cs.Wait(); unix.Close(efd); unix.Close(cfd) })
	if dir := os.Getenv("PF_TEST_PCAP_DIR"); dir != "" {
		t.Cleanup(func() {
			if !t.Failed() { // keep captures only for failing runs
				matches, _ := filepath.Glob(filepath.Join(dir, t.Name()+"-*.pcap"))
				for _, m := range matches {
					os.Remove(m)
				}
			}
		})
	}
	return e, cs, d
}

// resolve queries the fake DNS like a stub resolver does (1 s timeout, up to
// 3 attempts). Retries matter only for the gVisor *test client* stack, which
// under race-detector CPU starvation can miss a reply that arrives before its
// UDP endpoint is ready; kernel clients (the real case) are unaffected.
func resolve(t *testing.T, cs *stack.Stack, name string) netip.Addr {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 42, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name + "."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		c, err := gonet.DialUDP(cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 18, 0, 2}), Port: 53}, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		c.SetDeadline(time.Now().Add(time.Second))
		c.Write(q)
		buf := make([]byte, 512)
		n, err := c.Read(buf)
		c.Close()
		if err != nil {
			lastErr = err
			continue
		}
		var m dnsmessage.Message
		if err := m.Unpack(buf[:n]); err != nil || len(m.Answers) != 1 {
			t.Fatalf("bad DNS answer: %v %d", err, len(m.Answers))
		}
		return netip.AddrFrom4(m.Answers[0].Body.(*dnsmessage.AResource).A)
	}
	t.Fatalf("DNS read after 3 attempts: %v", lastErr)
	return netip.Addr{}
}

func dialTCP(cs *stack.Stack, ip netip.Addr, port uint16) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	return gonet.DialContextTCP(ctx, cs, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(ip.AsSlice()), Port: port}, ipv4.ProtocolNumber)
}

// dialOK is used where the connection is expected to succeed. Known test-client
// artifact (root-caused with an in-engine pcap, see docs/m1/09-feasibility-report.md):
// the gVisor client stack occasionally RSTs a valid SYN-ACK that arrives within
// ~0.5 ms of its SYN while starved by the race detector; the engine then
// correctly refuses that half-open flow (APP_HANDSHAKE_FAILED). Real apps use
// the kernel TCP stack and retry connects, so the test retries too.
func dialOK(t *testing.T, e *Engine, cs *stack.Stack, ip netip.Addr, port uint16, proto tcpip.NetworkProtocolNumber) net.Conn {
	t.Helper()
	var lastErr error
	for attempt := 0; attempt < 5; attempt++ {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		c, err := gonet.DialContextTCP(ctx, cs, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(ip.AsSlice()), Port: port}, proto)
		cancel()
		if err == nil {
			return c
		}
		lastErr = err
	}
	st := e.Stats()
	t.Fatalf("dial %s:%d failed 5 times: %v; failures=%v tcpstats=%s events=%v", ip, port, lastErr, st.Failures, tcpStackStats(e), lastEvents(e))
	return nil
}

func TestHostnameIsSentToProxyAndDataFlows(t *testing.T) {
	e, cs, d := startEngine(t)
	defer e.Stop()
	ip := resolve(t, cs, "ok.test")
	if !netip.MustParsePrefix("198.18.0.0/15").Contains(ip) {
		t.Fatalf("A answer %s must come from the fake pool", ip)
	}
	c := dialOK(t, e, cs, ip, 80, ipv4.ProtocolNumber)
	defer c.Close()
	if got := d.last(); got.Host != "ok.test" || got.Port != 80 {
		t.Fatalf("proxy must receive the hostname, got %+v", got)
	}
	c.SetDeadline(time.Now().Add(3 * time.Second))
	msg := []byte("hello through the tunnel")
	c.Write(msg)
	buf := make([]byte, len(msg))
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != string(msg) {
		t.Fatalf("echo through engine failed: %q %v", buf, err)
	}
}

func TestProxyFailureRefusesConnection(t *testing.T) {
	e, cs, _ := startEngine(t)
	defer e.Stop()
	ip := resolve(t, cs, "fail.test")
	start := time.Now()
	_, err := dialTCP(cs, ip, 443)
	if err == nil {
		t.Fatalf("connection must be refused when the proxy fails")
	}
	if !isRefused(err) {
		t.Fatalf("expected a RST (connection refused), got %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("refusal must be immediate (RST), took %s", time.Since(start))
	}
	s := e.Stats()
	// >= 1: the app may retransmit its SYN, which triggers another (failing) dial.
	if s.Failures["AUTH_FAILED"] < 1 || s.LastProxyError != "AUTH_FAILED" {
		t.Fatalf("failure must be categorised for the UI: %+v", s)
	}
}

func isRefused(err error) bool {
	return err != nil && (errors.Is(err, unix.ECONNREFUSED) || containsAny(err.Error(), "refused", "reset"))
}

func containsAny(s string, subs ...string) bool {
	for _, x := range subs {
		for i := 0; i+len(x) <= len(s); i++ {
			if s[i:i+len(x)] == x {
				return true
			}
		}
	}
	return false
}

func TestIPLiteralAndStaleMapping(t *testing.T) {
	e, cs, d := startEngine(t)
	defer e.Stop()
	c := dialOK(t, e, cs, netip.MustParseAddr("203.0.113.5"), 443, ipv4.ProtocolNumber)
	c.Close()
	if got := d.last(); got.Host != "" || got.Addr != netip.MustParseAddr("203.0.113.5") {
		t.Fatalf("IP literal must be forwarded by address, got %+v", got)
	}
	if _, err := dialTCP(cs, netip.MustParseAddr("198.19.200.200"), 80); err == nil {
		t.Fatalf("unmapped fake IP must be refused")
	}
	if e.Stats().Failures["STALE_DNS_MAPPING"] < 1 {
		t.Fatalf("stale mapping not counted: %+v", e.Stats().Failures)
	}
}

func TestStopReleasesGoroutines(t *testing.T) {
	base := runtime.NumGoroutine()
	e, cs, _ := startEngine(t)
	ip := resolve(t, cs, "ok.test")
	var conns []net.Conn
	for i := 0; i < 20; i++ {
		conns = append(conns, dialOK(t, e, cs, ip, 80, ipv4.ProtocolNumber))
	}
	// The app side sees the handshake complete slightly before the engine
	// goroutine records the flow, so poll briefly.
	for i := 0; i < 100 && e.Stats().TCPActive != 20; i++ {
		time.Sleep(10 * time.Millisecond)
	}
	if a := e.Stats().TCPActive; a != 20 {
		t.Fatalf("expected 20 active flows, got %d", a)
	}
	t0 := time.Now()
	e.Stop() // with 20 flows still open
	if d := time.Since(t0); d > time.Second {
		t.Fatalf("Stop with 20 open flows took %v, want < 1 s", d)
	} else {
		t.Logf("Stop with 20 open flows took %v", d)
	}
	for _, c := range conns {
		c.Close()
	}
	cs.Close()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if runtime.NumGoroutine() <= base+5 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	buf := make([]byte, 1<<16)
	n := runtime.Stack(buf, true)
	t.Fatalf("goroutine leak after Stop: base=%d now=%d\n%s", base, runtime.NumGoroutine(), buf[:n])
}

// --- IPv6 (hermetic: works even where the host kernel has IPv6 disabled) ----

func addIPv6(t *testing.T, cs *stack.Stack) {
	t.Helper()
	a := netip.MustParseAddr("fd00:66::1").As16()
	if err := cs.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv6.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom16(a).WithPrefix()}, stack.AddressProperties{}); err != nil {
		t.Fatal(err)
	}
	cs.AddRoute(tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: 1})
}

func TestIPv6TCPIsForwardedByAddress(t *testing.T) {
	e, cs, d := startEngine(t)
	defer e.Stop()
	addIPv6(t, cs)
	dst := netip.MustParseAddr("2001:db8::1")
	c := dialOK(t, e, cs, dst, 443, ipv6.ProtocolNumber)
	defer c.Close()
	if got := d.last(); got.Addr != dst || got.Host != "" || got.Port != 443 {
		t.Fatalf("IPv6 target must reach the proxy dialer by address, got %+v", got)
	}
	if got := d.last().HostPort(); got != "[2001:db8::1]:443" {
		t.Fatalf("CONNECT form for IPv6 must be bracketed, got %s", got)
	}
	c.SetDeadline(time.Now().Add(3 * time.Second))
	c.Write([]byte("v6"))
	buf := make([]byte, 2)
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != "v6" {
		t.Fatalf("IPv6 data path: %q %v", buf, err)
	}
}

func TestIPv6UDPRejectedAndDNSAnswered(t *testing.T) {
	e, cs, _ := startEngine(t)
	defer e.Stop()
	addIPv6(t, cs)
	// Non-DNS UDP over IPv6 is rejected, never forwarded.
	u, err := gonet.DialUDP(cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom16(netip.MustParseAddr("2001:db8::1").As16()), Port: 5000}, ipv6.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	u.Write([]byte("quic-like"))
	u.Close()
	// DNS to an arbitrary IPv6 resolver address is intercepted and answered.
	dc, err := gonet.DialUDP(cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom16(netip.MustParseAddr("2001:4860:4860::8888").As16()), Port: 53}, ipv6.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer dc.Close()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 7, RecursionDesired: true})
	b.StartQuestions()
	b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("v6.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	buf := make([]byte, 512)
	var n int
	for attempt := 0; attempt < 3; attempt++ { // stub-resolver style retry (see resolve)
		dc.SetDeadline(time.Now().Add(time.Second))
		dc.Write(q)
		if n, err = dc.Read(buf); err == nil {
			break
		}
	}
	if err != nil {
		t.Fatalf("DNS over IPv6 not answered: %v", err)
	}
	var m dnsmessage.Message
	if err := m.Unpack(buf[:n]); err != nil || len(m.Answers) != 1 {
		t.Fatalf("bad answer: %v", err)
	}
	for i := 0; i < 50 && e.Stats().UDPRejected < 1; i++ {
		time.Sleep(10 * time.Millisecond)
	}
	if e.Stats().UDPRejected < 1 {
		t.Fatalf("IPv6 UDP datagram must be counted as rejected")
	}
}

func tcpStackStats(e *Engine) string {
	ts := e.stack.Stats().TCP
	return fmt.Sprintf("passiveOpen=%d failedAttempts=%d resetsSent=%d fwdInFlightDrop=%d listenOverflowSynDrop=%d segmentsRecv=%d invalidSegs=%d ipRecv=%d ipMalformed=%d",
		ts.PassiveConnectionOpenings.Value(), ts.FailedConnectionAttempts.Value(), ts.ResetsSent.Value(),
		ts.ForwardMaxInFlightDrop.Value(), ts.ListenOverflowSynDrop.Value(), ts.ValidSegmentsReceived.Value(),
		ts.InvalidSegmentsReceived.Value(), e.stack.Stats().IP.PacketsReceived.Value(), e.stack.Stats().IP.MalformedPacketsReceived.Value())
}

var evMu sync.Mutex
var evLog []Event

func recordEvent(ev Event) {
	evMu.Lock()
	evLog = append(evLog, ev)
	if len(evLog) > 50 {
		evLog = evLog[len(evLog)-50:]
	}
	evMu.Unlock()
}

func lastEvents(_ *Engine) []string {
	evMu.Lock()
	defer evMu.Unlock()
	var out []string
	for _, ev := range evLog {
		if ev.Type == EvConnFail {
			out = append(out, ev.Code+": "+ev.Detail)
		}
	}
	return out
}
