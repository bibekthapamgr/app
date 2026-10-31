package tunnel

// Hermetic engine tests: no root, no TUN. The engine's fd is one end of a
// datagram socketpair; a second gVisor stack on the other end plays the role
// of "apps on the phone". This exercises the real fdbased link, netstack TCP
// handshake, fake DNS and the dial-before-accept / fail-closed logic; the
// integration tests add the real adapters and mock proxies (proxytest).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
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

	"github.com/bibekthapamgr/app/engine/adapters"
	"github.com/bibekthapamgr/app/engine/fakedns"
	"github.com/bibekthapamgr/app/engine/internal/proxytest"
)

// fakeDialer stands in for the proxy. By destination name:
// "fail.test" → AUTH_FAILED, "block.test" → waits until the context ends,
// "panic.test" → panics, "stream.test" → a destination that keeps sending,
// "quiet.test" → one that never sends or reads; anything else → an echo
// server behind the "proxy".
type fakeDialer struct {
	mu      sync.Mutex
	targets []adapters.Target
	started chan adapters.Target // optional: told about every dial
	quiet   []net.Conn
}

func (d *fakeDialer) Dial(ctx context.Context, t adapters.Target) (net.Conn, error) {
	d.mu.Lock()
	d.targets = append(d.targets, t)
	d.mu.Unlock()
	if d.started != nil {
		select {
		case d.started <- t:
		default:
		}
	}
	switch t.Host {
	case "fail.test":
		return nil, &adapters.Error{Code: adapters.CodeAuthFailed, Detail: "test"}
	case "block.test":
		<-ctx.Done()
		if errors.Is(ctx.Err(), context.Canceled) {
			return nil, &adapters.Error{Code: adapters.CodeCanceled}
		}
		return nil, &adapters.Error{Code: adapters.CodeTimeout}
	case "panic.test":
		panic("test panic in a dial")
	case "stream.test": // the destination keeps sending, never reads
		a, b := net.Pipe()
		go func() {
			defer b.Close()
			chunk := make([]byte, 512)
			for {
				if _, err := b.Write(chunk); err != nil {
					return
				}
				time.Sleep(50 * time.Millisecond)
			}
		}()
		return a, nil
	case "quiet.test": // the destination never sends and never reads
		a, b := net.Pipe()
		go func() { <-ctx.Done(); _ = b.Close() }()
		d.mu.Lock()
		d.quiet = append(d.quiet, b)
		d.mu.Unlock()
		return a, nil
	}
	a, b := net.Pipe()
	go func() { defer b.Close(); _, _ = io.Copy(b, b) }() // echo server "behind the proxy"
	return a, nil
}

// closeQuiet ends the far side of the quiet destinations (test cleanup).
func (d *fakeDialer) closeQuiet() {
	d.mu.Lock()
	defer d.mu.Unlock()
	for _, c := range d.quiet {
		_ = c.Close()
	}
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
		_ = unix.SetNonblock(fd, true)
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
	_ = s.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{10, 0, 0, 2}).WithPrefix()}, stack.AddressProperties{})
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	return s
}

type testEngine struct {
	*Engine
	cs     *stack.Stack
	efd    int
	events *eventLog
}

// startEngine starts an engine on a socketpair with a client stack on the
// other end. dialer nil means a fakeDialer. Cleanup stops everything.
func startEngine(t *testing.T, dialer TunnelDialer, opts ...func(*Config)) (*testEngine, *fakeDialer) {
	t.Helper()
	efd, cfd := socketpair(t)
	pool, _ := fakedns.NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 0)
	fd, _ := dialer.(*fakeDialer)
	if dialer == nil {
		fd = &fakeDialer{}
		dialer = fd
	}
	log := &eventLog{}
	cfg := Config{FD: efd, Dialer: dialer, FakeDNS: &fakedns.Responder{Pool: pool, TTL: 5}, DialTimeout: 2 * time.Second, Events: log.add}
	if dir := os.Getenv("PF_TEST_PCAP_DIR"); dir != "" {
		f, _ := os.Create(filepath.Join(dir, fmt.Sprintf("%s-%d.pcap", strings.ReplaceAll(t.Name(), "/", "_"), time.Now().UnixNano())))
		t.Cleanup(func() { f.Close() })
		cfg.WrapLink = func(l stack.LinkEndpoint) stack.LinkEndpoint {
			w, err := sniffer.NewWithWriter(l, f, 256)
			if err != nil {
				t.Fatal(err)
			}
			return w
		}
	}
	for _, o := range opts {
		o(&cfg)
	}
	e, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	cs := clientStack(t, cfd)
	t.Cleanup(func() { e.Stop(); cs.Close(); cs.Wait(); unix.Close(efd); unix.Close(cfd) })
	return &testEngine{Engine: e, cs: cs, efd: efd, events: log}, fd
}

