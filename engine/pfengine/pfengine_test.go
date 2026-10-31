package pfengine

import (
	"bytes"
	"context"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	glog "gvisor.dev/gvisor/pkg/log"

	"github.com/bibekthapamgr/app/engine/adapters"
	"github.com/bibekthapamgr/app/engine/internal/proxytest"
	"github.com/bibekthapamgr/app/engine/tunnel"
)

// fakePlatform plays the Kotlin side.
type fakePlatform struct {
	roots    *x509.CertPool
	hosts    map[string]string
	verdict  string // non-empty: VerifyChain answers this instead of verifying
	refuse   bool   // Protect returns false
	protects atomic.Int32
	verifies atomic.Int32
	resolves atomic.Int32
	mu       sync.Mutex
	events   []string
}

func (p *fakePlatform) Protect(fd int64) bool {
	p.protects.Add(1)
	return !p.refuse && fd > 0
}

func (p *fakePlatform) ResolveProxyHost(host string) (string, error) {
	p.resolves.Add(1)
	if a, ok := p.hosts[host]; ok {
		return a, nil
	}
	return "", errors.New("unknown host " + host)
}

func (p *fakePlatform) VerifyChain(derChain []byte, host string) string {
	p.verifies.Add(1)
	if p.verdict != "" {
		return p.verdict
	}
	var chain []*x509.Certificate
	for len(derChain) >= 4 {
		n := int(binary.BigEndian.Uint32(derChain))
		c, err := x509.ParseCertificate(derChain[4 : 4+n])
		if err != nil {
			return "TLS_UNTRUSTED: unparsable certificate"
		}
		chain = append(chain, c)
		derChain = derChain[4+n:]
	}
	if err := (adapters.RootsVerifier{Roots: p.roots}).VerifyChain(chain, host); err != nil {
		return string(adapters.CodeOf(err))
	}
	return "OK"
}

func (p *fakePlatform) OnEvent(js string) {
	p.mu.Lock()
	p.events = append(p.events, js)
	p.mu.Unlock()
}

func (p *fakePlatform) allEvents() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]string(nil), p.events...)
}

func secretFor(user, pass string) []byte {
	b := []byte{1, byte(len(user) >> 8), byte(len(user))}
	b = append(b, user...)
	b = append(b, byte(len(pass)>>8), byte(len(pass)))
	return append(b, pass...)
}

func configFor(protocol, host string, port uint16, extra string) string {
	return fmt.Sprintf(`{"protocol":%q,"host":%q,"port":%d%s}`, protocol, host, port, extra)
}

func decode[T any](t *testing.T, js string) T {
	t.Helper()
	var v T
	if err := json.Unmarshal([]byte(js), &v); err != nil {
		t.Fatalf("bad JSON %q: %v", js, err)
	}
	return v
}

