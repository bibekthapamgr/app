package adapters

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"github.com/bibekthapamgr/app/engine/internal/proxytest"
)

const testTimeout = 2 * time.Second

func dialerTo(t *testing.T, s *proxytest.Server, kind Kind, c *Credentials, tlsOpt *TLSOptions) *Dialer {
	t.Helper()
	d, err := NewDialer(ProxyConfig{Kind: kind, Host: s.Host(), Port: s.Port(), TLS: tlsOpt,
		DialTimeout: testTimeout, HandshakeTimeout: testTimeout}, c, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

// echoRoundTrip proves bytes really flow through the tunnel to the echo server and back.
func echoRoundTrip(t *testing.T, c net.Conn) {
	t.Helper()
	msg := []byte(fmt.Sprintf("ping through the tunnel %d", time.Now().UnixNano()))
	_ = c.SetDeadline(time.Now().Add(testTimeout))
	if _, err := c.Write(msg); err != nil {
		t.Fatalf("write: %v", err)
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != string(msg) {
		t.Fatalf("echo mismatch: %q", got)
	}
}

func hasIPv6Loopback() bool {
	ln, err := net.Listen("tcp6", "[::1]:0")
	if err != nil {
		return false
	}
	ln.Close()
	return true
}

func TestDialThroughProxiesToEcho(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo4 := proxytest.NewEcho(t, "tcp4")
	// 2001:db8::7 is a documentation address: the proxy maps it to the echo
	// server, so the IPv6 destination path runs even without IPv6 here.
	hosts := map[string]string{"echo.test": echo4.String(), "2001:db8::7": echo4.String()}

	type tcase struct {
		name     string
		target   Target
		addrType string
	}
	cases := []tcase{
		{"domain", Target{Host: "echo.test", Port: 7}, "domain"},
		{"ipv4", Target{Addr: echo4.Addr.Addr(), Port: echo4.Addr.Port()}, "ipv4"},
		{"ipv6 via proxy mapping", Target{Addr: netip.MustParseAddr("2001:db8::7"), Port: 7}, "ipv6"},
		{"ipv4-mapped ipv6 sent as ipv4", Target{Addr: netip.AddrFrom16(echo4.Addr.Addr().As16()), Port: echo4.Addr.Port()}, "ipv4"},
	}
	if hasIPv6Loopback() {
		echo6 := proxytest.NewEcho(t, "tcp6")
		cases = append(cases, tcase{"ipv6 loopback", Target{Addr: echo6.Addr.Addr(), Port: echo6.Addr.Port()}, "ipv6"})
	} else {
		t.Log("no IPv6 loopback here: the [::1] destination case is skipped (the mapped IPv6 case still runs)")
	}

	for _, kind := range []Kind{KindSOCKS5, KindHTTPConnect} {
		for _, signIn := range []bool{false, true} {
			cfg := proxytest.Config{Protocol: proxytest.SOCKS5, Hosts: hosts}
			if kind == KindHTTPConnect {
				cfg.Protocol = proxytest.HTTPConnect
			}
			var c *Credentials
			if signIn {
				cfg.Username, cfg.Password = "ålice", "p@ss:wörd"
				c = creds("ålice", "p@ss:wörd")
			}
			proxy := proxytest.NewServer(t, cfg)
			d := dialerTo(t, proxy, kind, c, nil)
			for _, tc := range cases {
				t.Run(fmt.Sprintf("%s/signin=%v/%s", kind, signIn, tc.name), func(t *testing.T) {
					conn, err := d.Dial(context.Background(), tc.target)
					if err != nil {
						t.Fatalf("dial: %v", err)
					}
					defer conn.Close()
					echoRoundTrip(t, conn)
					recs := proxy.Records()
					last := recs[len(recs)-1]
					if kind == KindSOCKS5 && last.AddrType != tc.addrType {
						t.Fatalf("SOCKS5 address type %q, want %q", last.AddrType, tc.addrType)
					}
					want := tc.target.HostPort()
					if last.Target != want {
						t.Fatalf("proxy saw target %q, want %q", last.Target, want)
					}
					if signIn && (last.Username != "ålice" || last.Password != "p@ss:wörd") {
						t.Fatalf("proxy received %q/%q", last.Username, last.Password)
					}
					if !signIn && (last.Username != "" || last.AuthHeader != "") {
						t.Fatalf("no sign-in configured, but the proxy received credentials")
					}
				})
			}
		}
	}
	if echo4.Served() == 0 {
		t.Fatal("echo server saw no connection")
	}
}

func TestDomainTargetIsResolvedByTheProxy(t *testing.T) {
	// "only-the-proxy-knows.test" does not exist anywhere except in the
	// proxy's host map: success proves the hostname was sent, not resolved locally.
	echo := proxytest.NewEcho(t, "tcp4")
	for _, p := range []proxytest.Protocol{proxytest.SOCKS5, proxytest.HTTPConnect} {
		proxy := proxytest.NewServer(t, proxytest.Config{Protocol: p, Hosts: map[string]string{"only-the-proxy-knows.test": echo.String()}})
		kind := KindSOCKS5
		if p == proxytest.HTTPConnect {
			kind = KindHTTPConnect
		}
		conn, err := dialerTo(t, proxy, kind, nil, nil).Dial(context.Background(), Target{Host: "only-the-proxy-knows.test", Port: 443})
		if err != nil {
			t.Fatalf("%s: %v", kind, err)
		}
		echoRoundTrip(t, conn)
		conn.Close()
	}
}

func TestAuthenticationOutcomes(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo := proxytest.NewEcho(t, "tcp4")
	hosts := map[string]string{"echo.test": echo.String()}
	target := Target{Host: "echo.test", Port: 80}
	for _, p := range []proxytest.Protocol{proxytest.SOCKS5, proxytest.HTTPConnect} {
		kind := KindSOCKS5
		if p == proxytest.HTTPConnect {
			kind = KindHTTPConnect
		}
		proxy := proxytest.NewServer(t, proxytest.Config{Protocol: p, Hosts: hosts, Username: "user", Password: "right"})
		t.Run(kind.String()+"/required but none configured", func(t *testing.T) {
			_, err := dialerTo(t, proxy, kind, nil, nil).Dial(context.Background(), target)
			if CodeOf(err) != CodeAuthRequired {
				t.Fatalf("got %v, want AUTH_REQUIRED", err)
			}
		})
		t.Run(kind.String()+"/wrong password", func(t *testing.T) {
			_, err := dialerTo(t, proxy, kind, creds("user", "wrong"), nil).Dial(context.Background(), target)
			if CodeOf(err) != CodeAuthFailed {
				t.Fatalf("got %v, want AUTH_FAILED", err)
			}
		})
		t.Run(kind.String()+"/wrong username", func(t *testing.T) {
			_, err := dialerTo(t, proxy, kind, creds("someone", "right"), nil).Dial(context.Background(), target)
			if CodeOf(err) != CodeAuthFailed {
				t.Fatalf("got %v, want AUTH_FAILED", err)
			}
		})
		t.Run(kind.String()+"/correct", func(t *testing.T) {
			conn, err := dialerTo(t, proxy, kind, creds("user", "right"), nil).Dial(context.Background(), target)
			if err != nil {
				t.Fatal(err)
			}
			echoRoundTrip(t, conn)
			conn.Close()
		})
	}
	digest := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.HTTPConnect, Hosts: hosts, Username: "u", Password: "p", AuthScheme: "Digest"})
	_, err := dialerTo(t, digest, KindHTTPConnect, creds("u", "x"), nil).Dial(context.Background(), target)
	if CodeOf(err) != CodeAuthFailed || !strings.Contains(err.Error(), "digest") {
		t.Fatalf("Digest-only proxy: got %v, want AUTH_FAILED naming Digest", err)
	}
}

func TestTargetLevelFailures(t *testing.T) {
	closed, _ := net.Listen("tcp4", "127.0.0.1:0")
	closedAddr := closed.Addr().String()
	closed.Close()
	hosts := map[string]string{"refused.test": closedAddr}
	cases := []struct {
		proto  proxytest.Protocol
		cfg    func(*proxytest.Config)
		target Target
		want   Code
	}{
		{proxytest.SOCKS5, nil, Target{Host: "unknown.test", Port: 80}, CodeTargetUnreachable},
		{proxytest.SOCKS5, nil, Target{Host: "refused.test", Port: 80}, CodeTargetRefused},
		{proxytest.SOCKS5, func(c *proxytest.Config) { c.SOCKSReply = 0x02 }, Target{Host: "a.test", Port: 80}, CodeTargetNotAllowed},
		{proxytest.SOCKS5, func(c *proxytest.Config) { c.SOCKSReply = 0x08 }, Target{Host: "a.test", Port: 80}, CodeTargetUnsupported},
		{proxytest.SOCKS5, func(c *proxytest.Config) { c.SOCKSReply = 0x01 }, Target{Host: "a.test", Port: 80}, CodeProxyGeneralFailed},
		{proxytest.HTTPConnect, nil, Target{Host: "unknown.test", Port: 80}, CodeTargetUnreachable},
		{proxytest.HTTPConnect, func(c *proxytest.Config) { c.HTTPStatus = 403 }, Target{Host: "a.test", Port: 80}, CodeTargetNotAllowed},
		{proxytest.HTTPConnect, func(c *proxytest.Config) { c.HTTPStatus = 504 }, Target{Host: "a.test", Port: 80}, CodeTargetUnreachable},
		{proxytest.HTTPConnect, func(c *proxytest.Config) { c.HTTPStatus = 500 }, Target{Host: "a.test", Port: 80}, CodeProxyGeneralFailed},
		{proxytest.HTTPConnect, func(c *proxytest.Config) { c.HTTPStatus = 405 }, Target{Host: "a.test", Port: 80}, CodeProtocol},
	}
	for i, tc := range cases {
		cfg := proxytest.Config{Protocol: tc.proto, Hosts: hosts}
		if tc.cfg != nil {
			tc.cfg(&cfg)
		}
		kind := KindSOCKS5
		if tc.proto == proxytest.HTTPConnect {
			kind = KindHTTPConnect
		}
		proxy := proxytest.NewServer(t, cfg)
		_, err := dialerTo(t, proxy, kind, nil, nil).Dial(context.Background(), tc.target)
		if CodeOf(err) != tc.want {
			t.Errorf("case %d (%s): got %v, want %s", i, kind, err, tc.want)
		}
	}
}

func TestBufferedTunnelBytesAfterConnectReply(t *testing.T) {
	echo := proxytest.NewEcho(t, "tcp4")
	for _, p := range []proxytest.Protocol{proxytest.SOCKS5, proxytest.HTTPConnect} {
		kind := KindSOCKS5
		if p == proxytest.HTTPConnect {
			kind = KindHTTPConnect
		}
		proxy := proxytest.NewServer(t, proxytest.Config{Protocol: p, Hosts: map[string]string{"e.test": echo.String()}, TunnelPreface: []byte("EARLY")})
		conn, err := dialerTo(t, proxy, kind, nil, nil).Dial(context.Background(), Target{Host: "e.test", Port: 1})
		if err != nil {
			t.Fatalf("%s: %v", kind, err)
		}
		got := make([]byte, 5)
		_ = conn.SetReadDeadline(time.Now().Add(testTimeout))
		if _, err := io.ReadFull(conn, got); err != nil || string(got) != "EARLY" {
			t.Fatalf("%s: bytes sent right after the reply must reach the app, got %q %v", kind, got, err)
		}
		echoRoundTrip(t, conn)
		conn.Close()
	}
}

func TestProxyUnreachable(t *testing.T) {
	ln, _ := net.Listen("tcp4", "127.0.0.1:0")
	addr := ln.Addr().(*net.TCPAddr).AddrPort()
	ln.Close()
	d, err := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: addr.Addr().String(), Port: addr.Port(), DialTimeout: testTimeout}, nil, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	_, err = d.Dial(context.Background(), Target{Host: "a.test", Port: 80})
	if CodeOf(err) != CodeProxyUnreachable || !CodeOf(err).IsProxyLevel() {
		t.Fatalf("closed port: got %v, want PROXY_UNREACHABLE", err)
	}
}