// eventLog collects events (the engine delivers them on one goroutine).
type eventLog struct {
	mu  sync.Mutex
	evs []Event
}

func (l *eventLog) add(ev Event) {
	l.mu.Lock()
	l.evs = append(l.evs, ev)
	l.mu.Unlock()
}

func (l *eventLog) all() []Event {
	l.mu.Lock()
	defer l.mu.Unlock()
	return append([]Event(nil), l.evs...)
}

func (l *eventLog) count(typ EventType) int {
	n := 0
	for _, ev := range l.all() {
		if ev.Type == typ {
			n++
		}
	}
	return n
}

// resolve queries the fake DNS like a stub resolver does (1 s timeout, up to
// 3 attempts). Retries matter only for the gVisor *test client* stack, which
// under race-detector CPU starvation can miss a reply that arrives before its
// UDP endpoint is ready; kernel clients (the real case) are unaffected.
func resolve(t *testing.T, cs *stack.Stack, name string) netip.Addr {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 42, RecursionDesired: true})
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name + "."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		c, err := gonet.DialUDP(cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 18, 0, 2}), Port: 53}, ipv4.ProtocolNumber)
		if err != nil {
			t.Fatal(err)
		}
		_ = c.SetDeadline(time.Now().Add(time.Second))
		_, _ = c.Write(q)
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
	proto := tcpip.NetworkProtocolNumber(ipv4.ProtocolNumber)
	if ip.Is6() {
		proto = ipv6.ProtocolNumber
	}
	return gonet.DialContextTCP(ctx, cs, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(ip.AsSlice()), Port: port}, proto)
}

// dialOK is used where the connection is expected to succeed. Known test-client
// artifact (root-caused with an in-engine pcap, see docs/m1/09-feasibility-report.md):
// the gVisor client stack occasionally RSTs a valid SYN-ACK that arrives within
// ~0.5 ms of its SYN while starved by the race detector; the engine then
// correctly refuses that half-open flow (APP_HANDSHAKE_FAILED). Real apps use
// the kernel TCP stack and retry connects, so the test retries too.
func dialOK(t *testing.T, e *testEngine, ip netip.Addr, port uint16) net.Conn {
	t.Helper()
	var lastErr error
	for attempt := 0; attempt < 5; attempt++ {
		c, err := dialTCP(e.cs, ip, port)
		if err == nil {
			return c
		}
		lastErr = err
	}
	st := e.Stats()
	t.Fatalf("dial %s:%d failed 5 times: %v; failures=%v tcpstats=%s", ip, port, lastErr, st.Failures, tcpStackStats(e.Engine))
	return nil
}

func roundTrip(t *testing.T, c net.Conn, msg string) {
	t.Helper()
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Write([]byte(msg)); err != nil {
		t.Fatalf("write: %v", err)
	}
	buf := make([]byte, len(msg))
	if _, err := io.ReadFull(c, buf); err != nil || string(buf) != msg {
		t.Fatalf("echo through the engine failed: %q %v", buf, err)
	}
}

func waitFor(t *testing.T, cond func() bool, what string) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func isRefused(err error) bool {
	return err != nil && (errors.Is(err, unix.ECONNREFUSED) || strings.Contains(err.Error(), "refused") || strings.Contains(err.Error(), "reset"))
}

