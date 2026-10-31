package pfengine

import (
	"bytes"
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"runtime"
	"runtime/pprof"
	"strconv"
	"strings"
	"sync/atomic"
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
	// Go runtime, gomobile and a VPN engine running at the same time start
	// and end goroutines of their own).
	GoroutinesBefore int `json:"goroutines_before"`
	GoroutinesAfter  int `json:"goroutines_after"`
	// GoroutinesLeft counts the goroutines this self-test started, directly
	// or through the engine, the netstack or the dialer, that are still alive
	// after it released everything (waiting up to 2 s): it must be 0. They
	// are told apart from every other goroutine by a profiler label that
	// goroutines inherit from the one that starts them.
	GoroutinesLeft int `json:"goroutines_left"`
	// GoroutinesLeftIn names the function each of those is in (its innermost
	// frame that is not just waiting); function names only.
	GoroutinesLeftIn []string `json:"goroutines_left_in,omitempty"`
}

// selfTestLabel marks, with a run number, every goroutine a self-test starts.
const selfTestLabel = "pfengine-selftest"

var selfTestRuns atomic.Int64

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
	before := runtime.NumGoroutine()
	run := strconv.FormatInt(selfTestRuns.Add(1), 10)
	var res selfTestResult
	pprof.Do(context.Background(), pprof.Labels(selfTestLabel, run), func(context.Context) {
		res = selfTest(configJSON, secret, target, payload, expect, timeoutMs, p)
	})
	res.GoroutinesBefore = before
	res.GoroutinesLeft, res.GoroutinesLeftIn = settledLabelledGoroutines(run)
	res.GoroutinesAfter = runtime.NumGoroutine()
	return toJSON(res)
}

func selfTest(configJSON string, secret []byte, target string, payload []byte, expect int64, timeoutMs int64, p Platform) selfTestResult {
	var res selfTestResult
	// Everything the self-test creates is released, newest first, before
	// SelfTest counts the goroutines left.
	var cleanups []func()
	release := func() {
		for i := len(cleanups) - 1; i >= 0; i-- {
			cleanups[i]()
		}
		cleanups = nil
	}
	defer release()
	report := func() selfTestResult {
		release()
		return res
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

// settledLabelledGoroutines waits up to 2 s for the goroutines carrying a
// self-test run's label to end, and returns how many did not and where each
// of those is.
func settledLabelledGoroutines(run string) (int, []string) {
	n, where := labelledGoroutines(run)
	for i := 0; i < 100 && n > 0; i++ {
		time.Sleep(20 * time.Millisecond)
		n, where = labelledGoroutines(run)
	}
	return n, where
}

// waitFrame reports frames that only say a goroutine is waiting (the Go
// runtime, system calls, gVisor's park helper), not which code it runs.
func waitFrame(fn string) bool {
	for _, p := range []string{"runtime.", "internal/poll.", "syscall.", "golang.org/x/sys/unix.", "gvisor.dev/gvisor/pkg/sync."} {
		if strings.HasPrefix(fn, p) {
			return true
		}
	}
	return false
}

// labelledGoroutines reads the goroutine profile and returns how many
// goroutines carry this run's label and, for each, the innermost function
// that is not just waiting. Only function names are read.
func labelledGoroutines(run string) (int, []string) {
	var b bytes.Buffer
	if err := pprof.Lookup("goroutine").WriteTo(&b, 1); err != nil {
		return 0, nil
	}
	text := b.String()
	if i := strings.IndexByte(text, '\n'); i >= 0 && strings.HasPrefix(text, "goroutine profile:") {
		text = text[i+1:]
	}
	want := fmt.Sprintf("%q:%q", selfTestLabel, run)
	n := 0
	var where []string
	// Records: "<count> @ <pcs>", then "# labels: {...}" when there are any,
	// then one "#\t<pc>\t<function>+<offset>\t<file>:<line>" line per frame.
	for _, rec := range strings.Split(text, "\n\n") {
		lines := strings.Split(rec, "\n")
		if len(lines) < 2 || !strings.HasPrefix(lines[1], "# labels: ") || !strings.Contains(lines[1], want) {
			continue
		}
		fields := strings.Fields(lines[0])
		if len(fields) == 0 {
			continue
		}
		count, err := strconv.Atoi(fields[0])
		if err != nil {
			continue
		}
		fn := "unknown"
		for _, l := range lines[2:] {
			if f := strings.Fields(l); len(f) >= 3 && !waitFrame(f[2]) {
				fn, _, _ = strings.Cut(f[2], "+")
				break
			}
		}
		n += count
		for range count {
			where = append(where, fn)
		}
	}
	return n, where
}

func minTime(a, b time.Time) time.Time {
	if a.Before(b) {
		return a
	}
	return b
}
