package pfengine

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"runtime"
	"strings"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"golang.org/x/sys/unix"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"

	"github.com/bibekthapamgr/app/engine/adapters"
	"github.com/bibekthapamgr/app/engine/tunnel"
)

const maxSelfTestReply = 4096

// selfTestResult is SelfTest's JSON report.
type selfTestResult struct {
	probeResult
	DNSAnswer       string `json:"dns_answer,omitempty"` // fake address the engine's DNS gave the target name
	Connected       bool   `json:"connected"`            // the client's TCP handshake completed through the engine
	BytesSent       int    `json:"bytes_sent"`
	BytesReceived   int    `json:"bytes_received"`
	ReplyB64        string `json:"reply_b64,omitempty"` // first bytes of the reply
	UDPRejected     bool   `json:"udp_rejected"`        // a UDP datagram to the target was refused, not forwarded
	TCPOpened       int64  `json:"tcp_opened"`
	EngineBytesUp   int64  `json:"engine_bytes_up"`
	EngineBytesDown int64  `json:"engine_bytes_down"`
	StopMs          int64  `json:"stop_ms"`
	// Goroutines in the whole process before and after (informational: the
	// Go runtime and gomobile run some of their own, e.g. finalizers that
	// release Java references).
	GoroutinesBefore int `json:"goroutines_before"`
	GoroutinesAfter  int `json:"goroutines_after"`
	// EngineGoroutinesLeft counts goroutines running engine or netstack code
	// after everything was released, beyond those alive before the test (an
	// engine may be running in the same process): it must be 0.
	EngineGoroutinesLeft int `json:"engine_goroutines_left"`
	// GoroutinesNew names, by the function that started each, goroutines
	// alive after the test that were not before (function names only).
	GoroutinesNew []string `json:"goroutines_new,omitempty"`
}

// SelfTest runs the whole packet engine without a VPN. The engine reads one
// end of a socketpair instead of a TUN; an in-process client network stack on
// the other end plays the apps. It resolves target's hostname through the
// engine's fake DNS, connects to it through the engine and the proxy, sends
// payload, reads until expect bytes arrived (at most 4 KiB; expect <= 0:
// until the reply ends or 4 KiB), checks that UDP to the target is refused, stops the engine
// and reports what happened as JSON. It proves the packet path on a device
// before the VpnService exists, and stays useful as a diagnostic. secret is
// zeroed before SelfTest returns.
func SelfTest(configJSON string, secret []byte, target string, payload []byte, expect int64, timeoutMs int64, p Platform) string {
	defer wipe(secret)
	originsBefore, engineBefore := goroutineOrigins()
	res := selfTestResult{GoroutinesBefore: runtime.NumGoroutine()}
	// Everything SelfTest creates is released, newest first, before the
	// report counts goroutines.
	var cleanups []func()
	release := func() {
		for i := len(cleanups) - 1; i >= 0; i-- {
			cleanups[i]()
		}
		cleanups = nil
	}
	defer release()
	report := func() string {
		release()
		res.EngineGoroutinesLeft = settledEngineGoroutines(engineBefore)
		res.GoroutinesAfter = runtime.NumGoroutine()
		originsAfter, _ := goroutineOrigins()
		res.GoroutinesNew = newOrigins(originsBefore, originsAfter)
		return toJSON(res)
	}
	if p == nil {
		res.probeResult = failed(errors.New("no platform callbacks"))
		return report()
	}
	cfg, err := parseConfig(configJSON)
	if err != nil {
		res.probeResult = failed(err)
		return report()
	}
	t, err := splitTarget(target)
	if err != nil {
		res.probeResult = failed(err)
		return report()
	}
	if expect > maxSelfTestReply {
		res.probeResult = failed(fmt.Errorf("expect must be at most %d bytes", maxSelfTestReply))
		return report()
	}
	timeout := defaultProbeTimeout
	if timeoutMs > 0 {
		timeout = ms(timeoutMs)
	}
	deadline := time.Now().Add(timeout)

	dialer, creds, err := newDialer(cfg, secret, p)
	if err != nil {
		res.probeResult = failed(err)
		return report()
	}
	cleanups = append(cleanups, creds.Wipe)

	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		res.probeResult = failed(fmt.Errorf("socketpair: %v", err))
		return report()
	}
	cleanups = append(cleanups, func() { unix.Close(fds[0]); unix.Close(fds[1]) })
	for _, fd := range fds {
		_ = unix.SetNonblock(fd, true)
	}
	mtu := uint32(tunnel.DefaultMTU)
	if cfg.MTU > 0 {
		mtu = uint32(cfg.MTU)
	}
	engine, err := newEngine(fds[0], cfg, dialer, p)
	if err != nil {
		res.probeResult = failed(err)
		return report()
	}
	if err := engine.Start(); err != nil {
		res.probeResult = failed(err)
		return report()
	}
	stopEngine := func() {
		if engine.State() != tunnel.StateStopped {
			t0 := time.Now()
			engine.Stop()
			res.StopMs = time.Since(t0).Milliseconds()
		}
	}
	cleanups = append(cleanups, stopEngine)

	cs, err := clientStack(fds[1], mtu)
	if err != nil {
		res.probeResult = failed(err)
		return report()
	}
	cleanups = append(cleanups, func() { cs.Close(); cs.Wait() })

	// 1. Name → fake address through the engine's DNS.
	dst := t.Addr
	if t.Host != "" {
		dst, err = queryA(cs, t.Host, deadline)
		if err != nil {
			res.probeResult = failed(err)
			return report()
		}
		res.DNSAnswer = dst.String()
	}

	// 2. TCP through the engine. The engine refuses with a RST when the proxy
	//    fails; its stats say why.
	start := time.Now()
	conn, err := connectTCP(cs, dst, t.Port, deadline)
	if err != nil {
		res.probeResult = engineFailure(engine, err)
		return report()
	}
	res.Connected = true
	res.LatencyMs = max(time.Since(start).Milliseconds(), 1)
	_ = conn.SetDeadline(deadline)
	if len(payload) > 0 {
		n, err := conn.Write(payload)
		res.BytesSent = n
		if err != nil {
			conn.Close()
			res.probeResult = failed(fmt.Errorf("write through the engine: %v", err))
			return report()
		}
	}
	reply := readReply(conn, int(expect))
	conn.Close()
	res.BytesReceived = len(reply)
	res.ReplyB64 = base64.StdEncoding.EncodeToString(reply[:min(len(reply), 256)])

	// 3. UDP to the same destination must be refused, never forwarded.
	before := engine.Stats().UDPRejected
	if u, err := gonet.DialUDP(cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(dst.AsSlice()), Port: 443}, netProto(dst)); err == nil {
		_, _ = u.Write([]byte("selftest"))
		u.Close()
		for i := 0; i < 50 && engine.Stats().UDPRejected == before; i++ {
			time.Sleep(10 * time.Millisecond)
		}
	}
	snap := engine.Stats()
	res.UDPRejected = snap.UDPRejected > before
	res.TCPOpened, res.EngineBytesUp, res.EngineBytesDown = snap.TCPOpened, snap.BytesUp, snap.BytesDown
	stopEngine()
	res.OK = expect <= 0 || int64(res.BytesReceived) >= expect
	if !res.OK {
		res.Code, res.Detail = string(adapters.CodeInternal), fmt.Sprintf("expected %d reply bytes, got %d", expect, res.BytesReceived)
	}
	return report()
}