func TestHostnameIsSentToProxyAndDataFlows(t *testing.T) {
	e, d := startEngine(t, nil)
	ip := resolve(t, e.cs, "ok.test")
	if !netip.MustParsePrefix("198.18.0.0/15").Contains(ip) {
		t.Fatalf("A answer %s must come from the fake pool", ip)
	}
	c := dialOK(t, e, ip, 80)
	defer c.Close()
	if got := d.last(); got.Host != "ok.test" || got.Port != 80 {
		t.Fatalf("proxy must receive the hostname, got %+v", got)
	}
	roundTrip(t, c, "hello through the tunnel")
}

func TestProxyFailureRefusesConnection(t *testing.T) {
	e, _ := startEngine(t, nil)
	ip := resolve(t, e.cs, "fail.test")
	start := time.Now()
	_, err := dialTCP(e.cs, ip, 443)
	if !isRefused(err) {
		t.Fatalf("expected a RST (connection refused), got %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("refusal must be immediate (RST), took %s", time.Since(start))
	}
	s := e.Stats()
	// >= 1: the app may retransmit its SYN, which triggers another (failing) dial.
	if s.Failures["AUTH_FAILED"] < 1 || s.LastProxyError == nil || s.LastProxyError.Code != "AUTH_FAILED" || s.ConsecutiveProxyFails < 1 {
		t.Fatalf("failure must be categorised for the UI: %+v", s)
	}
	waitFor(t, func() bool { return e.events.count(EvConnFail) >= 1 }, "a conn_fail event")
}

func TestIPLiteralAndStaleMapping(t *testing.T) {
	e, d := startEngine(t, nil)
	c := dialOK(t, e, netip.MustParseAddr("203.0.113.5"), 443)
	c.Close()
	if got := d.last(); got.Host != "" || got.Addr != netip.MustParseAddr("203.0.113.5") {
		t.Fatalf("IP literal must be forwarded by address, got %+v", got)
	}
	if _, err := dialTCP(e.cs, netip.MustParseAddr("198.19.200.200"), 80); err == nil {
		t.Fatalf("unmapped fake IP must be refused")
	}
	if e.Stats().Failures["STALE_DNS_MAPPING"] < 1 {
		t.Fatalf("stale mapping not counted: %+v", e.Stats().Failures)
	}
}

// --- lifecycle ---------------------------------------------------------------

func TestLifecycle(t *testing.T) {
	proxytest.CheckLeaks(t)
	efd, cfd := socketpair(t)
	defer unix.Close(efd)
	defer unix.Close(cfd)
	pool, _ := fakedns.NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 0)
	log := &eventLog{}
	e, err := New(Config{FD: efd, Dialer: &fakeDialer{}, FakeDNS: &fakedns.Responder{Pool: pool, TTL: 5}, Events: log.add})
	if err != nil {
		t.Fatal(err)
	}
	if e.State() != StateNew {
		t.Fatalf("state %s, want new", e.State())
	}
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	if e.State() != StateRunning || e.Stats().State != "running" {
		t.Fatalf("state %s, want running", e.State())
	}
	if err := e.Start(); err == nil {
		t.Fatal("a second Start must fail")
	}
	var wg sync.WaitGroup
	for i := 0; i < 3; i++ { // concurrent Stops all return after the engine stopped
		wg.Add(1)
		go func() { defer wg.Done(); e.Stop(); _ = e.State() }()
	}
	wg.Wait()
	if e.State() != StateStopped {
		t.Fatalf("state %s, want stopped", e.State())
	}
	e.Stop()
	if err := e.Start(); err == nil {
		t.Fatal("Start after Stop must fail: build a new engine")
	}
	evs := log.all()
	if len(evs) != 2 || evs[0].Type != EvEngineStarted || evs[1].Type != EvEngineStopped {
		t.Fatalf("events = %+v, want started then stopped", evs)
	}
	if e.ResetFlows() != 0 {
		t.Fatal("ResetFlows on a stopped engine does nothing")
	}

	// Stop without Start.
	idle, _ := New(Config{FD: efd, Dialer: &fakeDialer{}, FakeDNS: &fakedns.Responder{Pool: pool, TTL: 5}})
	idle.Stop()
	if idle.State() != StateStopped {
		t.Fatalf("state %s, want stopped", idle.State())
	}
}