func TestProbe(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo := proxytest.NewEcho(t, "tcp4")
	for _, tc := range []struct {
		proto    proxytest.Protocol
		protocol string
	}{{proxytest.SOCKS5, "SOCKS5"}, {proxytest.HTTPConnect, "HTTP"}} {
		proxy := proxytest.NewServer(t, proxytest.Config{Protocol: tc.proto, Username: "user", Password: "pw", Hosts: map[string]string{"web.test": echo.String()}})
		p := &fakePlatform{}
		cfg := configFor(tc.protocol, proxy.Host(), proxy.Port(), "")

		secret := secretFor("user", "pw")
		r := decode[probeResult](t, Probe(cfg, secret, "web.test:443", 5000, p))
		if !r.OK || r.LatencyMs < 1 {
			t.Fatalf("%s probe: %+v", tc.protocol, r)
		}
		if !bytes.Equal(secret, make([]byte, len(secret))) {
			t.Fatalf("%s: the secret must be zeroed before Probe returns", tc.protocol)
		}
		if p.protects.Load() != 1 {
			t.Fatalf("%s: the proxy socket must be protected once, got %d", tc.protocol, p.protects.Load())
		}
		if recs := proxy.Records(); recs[len(recs)-1].Target != "web.test:443" {
			t.Fatalf("%s: proxy saw %+v", tc.protocol, recs[len(recs)-1])
		}

		r = decode[probeResult](t, Probe(cfg, secretFor("user", "wrong"), "web.test:443", 5000, p))
		if r.OK || r.Code != "AUTH_FAILED" || !r.ProxyLevel {
			t.Fatalf("%s wrong password: %+v", tc.protocol, r)
		}
		r = decode[probeResult](t, Probe(cfg, nil, "web.test:443", 5000, p))
		if r.Code != "AUTH_REQUIRED" {
			t.Fatalf("%s no sign-in: %+v", tc.protocol, r)
		}
		r = decode[probeResult](t, Probe(cfg, secretFor("user", "pw"), "nowhere.test:443", 5000, p))
		if r.OK || r.Code != "TARGET_UNREACHABLE" || r.ProxyLevel {
			t.Fatalf("%s unknown destination: %+v", tc.protocol, r)
		}
	}

	silent := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Silent: true})
	start := time.Now()
	r := decode[probeResult](t, Probe(configFor("SOCKS5", silent.Host(), silent.Port(), `,"handshake_timeout_ms":300`), nil, "web.test:80", 5000, &fakePlatform{}))
	if r.Code != "HANDSHAKE_TIMEOUT" || time.Since(start) > 2*time.Second {
		t.Fatalf("silent proxy: %+v after %v", r, time.Since(start))
	}
	refusing := &fakePlatform{refuse: true}
	r = decode[probeResult](t, Probe(configFor("SOCKS5", silent.Host(), silent.Port(), ""), nil, "web.test:80", 5000, refusing))
	if r.Code != "PROXY_UNREACHABLE" || !strings.Contains(r.Detail, "protect") {
		t.Fatalf("protect refused: %+v", r)
	}
}

func TestProbeRejectsBadInput(t *testing.T) {
	p := &fakePlatform{}
	good := configFor("SOCKS5", "127.0.0.1", 1080, "")
	for name, tc := range map[string][2]string{
		"not JSON":            {`{`, "web.test:80"},
		"unknown key":         {`{"protocol":"SOCKS5","host":"h","port":1,"password":"x"}`, "web.test:80"},
		"bad protocol":        {configFor("SOCKS4", "h", 1, ""), "web.test:80"},
		"bad port":            {`{"protocol":"SOCKS5","host":"h","port":0}`, "web.test:80"},
		"bad host":            {configFor("SOCKS5", "bad host", 1, ""), "web.test:80"},
		"target without port": {good, "web.test"},
		"target bad port":     {good, "web.test:99999"},
		"target bad name":     {good, "we b.test:80"},
		"TLS name invalid":    {configFor("SOCKS5", "h", 1, `,"tls":true,"tls_server_name":"bad name"`), "web.test:80"},
	} {
		r := decode[probeResult](t, Probe(tc[0], nil, tc[1], 1000, p))
		if r.OK || r.Code != "INTERNAL" || r.Detail == "" {
			t.Errorf("%s: %+v", name, r)
		}
	}
	if r := decode[probeResult](t, Probe(good, []byte{9, 9}, "web.test:80", 1000, p)); r.Code != "INTERNAL" {
		t.Errorf("bad credential encoding: %+v", r)
	}
	if r := decode[probeResult](t, Probe(good, nil, "web.test:80", 1000, nil)); r.Code != "INTERNAL" {
		t.Errorf("nil platform: %+v", r)
	}
}

