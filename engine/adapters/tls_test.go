package adapters

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/bibekthapamgr/app/engine/internal/proxytest"
)

func chainOf(c tls.Certificate) []*x509.Certificate {
	var out []*x509.Certificate
	for _, der := range c.Certificate {
		x, err := x509.ParseCertificate(der)
		if err != nil {
			panic(err)
		}
		out = append(out, x)
	}
	return out
}

func TestRootsVerifier(t *testing.T) {
	ca := proxytest.NewCA(t, "Root")
	inter := ca.Intermediate(t, "Intermediate")
	other := proxytest.NewCA(t, "Other")
	now := time.Now()
	cases := []struct {
		name  string
		chain []*x509.Certificate
		now   func() time.Time
		want  Code
	}{
		{"leaf signed by root", chainOf(ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"p.test"}})), nil, ""},
		{"leaf via intermediate", chainOf(inter.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"p.test"}, Chain: []*x509.Certificate{inter.Cert}})), nil, ""},
		{"intermediate missing", chainOf(inter.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"p.test"}})), nil, CodeTLSUntrusted},
		{"other CA", chainOf(other.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"p.test"}})), nil, CodeTLSUntrusted},
		{"self-signed", chainOf(proxytest.SelfSigned(t, "p.test")), nil, CodeTLSUntrusted},
		{"expired", chainOf(ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"p.test"}})), func() time.Time { return now.Add(48 * time.Hour) }, CodeTLSExpired},
		{"not yet valid", chainOf(ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"p.test"}, NotBefore: now.Add(time.Hour)})), nil, CodeTLSExpired},
		{"client-auth certificate", chainOf(ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"p.test"}, ClientAuthOnly: true})), nil, CodeTLSUntrusted},
		{"empty chain", nil, nil, CodeTLSUntrusted},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := RootsVerifier{Roots: ca.Pool, Now: tc.now}.VerifyChain(tc.chain, "p.test")
			if CodeOf(err) != tc.want && !(tc.want == "" && err == nil) {
				t.Fatalf("got %v, want %q", err, tc.want)
			}
		})
	}
}

type funcVerifier func(chain []*x509.Certificate, serverName string) error

func (f funcVerifier) VerifyChain(chain []*x509.Certificate, serverName string) error {
	return f(chain, serverName)
}

func TestVerifyPeerChecksHostnameBeforeTrust(t *testing.T) {
	ca := proxytest.NewCA(t, "Root")
	leaf := ca.Leaf(t, proxytest.LeafOptions{DNSNames: []string{"proxy.test"}, IPs: []net.IP{net.ParseIP("192.0.2.1")}})
	cs := tls.ConnectionState{PeerCertificates: chainOf(leaf)}
	called := 0
	var gotName string
	v := funcVerifier(func(_ []*x509.Certificate, name string) error { called++; gotName = name; return nil })

	if err := verifyPeer(cs, "proxy.test", v); err != nil || called != 1 || gotName != "proxy.test" {
		t.Fatalf("matching name: err=%v called=%d name=%q", err, called, gotName)
	}
	if err := verifyPeer(cs, "192.0.2.1", v); err != nil {
		t.Fatalf("IP SAN must match an IP server name: %v", err)
	}
	called = 0
	if err := verifyPeer(cs, "other.test", v); CodeOf(err) != CodeTLSHostname || called != 0 {
		t.Fatalf("mismatch: got %v (verifier called %d times), want TLS_HOSTNAME before trust", err, called)
	}
	if err := verifyPeer(tls.ConnectionState{}, "proxy.test", v); CodeOf(err) != CodeTLSUntrusted {
		t.Fatalf("no certificate: got %v", err)
	}
	// A verifier's categorised error passes through; anything else is TLS_UNTRUSTED.
	expired := funcVerifier(func(_ []*x509.Certificate, _ string) error { return newErr(CodeTLSExpired, "x", nil) })
	if err := verifyPeer(cs, "proxy.test", expired); CodeOf(err) != CodeTLSExpired {
		t.Fatalf("got %v, want TLS_EXPIRED", err)
	}
	plain := funcVerifier(func(_ []*x509.Certificate, _ string) error { return errors.New("boom") })
	if err := verifyPeer(cs, "proxy.test", plain); CodeOf(err) != CodeTLSUntrusted {
		t.Fatalf("got %v, want TLS_UNTRUSTED", err)
	}
}

func TestTLSWithoutVerifierIsRefused(t *testing.T) {
	cfg := ProxyConfig{Kind: KindSOCKS5, Host: "proxy.test", Port: 1080, TLS: &TLSOptions{}}
	if _, err := NewDialer(cfg, nil, nil, nil); err == nil {
		t.Fatal("TLS without a verifier must be a configuration error")
	}
	if _, err := tlsWrap(context.Background(), nil, "proxy.test", &TLSOptions{}, nil); CodeOf(err) != CodeInternal {
		t.Fatalf("got %v, want INTERNAL", err)
	}
}