func TestConfigValidation(t *testing.T) {
	pool, _ := fakedns.NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 0)
	fake := &fakedns.Responder{Pool: pool}
	bad := []Config{
		{FD: 3, FakeDNS: fake},
		{FD: 3, Dialer: &fakeDialer{}},
		{FD: -1, Dialer: &fakeDialer{}, FakeDNS: fake},
		{FD: 3, Dialer: &fakeDialer{}, FakeDNS: fake, MTU: 576},
	}
	for i, c := range bad {
		if _, err := New(c); err == nil {
			t.Errorf("case %d should be rejected", i)
		}
	}
	e, err := New(Config{FD: 3, Dialer: &fakeDialer{}, FakeDNS: fake})
	if err != nil {
		t.Fatal(err)
	}
	c := e.cfg
	if c.MTU != DefaultMTU || c.MaxConns != DefaultMaxConns || c.MaxInFlight != DefaultMaxInFlight || c.DialTimeout != DefaultDialTimeout ||
		c.IdleTimeout != DefaultIdleTimeout || c.DNSIdleTimeout != DefaultDNSIdleTimeout || c.RelayBufferBytes != DefaultRelayBufferBytes {
		t.Fatalf("defaults not applied: %+v", c)
	}
}

func TestStartFailureLeavesNothingRunning(t *testing.T) {
	proxytest.CheckLeaks(t)
	pool, _ := fakedns.NewPool(netip.MustParsePrefix("198.18.0.0/15"), 256, 0)
	log := &eventLog{}
	// A descriptor number far above anything open: the TUN endpoint cannot be built.
	e, err := New(Config{FD: 1 << 20, Dialer: &fakeDialer{}, FakeDNS: &fakedns.Responder{Pool: pool}, Events: log.add})
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Start(); err == nil {
		t.Fatal("Start on a bad descriptor must fail")
	}
	if e.State() != StateStopped || e.LastError() == nil || e.LastError().Code != "INTERNAL" {
		t.Fatalf("state %s, last error %+v", e.State(), e.LastError())
	}
	e.Stop() // still safe
}

func TestStopEndsOpenFlowsAndCancelsSetups(t *testing.T) {
	proxytest.CheckLeaks(t)
	d := &fakeDialer{started: make(chan adapters.Target, 16)}
	e, _ := startEngine(t, d)
	okIP := resolve(t, e.cs, "ok.test")
	blockIP := resolve(t, e.cs, "block.test")

	var open []net.Conn
	for i := 0; i < 20; i++ {
		open = append(open, dialOK(t, e, okIP, 80))
	}
	defer func() {
		for _, c := range open {
			c.Close()
		}
	}()
	pending := make(chan error, 3)
	for i := 0; i < 3; i++ {
		go func() {
			c, err := dialTCP(e.cs, blockIP, 443)
			if err == nil {
				c.Close()
				err = errors.New("connected")
			}
			pending <- err
		}()
	}
	waitFor(t, func() bool { st := e.Stats(); return st.TCPActive == 20 && st.TCPConnecting >= 1 }, "20 open flows and set-ups in progress")
	if n := len(e.Flows()); n < 21 {
		t.Fatalf("Flows() lists %d flows, want at least 21", n)
	}

	t0 := time.Now()
	e.Stop()
	if el := time.Since(t0); el > time.Second {
		t.Fatalf("Stop with 20 open flows and pending set-ups took %v, want < 1 s", el)
	} else {
		t.Logf("Stop with 20 open flows and %d set-ups took %v", e.Stats().Canceled, el)
	}
	for _, c := range open { // every app connection has ended
		_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
		if _, err := c.Read(make([]byte, 1)); err == nil {
			t.Fatal("an app connection is still open after Stop")
		} else if isTimeout(err) {
			t.Fatal("an app connection was not ended by Stop")
		}
	}
	st := e.Stats()
	if st.Canceled < 1 || st.Failures["CANCELED"] != 0 || st.ConsecutiveProxyFails != 0 {
		t.Fatalf("canceled set-ups must be counted apart from failures: canceled=%d failures=%v consecutive=%d", st.Canceled, st.Failures, st.ConsecutiveProxyFails)
	}
	if st.Flows != 0 || st.TCPActive != 0 {
		t.Fatalf("flows left after Stop: %d tracked, %d active", st.Flows, st.TCPActive)
	}
	for _, ev := range e.events.all() {
		if ev.Code == "CANCELED" {
			t.Fatal("CANCELED must not be reported as a connection failure")
		}
	}
}

