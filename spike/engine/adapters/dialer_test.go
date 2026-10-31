package adapters

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"fmt"
	"math/big"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

// --- test PKI ---------------------------------------------------------------

type testCA struct {
	cert *x509.Certificate
	key  *ecdsa.PrivateKey
	pool *x509.CertPool
}

func newCA(t *testing.T, name string) *testCA {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: name},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(der)
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	return &testCA{cert: cert, key: key, pool: pool}
}

func (ca *testCA) leaf(t *testing.T, dns string, notBefore, notAfter time.Time) tls.Certificate {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: dns},
		DNSNames: []string{dns}, NotBefore: notBefore, NotAfter: notAfter,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, KeyUsage: x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca.cert, &key.PublicKey, ca.key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

// tlsSOCKSServer accepts TLS then answers a no-auth SOCKS5 CONNECT with success.
func tlsSOCKSServer(t *testing.T, cert tls.Certificate) string {
	t.Helper()
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				b := make([]byte, 512)
				if _, err := c.Read(b); err != nil { // greeting
					return
				}
				c.Write([]byte{5, 0})
				c.Read(b) // request
				c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				c.Read(b)
			}(c)
		}
	}()
	return ln.Addr().String()
}

func dialerFor(t *testing.T, addr string, kind Kind, tlsOpt *TLSOptions) *Dialer {
	t.Helper()
	host, p, _ := net.SplitHostPort(addr)
	port, _ := strconv.Atoi(p)
	d, err := NewDialer(ProxyConfig{Kind: kind, Host: host, Port: uint16(port), TLS: tlsOpt,
		DialTimeout: 2 * time.Second, HandshakeTimeout: 2 * time.Second}, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestTLSToProxyValidation(t *testing.T) {
	ca := newCA(t, "ProxyFlow Test CA")
	other := newCA(t, "Untrusted CA")
	now := time.Now()
	good := tlsSOCKSServer(t, ca.leaf(t, "proxy.test", now.Add(-time.Hour), now.Add(time.Hour)))
	expired := tlsSOCKSServer(t, ca.leaf(t, "proxy.test", now.Add(-48*time.Hour), now.Add(-24*time.Hour)))
	untrusted := tlsSOCKSServer(t, other.leaf(t, "proxy.test", now.Add(-time.Hour), now.Add(time.Hour)))

	target := Target{Host: "web.test", Port: 80}
	cases := []struct {
		name string
		addr string
		sni  string
		want Code
	}{
		{"valid chain and hostname", good, "proxy.test", ""},
		{"hostname mismatch", good, "other.test", CodeTLSHostname},
		{"expired certificate", expired, "proxy.test", CodeTLSExpired},
		{"untrusted issuer", untrusted, "proxy.test", CodeTLSUntrusted},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			d := dialerFor(t, tc.addr, KindSOCKS5, &TLSOptions{ServerName: tc.sni, RootCAs: ca.pool})
			c, err := d.Dial(context.Background(), target)
			if tc.want == "" {
				if err != nil {
					t.Fatalf("expected success, got %v", err)
				}
				if _, ok := c.(*tls.Conn); !ok {
					t.Fatalf("tunnel must run over TLS, got %T", c)
				}
				c.Close()
				return
			}
			if CodeOf(err) != tc.want {
				t.Fatalf("got %v, want %s", err, tc.want)
			}
			if !tc.want.IsProxyLevel() {
				t.Fatalf("%s must be a proxy-level failure", tc.want)
			}
		})
	}
}

func TestTLSToPlaintextProxy(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err == nil {
			c.Write([]byte("HTTP/1.1 400 Bad Request\r\n\r\n"))
			time.Sleep(100 * time.Millisecond)
			c.Close()
		}
	}()
	d := dialerFor(t, ln.Addr().String(), KindHTTPConnect, &TLSOptions{ServerName: "proxy.test", RootCAs: x509.NewCertPool()})
	_, err := d.Dial(context.Background(), Target{Host: "web.test", Port: 443})
	if CodeOf(err) != CodeTLSHandshake {
		t.Fatalf("got %v, want TLS_HANDSHAKE", err)
	}
}