func TestProbeTLSUsesPlatformTrust(t *testing.T) {
	ca := proxytest.NewCA(t, "Root")
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.HTTPConnect, TLS: proxytest.ServerTLS(ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}})),
		Hosts: map[string]string{"web.test": echo.String()}})
	cfg := configFor("HTTP", "proxy.test", proxy.Port(), `,"tls":true`)
	hosts := map[string]string{"proxy.test": proxy.Host()}

	trusting := &fakePlatform{roots: ca.Pool, hosts: hosts}
	if r := decode[probeResult](t, Probe(cfg, nil, "web.test:443", 5000, trusting)); !r.OK || trusting.verifies.Load() != 1 {
		t.Fatalf("trusted chain: %+v (verifier calls %d)", r, trusting.verifies.Load())
	}
	systemOnly := &fakePlatform{roots: x509.NewCertPool(), hosts: hosts}
	if r := decode[probeResult](t, Probe(cfg, nil, "web.test:443", 5000, systemOnly)); r.Code != "TLS_UNTRUSTED" {
		t.Fatalf("untrusted chain: %+v", r)
	}
	expired := &fakePlatform{verdict: "TLS_EXPIRED: certificate expired", hosts: hosts}
	if r := decode[probeResult](t, Probe(cfg, nil, "web.test:443", 5000, expired)); r.Code != "TLS_EXPIRED" || r.Detail != "certificate expired" {
		t.Fatalf("platform says expired: %+v", r)
	}
	// The hostname is checked by the engine before the platform is asked.
	wrongName := &fakePlatform{roots: ca.Pool, hosts: map[string]string{"other.test": proxy.Host()}}
	r := decode[probeResult](t, Probe(configFor("HTTP", "other.test", proxy.Port(), `,"tls":true`), nil, "web.test:443", 5000, wrongName))
	if r.Code != "TLS_HOSTNAME" || wrongName.verifies.Load() != 0 {
		t.Fatalf("hostname mismatch: %+v (verifier calls %d)", r, wrongName.verifies.Load())
	}
	// A TLS server name overrides the host for SNI and the check.
	override := &fakePlatform{roots: ca.Pool, hosts: map[string]string{"other.test": proxy.Host()}}
	r = decode[probeResult](t, Probe(configFor("HTTP", "other.test", proxy.Port(), `,"tls":true,"tls_server_name":"proxy.test"`), nil, "web.test:443", 5000, override))
	if !r.OK {
		t.Fatalf("TLS server name override: %+v", r)
	}
	// Proxy host the platform cannot resolve.
	if r := decode[probeResult](t, Probe(configFor("HTTP", "unknown.test", proxy.Port(), ""), nil, "web.test:443", 5000, &fakePlatform{})); r.Code != "PROXY_DNS_FAILED" {
		t.Fatalf("unresolvable proxy host: %+v", r)
	}
}

func socketpair(t *testing.T) (int, int) {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	return fds[0], fds[1]
}

func TestStartStopLifecycle(t *testing.T) {
	proxytest.CheckLeaks(t)
	efd, other := socketpair(t)
	defer unix.Close(efd)
	defer unix.Close(other)
	p := &fakePlatform{}
	cfg := configFor("SOCKS5", "127.0.0.1", 1080, "")

	if s := decode[map[string]any](t, Status()); s["state"] != "stopped" {
		t.Fatalf("status before start: %v", s)
	}
	secret := secretFor("user", "pw")
	if err := Start(int64(efd), cfg, secret, p); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(secret, make([]byte, len(secret))) {
		t.Fatal("the secret must be zeroed before Start returns")
	}
	if s := decode[map[string]any](t, Status()); s["state"] != "running" {
		t.Fatalf("status: %v", s)
	}
	if st := decode[map[string]any](t, Stats()); st["state"] != "running" {
		t.Fatalf("stats: %v", st)
	}
	if f := Flows(); f != "[]" {
		t.Fatalf("flows: %s", f)
	}
	if err := Start(int64(efd), cfg, nil, p); err == nil || !strings.HasPrefix(err.Error(), "INTERNAL: ") {
		t.Fatalf("second Start: %v", err)
	}
	if n := ResetFlows(); n != 0 {
		t.Fatalf("ResetFlows with no flows = %d", n)
	}
	Stop()
	Stop()
	if s := decode[map[string]any](t, Status()); s["state"] != "stopped" {
		t.Fatalf("status after stop: %v", s)
	}
	// The descriptor is still open (owned by the caller) and a new engine can start on it.
	if _, err := unix.FcntlInt(uintptr(efd), unix.F_GETFD, 0); err != nil {
		t.Fatalf("Stop must not close the TUN descriptor: %v", err)
	}
	if err := Start(int64(efd), cfg, nil, p); err != nil {
		t.Fatalf("restart: %v", err)
	}
	Stop()
	for _, bad := range []struct {
		fd  int64
		cfg string
		p   Platform
	}{{-1, cfg, p}, {int64(efd), `{"protocol":"SOCKS5"}`, p}, {int64(efd), cfg, nil}, {1 << 20, cfg, p}} {
		if err := Start(bad.fd, bad.cfg, nil, bad.p); err == nil || !strings.HasPrefix(err.Error(), "INTERNAL: ") {
			t.Errorf("Start(%d, %s) = %v, want an INTERNAL error", bad.fd, bad.cfg, err)
		}
	}
	evs := p.allEvents()
	if len(evs) < 4 || !strings.Contains(evs[0], `"type":"engine_started"`) {
		t.Fatalf("events: %v", evs)
	}
	if !strings.Contains(NetworkPlan(), `"dns_server4":"198.18.0.2"`) || !strings.Contains(Version(), "gvisor") {
		t.Fatalf("plan %s, version %s", NetworkPlan(), Version())
	}
}