func TestResetFlowsKeepsEngineRunning(t *testing.T) {
	proxytest.CheckLeaks(t)
	e, _ := startEngine(t, nil)
	ip := resolve(t, e.cs, "ok.test")
	a, b := dialOK(t, e, ip, 80), dialOK(t, e, ip, 81)
	defer a.Close()
	defer b.Close()
	roundTrip(t, a, "before")
	waitFor(t, func() bool { return e.Stats().TCPActive == 2 }, "2 open flows")
	if n := e.ResetFlows(); n < 2 {
		t.Fatalf("ResetFlows aborted %d flows, want at least 2", n)
	}
	for _, c := range []net.Conn{a, b} {
		_ = c.SetReadDeadline(time.Now().Add(2 * time.Second))
		_, err := c.Read(make([]byte, 1))
		if err == nil || isTimeout(err) {
			t.Fatalf("a reset flow must end at once, got %v", err)
		}
	}
	if e.State() != StateRunning {
		t.Fatalf("state %s after ResetFlows", e.State())
	}
	c := dialOK(t, e, ip, 82)
	defer c.Close()
	roundTrip(t, c, "after the reset")
	if st := e.Stats(); st.Resets != 1 {
		t.Fatalf("resets = %d", st.Resets)
	}
	waitFor(t, func() bool { return e.events.count(EvFlowsReset) == 1 }, "a flows_reset event")
}

func TestConnectionLimit(t *testing.T) {
	e, _ := startEngine(t, nil, func(c *Config) { c.MaxConns = 2 })
	ip := resolve(t, e.cs, "ok.test")
	a, b := dialOK(t, e, ip, 80), dialOK(t, e, ip, 81)
	defer a.Close()
	defer b.Close()
	waitFor(t, func() bool { return e.Stats().TCPActive == 2 }, "2 open flows")
	if _, err := dialTCP(e.cs, ip, 82); !isRefused(err) {
		t.Fatalf("a third flow must be refused at MaxConns=2, got %v", err)
	}
	if e.Stats().Failures["CONNECTION_LIMIT"] < 1 {
		t.Fatalf("limit not counted: %v", e.Stats().Failures)
	}
	a.Close()
	waitFor(t, func() bool { return e.Stats().TCPActive == 1 }, "the closed flow to free its slot")
	c := dialOK(t, e, ip, 83)
	defer c.Close()
	roundTrip(t, c, "slot freed")
}

func TestFlowsAndPrivacySwitch(t *testing.T) {
	for _, logDst := range []bool{false, true} {
		t.Run(fmt.Sprintf("LogDestinations=%v", logDst), func(t *testing.T) {
			e, _ := startEngine(t, nil, func(c *Config) { c.LogDestinations = logDst })
			ip := resolve(t, e.cs, "private.test")
			c := dialOK(t, e, ip, 8443)
			defer c.Close()
			roundTrip(t, c, "12345")
			var tcpFlow *FlowInfo
			waitFor(t, func() bool {
				for _, f := range e.Flows() {
					if f.Kind == FlowTCP && f.State == FlowOpen && f.BytesUp == 5 && f.BytesDown == 5 {
						tcpFlow = &f
						return true
					}
				}
				return false
			}, "the open flow with its byte counts")
			if tcpFlow.Port != 8443 || tcpFlow.DstKind != "hostname" {
				t.Fatalf("flow = %+v", tcpFlow)
			}
			blob, _ := json.Marshal(struct {
				F []FlowInfo
				E []Event
			}{e.Flows(), e.events.all()})
			if has := strings.Contains(string(blob), "private.test"); has != logDst {
				t.Fatalf("destination in flows/events = %v, want %v: %s", has, logDst, blob)
			}
		})
	}
}