func TestHandshakeTimeout(t *testing.T) {
	proxytest.CheckLeaks(t)
	for _, p := range []proxytest.Protocol{proxytest.SOCKS5, proxytest.HTTPConnect} {
		kind := KindSOCKS5
		if p == proxytest.HTTPConnect {
			kind = KindHTTPConnect
		}
		silent := proxytest.NewServer(t, proxytest.Config{Protocol: p, Silent: true})
		d, err := NewDialer(ProxyConfig{Kind: kind, Host: silent.Host(), Port: silent.Port(), HandshakeTimeout: 300 * time.Millisecond}, nil, nil, nil)
		if err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		_, err = d.Dial(context.Background(), Target{Host: "a.test", Port: 80})
		if CodeOf(err) != CodeTimeout {
			t.Fatalf("%s silent proxy: got %v, want HANDSHAKE_TIMEOUT", kind, err)
		}
		if el := time.Since(start); el < 250*time.Millisecond || el > 2*time.Second {
			t.Fatalf("%s: handshake timeout not enforced (%v)", kind, el)
		}
		waitFor(t, func() bool { return silent.Active() == 0 }, "the timed-out proxy connection to be closed")
	}
}

// The app's diagnostics count proxy timeouts (IsTimeout): connecting to the proxy or
// its handshake took too long. Other failures, and a slow lookup of the proxy's own
// name (a DNS failure), are not timeouts.
func TestTimeoutsAreTold(t *testing.T) {
	expired, cancel := context.WithDeadline(context.Background(), time.Now().Add(-time.Second))
	defer cancel()
	canceled, cancel2 := context.WithCancel(context.Background())
	cancel2()
	timeouts := map[string]error{
		"connect deadline":         connectCtxErr(expired),
		"connect timeout error":    classifyConnectError(os.ErrDeadlineExceeded),
		"connect without an error": classifyConnectError(nil),
		"handshake deadline":       handshakeCtxErr(expired),
		"handshake timeout error":  classifyHandshakeError(context.Background(), os.ErrDeadlineExceeded),
		"TLS handshake timeout":    classifyTLSError(os.ErrDeadlineExceeded),
	}
	for name, err := range timeouts {
		if !IsTimeout(err) || !CodeOf(err).IsProxyLevel() {
			t.Errorf("%s: %v is not told as a proxy timeout", name, err)
		}
	}
	others := map[string]error{
		"refused":           classifyConnectError(syscall.ECONNREFUSED),
		"no route":          classifyConnectError(syscall.ENETUNREACH),
		"canceled connect":  connectCtxErr(canceled),
		"proxy name lookup": newErr(CodeProxyDNS, "looking up the proxy host timed out", context.DeadlineExceeded),
		"sign-in":           newErr(CodeAuthFailed, "rejected", nil),
		"plain error":       errors.New("timeout"),
		"nil":               nil,
	}
	for name, err := range others {
		if IsTimeout(err) {
			t.Errorf("%s: %v is told as a proxy timeout", name, err)
		}
	}
}