func TestSelfTestThroughMockProxy(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Username: "user", Password: "pw",
		Hosts: map[string]string{"web.test": echo.String(), "2001:db8::7": echo.String()}})
	cfg := configFor("SOCKS5", proxy.Host(), proxy.Port(), "")
	payload := []byte("hello through the whole engine")

	p := &fakePlatform{}
	r := decode[selfTestResult](t, SelfTest(cfg, secretFor("user", "pw"), "web.test:443", payload, int64(len(payload)), 10000, p))
	reply, _ := base64.StdEncoding.DecodeString(r.ReplyB64)
	if !r.OK || !r.Connected || r.BytesReceived != len(payload) || string(reply) != string(payload) {
		t.Fatalf("self-test: %+v", r)
	}
	if !strings.HasPrefix(r.DNSAnswer, "198.1") || !r.UDPRejected || r.TCPOpened != 1 || r.EngineBytesUp != int64(len(payload)) {
		t.Fatalf("self-test details: %+v", r)
	}
	if r.EngineGoroutinesLeft != 0 || r.GoroutinesAfter > r.GoroutinesBefore {
		t.Fatalf("goroutines %d → %d, %d engine ones left, new: %v", r.GoroutinesBefore, r.GoroutinesAfter, r.EngineGoroutinesLeft, r.GoroutinesNew)
	}
	if recs := proxy.Records(); recs[len(recs)-1].Target != "web.test:443" || recs[len(recs)-1].AddrType != "domain" {
		t.Fatalf("the proxy must receive the hostname: %+v", recs[len(recs)-1])
	}

	r = decode[selfTestResult](t, SelfTest(cfg, secretFor("user", "pw"), "[2001:db8::7]:443", payload, int64(len(payload)), 10000, p))
	if !r.OK || r.DNSAnswer != "" {
		t.Fatalf("IPv6 destination: %+v", r)
	}
	if recs := proxy.Records(); recs[len(recs)-1].AddrType != "ipv6" {
		t.Fatalf("IPv6 destination must reach the proxy as an IPv6 address: %+v", recs[len(recs)-1])
	}

	r = decode[selfTestResult](t, SelfTest(cfg, secretFor("user", "wrong"), "web.test:443", payload, int64(len(payload)), 10000, p))
	if r.OK || r.Connected || r.Code != "AUTH_FAILED" || !r.ProxyLevel {
		t.Fatalf("wrong password: %+v", r)
	}
}

// TestNoSecretsAnywhere drives every entry point with distinctive credentials
// and searches everything that leaves the engine: returned JSON, errors,
// events, status, stats and flows.
func TestNoSecretsAnywhere(t *testing.T) {
	const user, pass = "Qz8user", "Wv5secret"
	needles := []string{user, pass, base64.StdEncoding.EncodeToString([]byte(user + ":" + pass)),
		fmt.Sprintf("%x", user), fmt.Sprintf("%x", pass), base64.StdEncoding.EncodeToString(secretFor(user, pass))}
	echo := proxytest.NewEcho(t, "tcp4")
	hosts := map[string]string{"web.test": echo.String()}
	p := &fakePlatform{}
	var outputs []string

	for _, proto := range []proxytest.Protocol{proxytest.SOCKS5, proxytest.HTTPConnect} {
		name := map[proxytest.Protocol]string{proxytest.SOCKS5: "SOCKS5", proxytest.HTTPConnect: "HTTP"}[proto]
		ok := proxytest.NewServer(t, proxytest.Config{Protocol: proto, Username: user, Password: pass, Hosts: hosts})
		bad := proxytest.NewServer(t, proxytest.Config{Protocol: proto, Username: "x", Password: "y", Hosts: hosts})
		for _, s := range []*proxytest.Server{ok, bad} {
			cfg := configFor(name, s.Host(), s.Port(), `,"log_destinations":true`)
			outputs = append(outputs,
				Probe(cfg, secretFor(user, pass), "web.test:443", 5000, p),
				SelfTest(cfg, secretFor(user, pass), "web.test:443", []byte("x"), 1, 5000, p))
		}
		efd, other := socketpair(t)
		if err := Start(int64(efd), configFor(name, ok.Host(), ok.Port(), ""), secretFor(user, pass), p); err != nil {
			t.Fatal(err)
		}
		outputs = append(outputs, Status(), Stats(), Flows())
		if err := Start(int64(efd), configFor(name, ok.Host(), ok.Port(), ""), secretFor(user, pass), p); err != nil {
			outputs = append(outputs, err.Error())
		}
		Stop()
		unix.Close(efd)
		unix.Close(other)
	}
	if err := Start(3, `{"protocol":"SOCKS5","host":"h","port":1}`, secretFor(strings.Repeat(user, 40), pass), p); err != nil {
		outputs = append(outputs, err.Error()) // over-long username: the error must not echo it
	}
	outputs = append(outputs, p.allEvents()...)
	if len(p.allEvents()) < 10 {
		t.Fatalf("expected events from the runs, got %d", len(p.allEvents()))
	}
	for _, out := range outputs {
		for _, n := range needles {
			if strings.Contains(out, n) {
				t.Fatalf("a credential (%q) appears in engine output: %s", n, out)
			}
		}
	}
}