func TestPacketLoopFailureIsReported(t *testing.T) {
	proxytest.CheckLeaks(t)
	e, _ := startEngine(t, nil)
	if err := unix.Shutdown(e.efd, unix.SHUT_RDWR); err != nil { // like a TUN whose interface went away
		t.Fatal(err)
	}
	waitFor(t, func() bool { return e.State() == StateFailed }, "the engine to report the failed packet loop")
	le := e.LastError()
	if le == nil || le.Code != CodeEngineStopped {
		t.Fatalf("last error = %+v, want ENGINE_STOPPED", le)
	}
	waitFor(t, func() bool { return e.events.count(EvEngineFailed) == 1 }, "an engine_failed event")
	e.Stop()
	if e.State() != StateStopped {
		t.Fatalf("state %s after Stop", e.State())
	}
}

func TestSlowEventReceiverNeverStallsTraffic(t *testing.T) {
	proxytest.CheckLeaks(t)
	release := make(chan struct{})
	var delivered atomic.Int64
	e, _ := startEngine(t, nil, func(c *Config) {
		c.EventQueue = 2
		c.Events = func(Event) { delivered.Add(1); <-release }
	})
	ip := resolve(t, e.cs, "ok.test")
	for i := 0; i < 5; i++ {
		c := dialOK(t, e, ip, uint16(80+i))
		roundTrip(t, c, "traffic flows while the event receiver is stuck")
		c.Close()
	}
	if e.Stats().EventsDropped == 0 {
		t.Fatal("events beyond the queue must be dropped and counted")
	}
	t0 := time.Now()
	e.Stop()
	if el := time.Since(t0); el > eventDrainTimeout+time.Second {
		t.Fatalf("Stop waited %v for a stuck event receiver", el)
	}
	atStop := delivered.Load()
	close(release) // the stuck call returns; the events queued behind it must not follow
	time.Sleep(200 * time.Millisecond)
	if got := delivered.Load(); got != atStop {
		t.Fatalf("%d events were delivered after Stop returned", got-atStop)
	}
}

func TestPanicInOneFlowIsIsolated(t *testing.T) {
	e, _ := startEngine(t, nil)
	bad := resolve(t, e.cs, "panic.test")
	if _, err := dialTCP(e.cs, bad, 80); !isRefused(err) {
		t.Fatalf("the panicking flow must be refused, got %v", err)
	}
	if e.Stats().RecoveredPanics < 1 {
		t.Fatal("the panic must be counted")
	}
	c := dialOK(t, e, resolve(t, e.cs, "ok.test"), 80)
	defer c.Close()
	roundTrip(t, c, "the engine survived")
}

func TestDNSFlowLimit(t *testing.T) {
	e, _ := startEngine(t, nil, func(c *Config) { c.MaxDNSFlows = 1; c.DNSIdleTimeout = 5 * time.Second })
	_ = resolve(t, e.cs, "first.test") // its server socket stays for DNSIdleTimeout
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 9, RecursionDesired: true})
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("second.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	c, err := gonet.DialUDP(e.cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4([4]byte{198, 18, 0, 2}), Port: 53}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(300 * time.Millisecond))
	_, _ = c.Write(q)
	if _, err := c.Read(make([]byte, 512)); err == nil {
		t.Fatal("a second DNS socket beyond MaxDNSFlows must not be served")
	}
	if e.Stats().DNSDropped < 1 {
		t.Fatal("dropped DNS socket not counted")
	}
}

// --- through the real adapters and mock proxies --------------------------------