func TestContextDeadlineDuringHandshake(t *testing.T) {
	silent := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Silent: true})
	d := dialerTo(t, silent, KindSOCKS5, nil, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	start := time.Now()
	_, err := d.Dial(ctx, Target{Host: "a.test", Port: 80})
	if CodeOf(err) != CodeTimeout || time.Since(start) > time.Second {
		t.Fatalf("got %v after %v, want HANDSHAKE_TIMEOUT at the context deadline", err, time.Since(start))
	}
}

func TestCancellationStopsDialAtOnce(t *testing.T) {
	proxytest.CheckLeaks(t)
	ca := proxytest.NewCA(t, "Root")
	cert := ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}})
	cases := []struct {
		name      string
		cfg       proxytest.Config
		clientTLS bool
	}{
		{"silent SOCKS5", proxytest.Config{Protocol: proxytest.SOCKS5, Silent: true}, false},
		{"silent HTTP", proxytest.Config{Protocol: proxytest.HTTPConnect, Silent: true}, false},
		{"during the TLS handshake", proxytest.Config{Protocol: proxytest.SOCKS5, Silent: true}, true},
		// The proxy sleeps through this one, so only CheckLeaks (client side) applies.
		{"slow reply after TLS", proxytest.Config{Protocol: proxytest.SOCKS5, TLS: proxytest.ServerTLS(cert), ReplyDelay: time.Hour}, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := tc.cfg
			proxy := proxytest.NewServer(t, cfg)
			kind := KindSOCKS5
			if cfg.Protocol == proxytest.HTTPConnect {
				kind = KindHTTPConnect
			}
			var tlsOpt *TLSOptions
			if tc.clientTLS {
				tlsOpt = &TLSOptions{ServerName: "proxy.test", Verifier: RootsVerifier{Roots: ca.Pool}}
			}
			d, err := NewDialer(ProxyConfig{Kind: kind, Host: proxy.Host(), Port: proxy.Port(), TLS: tlsOpt, HandshakeTimeout: time.Hour}, nil, nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			time.AfterFunc(150*time.Millisecond, cancel)
			start := time.Now()
			_, err = d.Dial(ctx, Target{Host: "a.test", Port: 80})
			if CodeOf(err) != CodeCanceled {
				t.Fatalf("got %v, want CANCELED", err)
			}
			if el := time.Since(start); el > time.Second {
				t.Fatalf("cancellation took %v", el)
			}
			if CodeCanceled.IsProxyLevel() {
				t.Fatal("CANCELED must never count as a proxy failure")
			}
			if cfg.ReplyDelay == 0 {
				waitFor(t, func() bool { return proxy.Active() == 0 }, "the canceled proxy connection to be closed")
			}
		})
	}
	// Already-canceled context: nothing is dialled at all.
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := dialerTo(t, proxy, KindSOCKS5, nil, nil).Dial(ctx, Target{Host: "a.test", Port: 80}); CodeOf(err) != CodeCanceled {
		t.Fatalf("got %v, want CANCELED", err)
	}
	time.Sleep(50 * time.Millisecond)
	if proxy.Accepts() != 0 {
		t.Fatal("a canceled dial must not connect to the proxy")
	}
}