func TestChainEncodingAndVerdicts(t *testing.T) {
	enc := encodeChain([][]byte{{1, 2, 3}, {4}})
	if !bytes.Equal(enc, []byte{0, 0, 0, 3, 1, 2, 3, 0, 0, 0, 1, 4}) {
		t.Fatalf("encoding %v", enc)
	}
	cases := map[string]adapters.Code{
		"OK": "",
		// Only the exact token means trusted: a Java null arrives as "".
		"":                       adapters.CodeTLSUntrusted,
		"ok":                     adapters.CodeTLSUntrusted,
		" OK":                    adapters.CodeTLSUntrusted,
		"OK: but not really":     adapters.CodeTLSUntrusted,
		"TLS_EXPIRED":            adapters.CodeTLSExpired,
		"TLS_EXPIRED: not after": adapters.CodeTLSExpired,
		"TLS_HOSTNAME":           adapters.CodeTLSHostname,
		"TLS_UNTRUSTED":          adapters.CodeTLSUntrusted,
		"something odd":          adapters.CodeTLSUntrusted,
	}
	for verdict, want := range cases {
		err := parseVerdict(verdict)
		if (want == "" && err != nil) || (want != "" && adapters.CodeOf(err) != want) {
			t.Errorf("verdict %q → %v, want %q", verdict, err, want)
		}
	}
}

func TestStatusRemembersWhyStartFailed(t *testing.T) {
	proxytest.CheckLeaks(t)
	efd, other := socketpair(t)
	defer unix.Close(efd)
	defer unix.Close(other)
	p := &fakePlatform{}
	bad := configFor("SOCKS4", "127.0.0.1", 1080, "")
	failStart := func() string {
		t.Helper()
		err := Start(int64(efd), bad, nil, p)
		if err == nil || !strings.HasPrefix(err.Error(), "INTERNAL: ") {
			t.Fatalf("Start with a bad protocol: %v", err)
		}
		return strings.TrimPrefix(err.Error(), "INTERNAL: ")
	}
	detail := failStart()
	s := decode[tunnel.Status](t, Status())
	if s.State != "stopped" || s.LastError == nil || s.LastError.Code != "INTERNAL" || s.LastError.Detail != detail || s.LastError.Time.IsZero() {
		t.Fatalf("status after a failed Start: %+v", s)
	}
	Stop()
	if s := decode[tunnel.Status](t, Status()); s.LastError != nil {
		t.Fatalf("Stop must clear the start error: %+v", s)
	}
	failStart()
	if err := Start(int64(efd), configFor("SOCKS5", "127.0.0.1", 1080, ""), nil, p); err != nil {
		t.Fatal(err)
	}
	if s := decode[tunnel.Status](t, Status()); s.State != "running" || s.LastError != nil {
		t.Fatalf("a successful Start must clear the start error: %+v", s)
	}
	Stop()
	if s := decode[tunnel.Status](t, Status()); s.State != "stopped" || s.LastError != nil {
		t.Fatalf("status after a clean stop: %+v", s)
	}
}