func TestEngineThroughMockProxies(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo := proxytest.NewEcho(t, "tcp4")
	for _, p := range []proxytest.Protocol{proxytest.SOCKS5, proxytest.HTTPConnect} {
		kind := adapters.KindSOCKS5
		if p == proxytest.HTTPConnect {
			kind = adapters.KindHTTPConnect
		}
		t.Run(kind.String(), func(t *testing.T) {
			proxy := proxytest.NewServer(t, proxytest.Config{Protocol: p, Username: "user", Password: "pw",
				Hosts: map[string]string{"web.test": echo.String()}})
			dialer, err := adapters.NewDialer(adapters.ProxyConfig{Kind: kind, Host: proxy.Host(), Port: proxy.Port()},
				adapters.NewCredentials([]byte("user"), []byte("pw")), nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			e, _ := startEngine(t, dialer)
			ip := resolve(t, e.cs, "web.test")
			c := dialOK(t, e, ip, 443)
			roundTrip(t, c, "bytes through gVisor, the adapter and a "+kind.String()+" proxy")
			c.Close()
			recs := proxy.Records()
			if len(recs) == 0 || recs[0].Target != "web.test:443" || recs[0].Username != "user" {
				t.Fatalf("the proxy must see the hostname and the sign-in: %+v", recs)
			}
			// Proxy refuses a destination: the app gets a RST and the code is target-level.
			if _, err := dialTCP(e.cs, resolve(t, e.cs, "unknown.test"), 443); !isRefused(err) {
				t.Fatalf("got %v, want refused", err)
			}
			st := e.Stats()
			if st.Failures["TARGET_UNREACHABLE"] < 1 || st.ConsecutiveProxyFails != 0 {
				t.Fatalf("target-level failure: %+v", st.Failures)
			}
		})
	}
}

func TestProxyOutageFailsClosed(t *testing.T) {
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Hosts: map[string]string{"web.test": echo.String()}})
	dialer, _ := adapters.NewDialer(adapters.ProxyConfig{Kind: adapters.KindSOCKS5, Host: proxy.Host(), Port: proxy.Port()}, nil, nil, nil)
	e, _ := startEngine(t, dialer)
	ip := resolve(t, e.cs, "web.test")
	c := dialOK(t, e, ip, 80)
	roundTrip(t, c, "proxy up")
	c.Close()
	proxy.Close() // the proxy goes away
	for i := 0; i < 3; i++ {
		if _, err := dialTCP(e.cs, ip, 80); !isRefused(err) {
			t.Fatalf("with the proxy down every connection must be refused, got %v", err)
		}
	}
	st := e.Stats()
	if st.Failures["PROXY_UNREACHABLE"] < 3 || st.ConsecutiveProxyFails < 3 || st.LastProxyError.Code != "PROXY_UNREACHABLE" {
		t.Fatalf("outage must escalate: %+v", st)
	}
	if echo.Served() != 1 {
		t.Fatalf("the destination was reached %d times; only the one tunnelled connection may reach it", echo.Served())
	}
}

func TestIdleTimeoutCountsBothDirections(t *testing.T) {
	proxytest.CheckLeaks(t)
	e, d := startEngine(t, nil, func(c *Config) { c.IdleTimeout = 500 * time.Millisecond })
	t.Cleanup(d.closeQuiet)

	// A download: the destination sends every 50 ms, the app never sends.
	down := dialOK(t, e, resolve(t, e.cs, "stream.test"), 80)
	defer down.Close()
	deadline := time.Now().Add(1500 * time.Millisecond) // three idle timeouts
	got := 0
	buf := make([]byte, 4096)
	for time.Now().Before(deadline) {
		_ = down.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
		n, err := down.Read(buf)
		got += n
		if err != nil && !isTimeout(err) {
			t.Fatalf("a flow receiving data was closed after %d bytes: %v", got, err)
		}
	}
	if got == 0 {
		t.Fatal("no data arrived")
	}

	// Silence both ways: closed after the idle timeout and counted.
	quiet := dialOK(t, e, resolve(t, e.cs, "quiet.test"), 80)
	defer quiet.Close()
	_ = quiet.SetReadDeadline(time.Now().Add(3 * time.Second))
	start := time.Now()
	if _, err := quiet.Read(buf); err == nil || isTimeout(err) {
		t.Fatalf("an idle flow must be closed by the engine, got %v", err)
	}
	if el := time.Since(start); el < 400*time.Millisecond || el > 2*time.Second {
		t.Fatalf("idle flow closed after %v, want about 500 ms", el)
	}
	waitFor(t, func() bool { return e.Stats().IdleTimeouts >= 1 }, "the idle timeout to be counted")
}