func TestProxyUnreachableAndTimeout(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	closedAddr := ln.Addr().String()
	ln.Close()
	_, err := dialerFor(t, closedAddr, KindSOCKS5, nil).Dial(context.Background(), Target{Host: "a.test", Port: 80})
	if CodeOf(err) != CodeProxyUnreachable {
		t.Fatalf("closed port: got %v, want PROXY_UNREACHABLE", err)
	}

	silent, _ := net.Listen("tcp", "127.0.0.1:0")
	defer silent.Close()
	go func() {
		for {
			c, err := silent.Accept()
			if err != nil {
				return
			}
			defer c.Close() // accept, never answer
		}
	}()
	d := dialerFor(t, silent.Addr().String(), KindSOCKS5, nil)
	d.cfg.HandshakeTimeout = 300 * time.Millisecond
	start := time.Now()
	_, err = d.Dial(context.Background(), Target{Host: "a.test", Port: 80})
	if CodeOf(err) != CodeTimeout {
		t.Fatalf("silent proxy: got %v, want HANDSHAKE_TIMEOUT", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("handshake timeout not enforced")
	}
}

func TestProxyHostnameResolutionFailure(t *testing.T) {
	failing := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, n, a string) (net.Conn, error) {
		return nil, errors.New("no underlying network")
	}}
	d, err := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: "proxy.example.invalid", Port: 1080, DialTimeout: time.Second}, nil, failing)
	if err != nil {
		t.Fatal(err)
	}
	_, err = d.Dial(context.Background(), Target{Host: "a.test", Port: 80})
	if CodeOf(err) != CodeProxyDNS {
		t.Fatalf("got %v, want PROXY_DNS_FAILED", err)
	}
}

func TestProtectorFailureFailsClosed(t *testing.T) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	defer ln.Close()
	host, p, _ := net.SplitHostPort(ln.Addr().String())
	port, _ := strconv.Atoi(p)
	called := false
	d, _ := NewDialer(ProxyConfig{Kind: KindSOCKS5, Host: host, Port: uint16(port)},
		func(fd uintptr) error { called = true; return errors.New("protect refused") }, nil)
	_, err := d.Dial(context.Background(), Target{Host: "a.test", Port: 80})
	if !called {
		t.Fatalf("protector must be invoked on the upstream socket")
	}
	if err == nil || CodeOf(err) != CodeProxyUnreachable {
		t.Fatalf("an unprotectable socket must not be used, got %v", err)
	}
}

func TestConfigNeverRevealsSecrets(t *testing.T) {
	cfg := ProxyConfig{Kind: KindHTTPConnect, Host: "proxy.test", Port: 3128, Username: "alice-user", Password: "hunter2-secret", TLS: &TLSOptions{}}
	for _, s := range []string{fmt.Sprintf("%v", cfg), fmt.Sprintf("%+v", cfg), fmt.Sprintf("%#v", cfg), fmt.Sprint(cfg), cfg.String()} {
		if strings.Contains(s, "hunter2") || strings.Contains(s, "alice-user") {
			t.Fatalf("credentials leaked into formatted config: %q", s)
		}
	}
	if !strings.Contains(cfg.String(), "http+tls://proxy.test:3128") {
		t.Fatalf("unexpected redacted form %q", cfg.String())
	}
}

func TestConfigValidation(t *testing.T) {
	bad := []ProxyConfig{
		{Kind: KindSOCKS5, Port: 1080},
		{Kind: KindSOCKS5, Host: "h"},
		{Kind: 0, Host: "h", Port: 1},
		{Kind: KindSOCKS5, Host: "h", Port: 1, Password: "p"},
		{Kind: KindSOCKS5, Host: "h", Port: 1, Username: strings.Repeat("u", 256), Password: "p"},
		{Kind: KindHTTPConnect, Host: "h", Port: 1, Username: "a:b", Password: "p"},
	}
	for i, c := range bad {
		if c.Validate() == nil {
			t.Errorf("case %d should be invalid", i)
		}
	}
}

func TestValidHostname(t *testing.T) {
	good := []string{"example.com", "a.b-c.d", "xn--bcher-kva.example", "_dmarc.example.org", "localhost", "a1"}
	bad := []string{"", "Example.com", "-a.com", "a-.com", "a..com", ".a.com", "a.com.", "a b.com", "a.com\r\n", "a:80", strings.Repeat("a", 64) + ".com"}
	for _, s := range good {
		if !ValidHostname(s) {
			t.Errorf("%q should be valid", s)
		}
	}
	for _, s := range bad {
		if ValidHostname(s) {
			t.Errorf("%q should be invalid", s)
		}
	}
}

// Regression for the nil-conn crash found by the end-to-end suite (E03): every
// HTTP CONNECT refusal path must return a categorised error through Dial.
func TestDialHTTPConnectRefusalNoPanic(t *testing.T) {
	for _, status := range []string{"403 Forbidden", "407 Proxy Authentication Required", "502 Bad Gateway", "405 Method Not Allowed"} {
		ln, _ := net.Listen("tcp", "127.0.0.1:0")
		resp := status
		go func() {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			buf := make([]byte, 1024)
			c.Read(buf)
			c.Write([]byte("HTTP/1.1 " + resp + "\r\n\r\n"))
			c.Close()
		}()
		d := dialerFor(t, ln.Addr().String(), KindHTTPConnect, nil)
		_, err := d.Dial(context.Background(), Target{Host: "web.test", Port: 8081})
		if err == nil || CodeOf(err) == CodeInternal {
			t.Errorf("%s: expected a categorised error, got %v", status, err)
		}
		ln.Close()
	}
}