func TestWipedCredentialsCancelDials(t *testing.T) {
	echo := proxytest.NewEcho(t, "tcp4")
	for _, p := range []proxytest.Protocol{proxytest.SOCKS5, proxytest.HTTPConnect} {
		kind := KindSOCKS5
		if p == proxytest.HTTPConnect {
			kind = KindHTTPConnect
		}
		proxy := proxytest.NewServer(t, proxytest.Config{Protocol: p, Hosts: map[string]string{"e.test": echo.String()}, Username: "u", Password: "p"})
		c := creds("u", "p")
		d := dialerTo(t, proxy, kind, c, nil)
		c.Wipe() // the engine stopped
		_, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
		if CodeOf(err) != CodeAuthRequired && CodeOf(err) != CodeCanceled {
			t.Fatalf("%s: got %v, want no credentials to be sent", kind, err)
		}
		for _, r := range proxy.Records() {
			if r.Username != "" || r.AuthHeader != "" {
				t.Fatalf("%s: wiped credentials reached the proxy", kind)
			}
		}
	}
}

func TestProxyHostResolution(t *testing.T) {
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Hosts: map[string]string{"e.test": echo.String()}})

	t.Run("failure", func(t *testing.T) {
		failing := func(context.Context, string) ([]netip.Addr, error) { return nil, errors.New("no such host") }
		d, err := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: "proxy.example.invalid", Port: 1080}, nil, nil, failing)
		if err != nil {
			t.Fatal(err)
		}
		_, err = d.Dial(context.Background(), Target{Host: "a.test", Port: 80})
		if CodeOf(err) != CodeProxyDNS {
			t.Fatalf("got %v, want PROXY_DNS_FAILED", err)
		}
	})
	t.Run("no usable address", func(t *testing.T) {
		unusable := func(context.Context, string) ([]netip.Addr, error) {
			return []netip.Addr{netip.IPv4Unspecified(), netip.MustParseAddr("ff02::1")}, nil
		}
		d, _ := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: "proxy.test", Port: 1080}, nil, nil, unusable)
		if _, err := d.Dial(context.Background(), Target{Host: "a.test", Port: 80}); CodeOf(err) != CodeProxyDNS {
			t.Fatalf("got %v, want PROXY_DNS_FAILED", err)
		}
	})
	t.Run("normalised name, fallback to the next address", func(t *testing.T) {
		// 127.0.0.2 is loopback too, but nothing listens there: refused at once.
		var asked string
		resolve := func(_ context.Context, host string) ([]netip.Addr, error) {
			asked = host
			return []netip.Addr{netip.MustParseAddr("::ffff:127.0.0.2"), proxy.Addr.Addr()}, nil
		}
		d, err := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: "Proxy.Example.", Port: proxy.Port(), DialTimeout: testTimeout}, nil, nil, resolve)
		if err != nil {
			t.Fatal(err)
		}
		conn, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
		if err != nil {
			t.Fatalf("second address must be tried: %v", err)
		}
		echoRoundTrip(t, conn)
		conn.Close()
		if asked != "proxy.example" {
			t.Fatalf("resolver asked for %q, want the normalised name", asked)
		}
	})
}