func netProto(a netip.Addr) tcpip.NetworkProtocolNumber {
	if a.Is4() {
		return ipv4.ProtocolNumber
	}
	return ipv6.ProtocolNumber
}

// clientStack builds the "apps" side: a second netstack on the socketpair.
func clientStack(fd int, mtu uint32) (*stack.Stack, error) {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep, err := fdbased.New(&fdbased.Options{FDs: []int{fd}, MTU: mtu})
	if err != nil {
		s.Close()
		return nil, err
	}
	if terr := s.CreateNIC(1, ep); terr != nil {
		s.Close()
		return nil, fmt.Errorf("client NIC: %s", terr)
	}
	for _, a := range []netip.Addr{netip.MustParseAddr(TunAddress4), netip.MustParseAddr(TunAddress6)} {
		if terr := s.AddProtocolAddress(1, tcpip.ProtocolAddress{Protocol: netProto(a),
			AddressWithPrefix: tcpip.AddrFromSlice(a.AsSlice()).WithPrefix()}, stack.AddressProperties{}); terr != nil {
			s.Close()
			return nil, fmt.Errorf("client address: %s", terr)
		}
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}, {Destination: header.IPv6EmptySubnet, NIC: 1}})
	return s, nil
}

// queryA asks the engine's DNS for name like a stub resolver (1 s per try).
func queryA(cs *stack.Stack, name string, deadline time.Time) (netip.Addr, error) {
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 0x5054, RecursionDesired: true})
	_ = b.StartQuestions()
	qn, err := dnsmessage.NewName(name + ".")
	if err != nil {
		return netip.Addr{}, err
	}
	_ = b.Question(dnsmessage.Question{Name: qn, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET})
	q, _ := b.Finish()
	dns := netip.MustParseAddr(DNSServer4).As4()
	for attempt := 0; attempt < 3 && time.Now().Before(deadline); attempt++ {
		c, err := gonet.DialUDP(cs, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(dns), Port: 53}, ipv4.ProtocolNumber)
		if err != nil {
			return netip.Addr{}, fmt.Errorf("DNS socket: %v", err)
		}
		_ = c.SetDeadline(minTime(time.Now().Add(time.Second), deadline))
		_, _ = c.Write(q)
		buf := make([]byte, 512)
		n, err := c.Read(buf)
		c.Close()
		if err != nil {
			continue
		}
		var m dnsmessage.Message
		if err := m.Unpack(buf[:n]); err != nil {
			return netip.Addr{}, fmt.Errorf("DNS answer: %v", err)
		}
		for _, a := range m.Answers {
			if r, ok := a.Body.(*dnsmessage.AResource); ok {
				return netip.AddrFrom4(r.A), nil
			}
		}
		return netip.Addr{}, fmt.Errorf("DNS answer without an address (rcode %v)", m.RCode)
	}
	return netip.Addr{}, errors.New("the engine's DNS did not answer")
}