func TestResetFlowsForgetsProxyAddresses(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Hosts: map[string]string{"web.test": echo.String()}})
	efd, other := socketpair(t)
	defer unix.Close(efd)
	defer unix.Close(other)
	p := &fakePlatform{hosts: map[string]string{"proxy.test": proxy.Host()}}
	if err := Start(int64(efd), configFor("SOCKS5", "proxy.test", proxy.Port(), ""), nil, p); err != nil {
		t.Fatal(err)
	}
	defer Stop()
	dial := func() {
		t.Helper()
		c, err := getCurrent().dialer.Dial(context.Background(), adapters.Target{Host: "web.test", Port: 443})
		if err != nil {
			t.Fatalf("dial through the running engine's dialer: %v", err)
		}
		c.Close()
	}
	dial()
	dial()
	if n := p.resolves.Load(); n != 1 {
		t.Fatalf("two dials asked the platform %d times, want 1 (cached)", n)
	}
	ResetFlows()
	dial()
	if n := p.resolves.Load(); n != 2 {
		t.Fatalf("after ResetFlows the proxy host must be resolved again (%d lookups)", n)
	}
}

// The self-test's leak check sees a goroutine running engine code, names who
// started it, and ignores goroutines that were there before.
func TestGoroutineAccountingSeesEngineGoroutines(t *testing.T) {
	originsBefore, before := goroutineOrigins()
	stop, done := make(chan struct{}), make(chan struct{})
	go func() {
		<-stop
		close(done)
	}()
	originsAfter, after := goroutineOrigins()
	if after != before+1 {
		t.Fatalf("engine goroutines %d → %d, want one more", before, after)
	}
	if n := newOrigins(originsBefore, originsAfter); len(n) != 1 || !strings.HasSuffix(n[0], "pfengine.TestGoroutineAccountingSeesEngineGoroutines") {
		t.Fatalf("new goroutines %v", n)
	}
	if left := settledEngineGoroutines(before - 1); left != 2 {
		t.Fatalf("with one engine goroutine too many before and one now, %d left, want 2", left)
	}
	close(stop)
	<-done
	if left := settledEngineGoroutines(before); left != 0 {
		t.Fatalf("%d engine goroutines left after it ended", left)
	}
}

func TestSelfTestRejectsBadInput(t *testing.T) {
	p := &fakePlatform{}
	cfg := configFor("SOCKS5", "127.0.0.1", 1080, "")
	r := decode[selfTestResult](t, SelfTest(cfg, nil, "web.test:443", nil, maxSelfTestReply+1, 1000, p))
	if r.OK || r.Code != "INTERNAL" || !strings.Contains(r.Detail, "4096") {
		t.Fatalf("expect over the reply limit: %+v", r)
	}
	if r := decode[selfTestResult](t, SelfTest(cfg, nil, "web.test", nil, 1, 1000, p)); r.Code != "INTERNAL" {
		t.Fatalf("target without port: %+v", r)
	}
}

// The netstack's own log lines (addresses, ports) must not reach logcat.
func TestNetstackLoggingIsDiscarded(t *testing.T) {
	if _, ok := glog.Log().Emitter.(discardLog); !ok {
		t.Fatalf("gVisor logs to %T, want discardLog", glog.Log().Emitter)
	}
	if glog.IsLogging(glog.Info) {
		t.Fatalf("gVisor must not even format Info messages")
	}
}

func TestResolverHonoursContext(t *testing.T) {
	block := make(chan struct{})
	slow := &slowPlatform{release: block}
	r := resolver(slow)
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	start := time.Now()
	if _, err := r(ctx, "proxy.test"); err == nil || time.Since(start) > time.Second {
		t.Fatalf("a slow platform resolver must not hold the dial past its deadline: %v after %v", err, time.Since(start))
	}
	close(block)
	ok := resolver(&fakePlatform{hosts: map[string]string{"p.test": "192.0.2.1, 2001:db8::1, junk"}})
	addrs, err := ok(context.Background(), "p.test")
	if err != nil || len(addrs) != 2 {
		t.Fatalf("addresses %v %v", addrs, err)
	}
}

type slowPlatform struct {
	fakePlatform
	release chan struct{}
}

func (s *slowPlatform) ResolveProxyHost(string) (string, error) {
	<-s.release
	return "192.0.2.1", nil
}