func TestProxyAddressesAreCachedAndShared(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Hosts: map[string]string{"e.test": echo.String()}})
	target := Target{Host: "e.test", Port: 1}
	newDialer := func(t *testing.T, resolve Resolver) (*Dialer, *time.Time) {
		t.Helper()
		d, err := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: "proxy.test", Port: proxy.Port(), DialTimeout: testTimeout}, nil, nil, resolve)
		if err != nil {
			t.Fatal(err)
		}
		now := time.Unix(1_000_000, 0)
		d.now = func() time.Time { return now }
		return d, &now
	}
	dialOK := func(t *testing.T, d *Dialer) {
		t.Helper()
		c, err := d.Dial(context.Background(), target)
		if err != nil {
			t.Fatalf("dial: %v", err)
		}
		echoRoundTrip(t, c)
		c.Close()
	}
	waitCalls := func(t *testing.T, calls *atomic.Int32, n int32) {
		t.Helper()
		for deadline := time.Now().Add(testTimeout); calls.Load() < n; time.Sleep(time.Millisecond) {
			if time.Now().After(deadline) {
				t.Fatalf("resolver called %d times, want %d", calls.Load(), n)
			}
		}
	}

	t.Run("concurrent dials share one lookup, then the cache answers", func(t *testing.T) {
		var calls atomic.Int32
		release := make(chan struct{})
		d, now := newDialer(t, func(ctx context.Context, _ string) ([]netip.Addr, error) {
			calls.Add(1)
			<-release
			return []netip.Addr{proxy.Addr.Addr()}, nil
		})
		errs := make(chan error, 8)
		for range 8 {
			go func() {
				c, err := d.Dial(context.Background(), target)
				if err == nil {
					c.Close()
				}
				errs <- err
			}()
		}
		waitCalls(t, &calls, 1)
		time.Sleep(50 * time.Millisecond) // let the other dials reach the lookup
		close(release)
		for range 8 {
			if err := <-errs; err != nil {
				t.Fatalf("dial: %v", err)
			}
		}
		dialOK(t, d)
		if n := calls.Load(); n != 1 {
			t.Fatalf("nine dials made %d lookups, want 1", n)
		}
		*now = now.Add(proxyAddrTTL)
		dialOK(t, d)
		if n := calls.Load(); n != 2 {
			t.Fatalf("an expired entry must be looked up again (%d lookups)", n)
		}
		d.ForgetProxyAddrs()
		dialOK(t, d)
		if n := calls.Load(); n != 3 {
			t.Fatalf("ForgetProxyAddrs must force a new lookup (%d lookups)", n)
		}
	})

	t.Run("failures are cached briefly", func(t *testing.T) {
		var calls atomic.Int32
		d, now := newDialer(t, func(context.Context, string) ([]netip.Addr, error) {
			calls.Add(1)
			return nil, errors.New("no such host")
		})
		for range 3 {
			if _, err := d.Dial(context.Background(), target); CodeOf(err) != CodeProxyDNS {
				t.Fatalf("got %v, want PROXY_DNS_FAILED", err)
			}
		}
		if n := calls.Load(); n != 1 {
			t.Fatalf("a failed lookup must be reused for a few seconds (%d lookups)", n)
		}
		*now = now.Add(proxyAddrNegativeTTL)
		if _, err := d.Dial(context.Background(), target); CodeOf(err) != CodeProxyDNS || calls.Load() != 2 {
			t.Fatalf("after the negative TTL the host must be looked up again: %v, %d lookups", err, calls.Load())
		}
	})

	t.Run("a lookup abandoned by its dial is retried, not shared", func(t *testing.T) {
		var calls atomic.Int32
		release := make(chan struct{})
		d, _ := newDialer(t, func(ctx context.Context, _ string) ([]netip.Addr, error) {
			calls.Add(1)
			select {
			case <-release:
				return []netip.Addr{proxy.Addr.Addr()}, nil
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		})
		ctxA, cancelA := context.WithCancel(context.Background())
		errA := make(chan error, 1)
		go func() { _, err := d.Dial(ctxA, target); errA <- err }()
		waitCalls(t, &calls, 1)
		errB := make(chan error, 1)
		go func() {
			c, err := d.Dial(context.Background(), target)
			if err == nil {
				c.Close()
			}
			errB <- err
		}()
		time.Sleep(50 * time.Millisecond) // B waits for A's lookup
		cancelA()
		if err := <-errA; CodeOf(err) != CodeCanceled {
			t.Fatalf("the canceled dial: %v, want CANCELED", err)
		}
		waitCalls(t, &calls, 2) // B looks up for itself
		close(release)
		if err := <-errB; err != nil {
			t.Fatalf("the other dial must not inherit the cancellation: %v", err)
		}
		dialOK(t, d)
		if n := calls.Load(); n != 2 {
			t.Fatalf("B's result must be cached (%d lookups)", n)
		}
	})

	t.Run("a dial after ForgetProxyAddrs does not join a lookup from before", func(t *testing.T) {
		var calls atomic.Int32
		oldNetwork := make(chan struct{})
		d, _ := newDialer(t, func(context.Context, string) ([]netip.Addr, error) {
			if calls.Add(1) == 1 {
				<-oldNetwork // the lookup on the old network hangs, then fails
				return nil, errors.New("old network gone")
			}
			return []netip.Addr{proxy.Addr.Addr()}, nil
		})
		errOld := make(chan error, 1)
		go func() { _, err := d.Dial(context.Background(), target); errOld <- err }()
		waitCalls(t, &calls, 1)
		d.ForgetProxyAddrs() // the network changed
		dialOK(t, d)         // looks up again at once instead of waiting for the old lookup
		close(oldNetwork)
		if err := <-errOld; CodeOf(err) != CodeProxyDNS {
			t.Fatalf("the dial waiting on the old lookup gets its answer: %v", err)
		}
		dialOK(t, d) // the old failure was not cached over the new answer
		if n := calls.Load(); n != 2 {
			t.Fatalf("%d lookups, want 2", n)
		}
	})

	t.Run("a lookup that panics does not block later dials", func(t *testing.T) {
		var calls atomic.Int32
		d, _ := newDialer(t, func(context.Context, string) ([]netip.Addr, error) {
			if calls.Add(1) == 1 {
				panic("resolver bug")
			}
			return []netip.Addr{proxy.Addr.Addr()}, nil
		})
		func() {
			defer func() {
				if recover() == nil {
					t.Fatal("the panic must reach the dial that ran the lookup")
				}
			}()
			_, _ = d.Dial(context.Background(), target)
		}()
		dialOK(t, d) // would block on a lookup that never finishes
		if n := calls.Load(); n != 2 {
			t.Fatalf("%d lookups, want 2", n)
		}
	})

	t.Run("the reachable address moves to the front", func(t *testing.T) {
		// Nothing listens on 127.0.0.2: refused at once.
		d, _ := newDialer(t, func(context.Context, string) ([]netip.Addr, error) {
			return []netip.Addr{netip.MustParseAddr("127.0.0.2"), proxy.Addr.Addr()}, nil
		})
		dialOK(t, d)
		d.addrs.mu.Lock()
		got := d.addrs.addrs
		d.addrs.mu.Unlock()
		if len(got) != 2 || got[0] != proxy.Addr.Addr() {
			t.Fatalf("cached order %v, want the proxy's address first", got)
		}
	})
}

func TestProtectorRunsBeforeConnect(t *testing.T) {
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, Hosts: map[string]string{"e.test": echo.String()}})
	var protected atomic.Int32
	ok := func(fd uintptr) error {
		if fd == 0 {
			return errors.New("bad fd")
		}
		protected.Add(1)
		return nil
	}
	d, _ := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: proxy.Host(), Port: proxy.Port()}, nil, ok, nil)
	conn, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()
	if protected.Load() != 1 {
		t.Fatalf("protector called %d times, want once per proxy socket", protected.Load())
	}

	refuse := func(uintptr) error { return errors.New("protect refused") }
	d, _ = NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: proxy.Host(), Port: proxy.Port()}, nil, refuse, nil)
	before := proxy.Accepts()
	_, err = d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
	if CodeOf(err) != CodeProxyUnreachable || !strings.Contains(err.Error(), "protect") {
		t.Fatalf("an unprotectable socket must not be used, got %v", err)
	}
	time.Sleep(50 * time.Millisecond)
	if proxy.Accepts() != before {
		t.Fatal("the socket connected although protect failed")
	}
}

// --- TLS to the proxy --------------------------------------------------------

func tlsProxy(t *testing.T, cert tls.Certificate, hosts map[string]string) *proxytest.Server {
	return proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, TLS: proxytest.ServerTLS(cert), Hosts: hosts})
}