func TestStalledFlowsHoldLittleMemory(t *testing.T) {
	e, d := startEngine(t, nil)
	t.Cleanup(d.closeQuiet)
	var rcv tcpip.TCPReceiveBufferSizeRangeOption
	var snd tcpip.TCPSendBufferSizeRangeOption
	var moderate tcpip.TCPModerateReceiveBufferOption
	st := e.stack.Load()
	if st.TransportProtocolOption(tcp.ProtocolNumber, &rcv) != nil || st.TransportProtocolOption(tcp.ProtocolNumber, &snd) != nil ||
		st.TransportProtocolOption(tcp.ProtocolNumber, &moderate) != nil {
		t.Fatal("cannot read the netstack's TCP options")
	}
	if rcv.Max != 2*DefaultTCPBufferBytes || snd.Max != 2*DefaultTCPBufferBytes || bool(moderate) {
		t.Fatalf("buffers rcv=%+v snd=%+v moderation=%v", rcv, snd, moderate)
	}

	// An upload into a destination that never reads: the app can push only what
	// the engine's receive window, one relay buffer and its own send buffer hold.
	small := tcpip.TCPSendBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: 16 << 10, Max: 16 << 10}
	if terr := e.cs.SetTransportProtocolOption(tcp.ProtocolNumber, &small); terr != nil {
		t.Fatal(terr)
	}
	c := dialOK(t, e, resolve(t, e.cs, "quiet.test"), 80)
	defer c.Close()
	chunk := make([]byte, 8<<10)
	total := 0
	for {
		_ = c.SetWriteDeadline(time.Now().Add(500 * time.Millisecond))
		n, err := c.Write(chunk)
		total += n
		if err != nil {
			if !isTimeout(err) {
				t.Fatalf("write: %v", err)
			}
			break
		}
		if total > 8<<20 {
			t.Fatal("the engine accepted more than 8 MiB for a destination that never reads")
		}
	}
	limit := 2*DefaultTCPBufferBytes + DefaultRelayBufferBytes + 16<<10 + 64<<10 // + slack for segments in flight
	if total > limit {
		t.Fatalf("a stalled flow took %d bytes, want at most %d", total, limit)
	}
	t.Logf("a stalled upload stopped after %d KiB", total>>10)
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
	e, d := startEngine(t, nil)
	addIPv6(t, e.cs)
	dst := netip.MustParseAddr("2001:db8::1")
	c := dialOK(t, e, dst, 443)
	defer c.Close()
	if got := d.last(); got.Addr != dst || got.Host != "" || got.Port != 443 {
		t.Fatalf("IPv6 target must reach the proxy dialer by address, got %+v", got)
	}
	if got := d.last().HostPort(); got != "[2001:db8::1]:443" {
		t.Fatalf("CONNECT form for IPv6 must be bracketed, got %s", got)
	}
	roundTrip(t, c, "v6")
}

func TestIPv6UDPRejectedAndDNSAnswered(t *testing.T) {
	e, _ := startEngine(t, nil)
	addIPv6(t, e.cs)
	// Non-DNS UDP over IPv6 is rejected, never forwarded.
	u, err := gonet.DialUDP(e.cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom16(netip.MustParseAddr("2001:db8::1").As16()), Port: 5000}, ipv6.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = u.Write([]byte("quic-like"))
	u.Close()
	// DNS to an arbitrary IPv6 resolver address is intercepted and answered.
	dc, err := gonet.DialUDP(e.cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom16(netip.MustParseAddr("2001:4860:4860::8888").As16()), Port: 53}, ipv6.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer dc.Close()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 7, RecursionDesired: true})
	_ = b.StartQuestions()
	_ = b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName("v6.test."), Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	buf := make([]byte, 512)
	var n int
	for attempt := 0; attempt < 3; attempt++ { // stub-resolver style retry (see resolve)
		_ = dc.SetDeadline(time.Now().Add(time.Second))
		_, _ = dc.Write(q)
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
	waitFor(t, func() bool { return e.Stats().UDPRejected >= 1 }, "the IPv6 UDP datagram to be counted as rejected")
}

func tcpStackStats(e *Engine) string {
	st := e.stack.Load()
	if st == nil {
		return "no stack"
	}
	ts := st.Stats().TCP
	return fmt.Sprintf("passiveOpen=%d failedAttempts=%d resetsSent=%d fwdInFlightDrop=%d listenOverflowSynDrop=%d segmentsRecv=%d invalidSegs=%d",
		ts.PassiveConnectionOpenings.Value(), ts.FailedConnectionAttempts.Value(), ts.ResetsSent.Value(),
		ts.ForwardMaxInFlightDrop.Value(), ts.ListenOverflowSynDrop.Value(), ts.ValidSegmentsReceived.Value(),
		ts.InvalidSegmentsReceived.Value())
}