// connectTCP connects through the engine. One retry covers a handshake the
// in-process client stack itself gave up on (APP_HANDSHAKE_FAILED).
func connectTCP(cs *stack.Stack, dst netip.Addr, port uint16, deadline time.Time) (net.Conn, error) {
	var lastErr error
	for attempt := 0; attempt < 2 && time.Now().Before(deadline); attempt++ {
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		c, err := gonet.DialContextTCP(ctx, cs, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(dst.AsSlice()), Port: port}, netProto(dst))
		cancel()
		if err == nil {
			return c, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

// engineFailure explains a refused connection with the engine's last failure.
func engineFailure(e *tunnel.Engine, connErr error) probeResult {
	for i := 0; i < 20; i++ {
		if lf := e.Stats().LastFailure; lf != nil {
			code := adapters.Code(lf.Code)
			return probeResult{Code: lf.Code, Detail: lf.Detail, ProxyLevel: code.IsProxyLevel()}
		}
		time.Sleep(10 * time.Millisecond) // the failure is recorded just after the RST
	}
	return probeResult{Code: string(adapters.CodeInternal), Detail: fmt.Sprintf("connection through the engine failed: %v", connErr)}
}

func readReply(c net.Conn, expect int) []byte {
	limit := maxSelfTestReply
	if expect > 0 && expect < limit {
		limit = expect
	}
	buf := make([]byte, limit)
	got := 0
	for got < limit {
		n, err := c.Read(buf[got:])
		got += n
		if err != nil { // EOF, the deadline or a reset: the reply is what arrived
			break
		}
	}
	return buf[:got]
}

// settledGoroutines waits up to 2 s for the goroutine count to fall back to
// before, and returns the count it settled at.
// settledEngineGoroutines waits up to 2 s for the goroutines running engine
// or netstack code to fall back to the count before the test, and returns
// how many more are left.
func settledEngineGoroutines(before int) int {
	_, n := goroutineOrigins()
	for i := 0; i < 100 && n > before; i++ {
		time.Sleep(20 * time.Millisecond)
		_, n = goroutineOrigins()
	}
	return max(n-before, 0)
}

// enginePackages are the code a leftover goroutine of the engine would run.
var enginePackages = []string{
	"gvisor.dev/gvisor/",
	"github.com/bibekthapamgr/app/engine/adapters.",
	"github.com/bibekthapamgr/app/engine/fakedns.",
	"github.com/bibekthapamgr/app/engine/pfengine.",
	"github.com/bibekthapamgr/app/engine/tunnel.",
}

// goroutineOrigins lists, for every goroutine but the caller, the function
// that started it (the "created by" line of a stack dump), and counts those
// running engine or netstack code. Only function names are read.
func goroutineOrigins() (origins []string, engine int) {
	buf := make([]byte, 64<<10)
	for {
		n := runtime.Stack(buf, true)
		if n < len(buf) {
			buf = buf[:n]
			break
		}
		buf = make([]byte, 2*len(buf))
	}
	for i, g := range strings.Split(string(buf), "\n\n") {
		if i == 0 {
			continue // runtime.Stack puts the caller first
		}
		origin := "unknown"
		if j := strings.LastIndex(g, "\ncreated by "); j >= 0 {
			origin = strings.TrimPrefix(g[j+1:], "created by ")
			if k := strings.IndexAny(origin, " \n"); k >= 0 {
				origin = origin[:k]
			}
		}
		origins = append(origins, origin)
		for _, p := range enginePackages {
			if strings.Contains(g, p) {
				engine++
				break
			}
		}
	}
	return origins, engine
}

// newOrigins returns the origins in after that are not matched in before.
func newOrigins(before, after []string) []string {
	seen := map[string]int{}
	for _, o := range before {
		seen[o]++
	}
	var out []string
	for _, o := range after {
		if seen[o] > 0 {
			seen[o]--
			continue
		}
		out = append(out, o)
	}
	return out
}

func minTime(a, b time.Time) time.Time {
	if a.Before(b) {
		return a
	}
	return b
}