func TestTLSToProxyValidation(t *testing.T) {
	proxytest.CheckLeaks(t)
	echo := proxytest.NewEcho(t, "tcp4")
	hosts := map[string]string{"e.test": echo.String()}
	ca := proxytest.NewCA(t, "ProxyFlow Test CA")
	inter := ca.Intermediate(t, "ProxyFlow Test Intermediate")
	other := proxytest.NewCA(t, "Untrusted CA")
	now := time.Now()
	leaf := func(o proxytest.LeafOptions) tls.Certificate {
		if o.DNSNames == nil {
			o.DNSNames = []string{"proxy.test"}
		}
		return ca.Leaf(t, o)
	}
	cases := []struct {
		name string
		cert tls.Certificate
		sni  string
		want Code
	}{
		{"valid chain and hostname", leaf(proxytest.LeafOptions{}), "proxy.test", ""},
		{"valid via intermediate", inter.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}, Chain: []*x509.Certificate{inter.Cert}}), "proxy.test", ""},
		{"server name case-insensitive", leaf(proxytest.LeafOptions{}), "PROXY.test", ""},
		{"wildcard", leaf(proxytest.LeafOptions{DNSNames: []string{"*.example.test"}}), "proxy.example.test", ""},
		{"hostname mismatch", leaf(proxytest.LeafOptions{}), "other.test", CodeTLSHostname},
		{"wildcard does not cover two labels", leaf(proxytest.LeafOptions{DNSNames: []string{"*.test"}}), "a.proxy.test", CodeTLSHostname},
		{"expired", leaf(proxytest.LeafOptions{NotBefore: now.Add(-48 * time.Hour), NotAfter: now.Add(-24 * time.Hour)}), "proxy.test", CodeTLSExpired},
		{"not yet valid", leaf(proxytest.LeafOptions{NotBefore: now.Add(time.Hour), NotAfter: now.Add(48 * time.Hour)}), "proxy.test", CodeTLSExpired},
		{"untrusted issuer", other.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}}), "proxy.test", CodeTLSUntrusted},
		{"self-signed", proxytest.SelfSigned(t, "proxy.test"), "proxy.test", CodeTLSUntrusted},
		{"intermediate not sent", inter.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}}), "proxy.test", CodeTLSUntrusted},
		{"client-auth-only certificate", leaf(proxytest.LeafOptions{ClientAuthOnly: true}), "proxy.test", CodeTLSUntrusted},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			proxy := tlsProxy(t, tc.cert, hosts)
			d := dialerTo(t, proxy, KindSOCKS5, nil, &TLSOptions{ServerName: tc.sni, Verifier: RootsVerifier{Roots: ca.Pool}})
			c, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
			if tc.want == "" {
				if err != nil {
					t.Fatalf("expected success, got %v", err)
				}
				if _, ok := c.(*tls.Conn); !ok {
					t.Fatalf("the tunnel must run over TLS, got %T", c)
				}
				echoRoundTrip(t, c)
				c.Close()
				return
			}
			if CodeOf(err) != tc.want {
				t.Fatalf("got %v, want %s", err, tc.want)
			}
			if !tc.want.IsProxyLevel() {
				t.Fatalf("%s must be a proxy-level failure", tc.want)
			}
			for _, r := range proxy.Records() {
				if r.Outcome == "ok" || len(r.Methods) > 0 {
					t.Fatal("nothing may be sent to a proxy whose certificate failed verification")
				}
			}
		})
	}
}

func TestTLSToProxyByIPAddress(t *testing.T) {
	ca := proxytest.NewCA(t, "Root")
	echo := proxytest.NewEcho(t, "tcp4")
	hosts := map[string]string{"e.test": echo.String()}
	withIP := tlsProxy(t, ca.Leaf(t, proxytest.LeafOptions{IPs: []net.IP{net.ParseIP("127.0.0.1")}}), hosts)
	conn, err := dialerTo(t, withIP, KindSOCKS5, nil, &TLSOptions{Verifier: RootsVerifier{Roots: ca.Pool}}).Dial(context.Background(), Target{Host: "e.test", Port: 1})
	if err != nil {
		t.Fatalf("certificate with the proxy's IP must verify: %v", err)
	}
	conn.Close()
	nameOnly := tlsProxy(t, ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}}), hosts)
	_, err = dialerTo(t, nameOnly, KindSOCKS5, nil, &TLSOptions{Verifier: RootsVerifier{Roots: ca.Pool}}).Dial(context.Background(), Target{Host: "e.test", Port: 1})
	if CodeOf(err) != CodeTLSHostname {
		t.Fatalf("got %v, want TLS_HOSTNAME", err)
	}
}

func TestTLSResumptionIsVerifiedAgain(t *testing.T) {
	ca := proxytest.NewCA(t, "Root")
	echo := proxytest.NewEcho(t, "tcp4")
	proxy := tlsProxy(t, ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}}), map[string]string{"e.test": echo.String()})
	var calls atomic.Int32
	var reject atomic.Bool
	v := funcVerifier(func(chain []*x509.Certificate, name string) error {
		calls.Add(1)
		if reject.Load() {
			return newErr(CodeTLSUntrusted, "revoked for the test", nil)
		}
		return RootsVerifier{Roots: ca.Pool}.VerifyChain(chain, name)
	})
	d := dialerTo(t, proxy, KindSOCKS5, nil, &TLSOptions{ServerName: "proxy.test", Verifier: v})
	first, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
	if err != nil {
		t.Fatal(err)
	}
	echoRoundTrip(t, first) // reading also receives the TLS 1.3 session ticket
	first.Close()
	second, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
	if err != nil {
		t.Fatal(err)
	}
	if !second.(*tls.Conn).ConnectionState().DidResume {
		t.Fatal("expected the second handshake to resume the session")
	}
	second.Close()
	if calls.Load() != 2 {
		t.Fatalf("verifier ran %d times, want on every handshake including resumed ones", calls.Load())
	}
	reject.Store(true)
	if _, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1}); CodeOf(err) != CodeTLSUntrusted {
		t.Fatalf("a resumed session must still be rejected by the verifier, got %v", err)
	}
}

// An app's half-close reaches the destination over TLS 1.3 (close_notify
// closes one direction) but not over TLS 1.2, where close_notify would end the
// whole connection and cut the reply short.
func TestTLSHalfCloseFollowsTheVersion(t *testing.T) {
	proxytest.CheckLeaks(t)
	ca := proxytest.NewCA(t, "Root")
	cert := ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}})
	for _, tc := range []struct {
		name    string
		version uint16
		eof     bool
	}{
		{"TLS 1.3", tls.VersionTLS13, true},
		{"TLS 1.2", tls.VersionTLS12, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			echo := proxytest.NewEcho(t, "tcp4")
			cfg := proxytest.ServerTLS(cert)
			cfg.MaxVersion = tc.version
			proxy := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5, TLS: cfg, Hosts: map[string]string{"e.test": echo.String()}})
			d := dialerTo(t, proxy, KindSOCKS5, nil, &TLSOptions{ServerName: "proxy.test", Verifier: RootsVerifier{Roots: ca.Pool}})
			conn, err := d.Dial(context.Background(), Target{Host: "e.test", Port: 1})
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			if _, err := conn.Write([]byte("ping")); err != nil {
				t.Fatal(err)
			}
			cw, ok := conn.(interface{ CloseWrite() error })
			if !ok {
				t.Fatalf("%T must offer CloseWrite to the relay", conn)
			}
			if err := cw.CloseWrite(); err != nil {
				t.Fatalf("CloseWrite: %v", err)
			}
			buf := make([]byte, 4)
			_ = conn.SetReadDeadline(time.Now().Add(testTimeout))
			if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "ping" {
				t.Fatalf("the reply must arrive after the half-close: %q %v", buf, err)
			}
			_ = conn.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
			_, err = conn.Read(buf)
			if tc.eof && !errors.Is(err, io.EOF) {
				t.Fatalf("the half-close must reach the echo server, which then closes: got %v", err)
			}
			var ne net.Error
			if !tc.eof && !(errors.As(err, &ne) && ne.Timeout()) {
				t.Fatalf("no close_notify may be sent over TLS 1.2, so the echo server stays open: got %v", err)
			}
		})
	}
}

func TestTLSMismatchedTransport(t *testing.T) {
	// TLS configured, proxy speaks plaintext HTTP and answers garbage at once.
	ln, _ := net.Listen("tcp4", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err == nil {
			_, _ = c.Write([]byte("HTTP/1.1 400 Bad Request\r\n\r\n"))
			time.Sleep(100 * time.Millisecond)
			c.Close()
		}
	}()
	addr := ln.Addr().(*net.TCPAddr).AddrPort()
	d, _ := NewDialer(ProxyConfig{Kind: KindHTTPConnect, Host: addr.Addr().String(), Port: addr.Port(),
		TLS: &TLSOptions{ServerName: "proxy.test", Verifier: RootsVerifier{Roots: x509.NewCertPool()}}, HandshakeTimeout: testTimeout}, nil, nil, nil)
	if _, err := d.Dial(context.Background(), Target{Host: "web.test", Port: 443}); CodeOf(err) != CodeTLSHandshake {
		t.Fatalf("got %v, want TLS_HANDSHAKE", err)
	}

	// TLS configured, plaintext SOCKS5 proxy closes on the ClientHello.
	plain := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.SOCKS5})
	d = dialerTo(t, plain, KindSOCKS5, nil, &TLSOptions{ServerName: "proxy.test", Verifier: RootsVerifier{Roots: x509.NewCertPool()}})
	if _, err := d.Dial(context.Background(), Target{Host: "a.test", Port: 80}); CodeOf(err) != CodeTLSHandshake {
		t.Fatalf("got %v, want TLS_HANDSHAKE", err)
	}

	// No TLS configured, proxy expects TLS. The 3-byte SOCKS5 greeting is
	// shorter than a TLS record header, so a TLS server waits for more, like
	// stunnel does: the dial ends at the handshake timeout. An HTTP CONNECT
	// request is long enough for the server to reject it at once.
	ca := proxytest.NewCA(t, "Root")
	tlsOnly := tlsProxy(t, ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}}), nil)
	d, _ = NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: tlsOnly.Host(), Port: tlsOnly.Port(), HandshakeTimeout: 300 * time.Millisecond}, nil, nil, nil)
	if _, err := d.Dial(context.Background(), Target{Host: "a.test", Port: 80}); CodeOf(err) != CodeTimeout {
		t.Fatalf("SOCKS5 to a TLS-only proxy: got %v, want HANDSHAKE_TIMEOUT", err)
	}
	tlsHTTP := proxytest.NewServer(t, proxytest.Config{Protocol: proxytest.HTTPConnect, TLS: proxytest.ServerTLS(ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}}))})
	if _, err := dialerTo(t, tlsHTTP, KindHTTPConnect, nil, nil).Dial(context.Background(), Target{Host: "a.test", Port: 80}); CodeOf(err) != CodeProtocol {
		t.Fatalf("HTTP to a TLS-only proxy: got %v, want PROTOCOL_ERROR", err)
	}
}

// --- configuration -------------------------------------------------------------

func TestConfigValidation(t *testing.T) {
	v := RootsVerifier{}
	bad := []ProxyConfig{
		{Kind: KindSOCKS5, Port: 1080},
		{Kind: KindSOCKS5, Host: "h"},
		{Kind: 0, Host: "h", Port: 1},
		{Kind: KindSOCKS5, Host: "bad host", Port: 1},
		{Kind: KindSOCKS5, Host: "fe80::1%eth0", Port: 1},
		{Kind: KindSOCKS5, Host: "a.test\r\nX: y", Port: 1},
		{Kind: KindSOCKS5, Host: "h", Port: 1, TLS: &TLSOptions{}},
		{Kind: KindSOCKS5, Host: "h", Port: 1, TLS: &TLSOptions{ServerName: "bad name", Verifier: v}},
		{Kind: KindSOCKS5, Host: "h", Port: 1, DialTimeout: -1},
	}
	for i, c := range bad {
		if c.Validate() == nil {
			t.Errorf("case %d should be invalid", i)
		}
	}
	good := []ProxyConfig{
		{Kind: KindSOCKS5, Host: "Proxy.Example.COM.", Port: 1080},
		{Kind: KindHTTPConnect, Host: "2001:db8::1", Port: 3128},
		{Kind: KindHTTPConnect, Host: "192.0.2.1", Port: 3128, TLS: &TLSOptions{ServerName: "Proxy.Example", Verifier: v}},
	}
	for i, c := range good {
		if err := c.Validate(); err != nil {
			t.Errorf("good case %d: %v", i, err)
		}
	}
	d, _ := NewDialer(good[0], nil, nil, nil)
	if d.Config().Host != "proxy.example.com" || d.Config().DialTimeout != DefaultDialTimeout || d.Config().HandshakeTimeout != DefaultHandshakeTimeout {
		t.Fatalf("normalised config = %+v", d.Config())
	}
}

func TestCredentialLimits(t *testing.T) {
	base := func(k Kind) ProxyConfig { return ProxyConfig{Kind: k, Host: "h", Port: 1} }
	long256, long1024, long1025 := strings.Repeat("u", 256), strings.Repeat("u", 1024), strings.Repeat("u", 1025)
	bad := []struct {
		kind Kind
		c    *Credentials
	}{
		{KindSOCKS5, creds(long256, "p")},
		{KindSOCKS5, creds("u", long256)},
		{KindSOCKS5, creds("", "p")},
		{KindHTTPConnect, creds(long1025, "p")},
		{KindHTTPConnect, creds("a:b", "p")},
	}
	for i, tc := range bad {
		if _, err := NewDialer(base(tc.kind), tc.c, nil, nil); err == nil {
			t.Errorf("case %d should be rejected", i)
		} else if strings.Contains(err.Error(), "uuuu") {
			t.Errorf("case %d: the error repeats the credentials", i)
		}
	}
	good := []struct {
		kind Kind
		c    *Credentials
	}{
		{KindSOCKS5, creds(strings.Repeat("u", 255), strings.Repeat("p", 255))},
		{KindHTTPConnect, creds(long1024, long1024)},
		{KindHTTPConnect, creds("user", "pass:with:colons")},
		{KindSOCKS5, nil},
		{KindSOCKS5, creds("", "")},
	}
	for i, tc := range good {
		if _, err := NewDialer(base(tc.kind), tc.c, nil, nil); err != nil {
			t.Errorf("good case %d: %v", i, err)
		}
	}
	wiped := creds("u", "p")
	wiped.Wipe()
	if _, err := NewDialer(base(KindSOCKS5), wiped, nil, nil); err == nil {
		t.Error("wiped credentials must be rejected")
	}
}

func TestDialerAndConfigNeverRevealSecrets(t *testing.T) {
	d, err := NewDialer(ProxyConfig{Kind: KindHTTPConnect, Host: "proxy.test", Port: 3128,
		TLS: &TLSOptions{Verifier: RootsVerifier{}}}, creds("alice-user", "hunter2-secret"), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range []string{fmt.Sprintf("%v", d), fmt.Sprintf("%+v", d), fmt.Sprintf("%#v", d), d.String(), fmt.Sprintf("%+v", d.Config())} {
		if strings.Contains(s, "hunter2") || strings.Contains(s, "alice") {
			t.Fatalf("credentials leaked into %q", s)
		}
	}
	if d.String() != "http+tls://proxy.test:3128 (sign-in on)" {
		t.Fatalf("unexpected log form %q", d.String())
	}
}

// TestErrorsNeverContainCredentials drives every failure path with distinctive
// credentials and checks no error text carries them in any encoding.
func TestErrorsNeverContainCredentials(t *testing.T) {
	const user, pass = "Zq7user", "Xy9secretpw"
	basic := base64.StdEncoding.EncodeToString([]byte(user + ":" + pass))
	secrets := []string{user, pass, basic, strings.ToLower(user), fmt.Sprintf("%x", user), fmt.Sprintf("%x", pass)}
	echo := proxytest.NewEcho(t, "tcp4")
	hosts := map[string]string{"e.test": echo.String()}
	ca := proxytest.NewCA(t, "Root")
	selfSigned := proxytest.ServerTLS(proxytest.SelfSigned(t, "proxy.test"))
	failures := map[proxytest.Protocol][]proxytest.Config{
		proxytest.SOCKS5: {
			{Username: "other", Password: "x"},
			{Username: user, Password: "different"},
			{Username: user, Password: pass, SOCKSReply: 0x02},
			{SOCKSReply: 0x05},
			{SOCKSMethod: 0xFF},
			{SOCKSBadVer: true},
			{CloseOnAccept: true},
			{TLS: selfSigned},
		},
		proxytest.HTTPConnect: {
			{Username: "other", Password: "x"},
			{Username: user, Password: "different"},
			{Username: user, Password: pass, HTTPStatus: 502},
			{HTTPStatus: 403},
			{HTTPStatus: 407},
			{RawResponse: []byte("HTTP/1.1 999 " + user + "\r\n\r\n")},
			{RawResponse: []byte("SSH-2.0-" + pass + "\r\n")},
			{CloseOnAccept: true},
			{TLS: selfSigned},
		},
	}
	var errs []error
	for p, configs := range failures {
		for _, cfg := range configs {
			cfg.Protocol, cfg.Hosts = p, hosts
			kind := KindSOCKS5
			if p == proxytest.HTTPConnect {
				kind = KindHTTPConnect
			}
			var tlsOpt *TLSOptions
			if cfg.TLS != nil {
				tlsOpt = &TLSOptions{ServerName: "proxy.test", Verifier: RootsVerifier{Roots: ca.Pool}}
			}
			proxy := proxytest.NewServer(t, cfg)
			_, err := dialerTo(t, proxy, kind, creds(user, pass), tlsOpt).Dial(context.Background(), Target{Host: "e.test", Port: 1})
			if err == nil {
				t.Fatalf("%s %+v: expected a failure", kind, cfg)
			}
			errs = append(errs, err)
		}
	}
	for _, err := range errs {
		texts := []string{err.Error(), fmt.Sprintf("%v", err), fmt.Sprintf("%+v", err), fmt.Sprintf("%#v", err), DetailOf(err)}
		for u := errors.Unwrap(err); u != nil; u = errors.Unwrap(u) {
			texts = append(texts, u.Error())
		}
		for _, s := range texts {
			for _, secret := range secrets {
				if strings.Contains(s, secret) {
					t.Fatalf("error text %q contains a credential", s)
				}
			}
		}
	}
}

func waitFor(t *testing.T, cond func() bool, what string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}
