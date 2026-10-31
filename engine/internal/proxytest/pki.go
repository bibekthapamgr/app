// Package proxytest provides deterministic fixtures for engine tests: a test
// PKI, mock SOCKS5 and HTTP CONNECT proxies (optionally behind TLS) with
// scripted misbehaviour, an echo destination, and checks that a test leaves no
// goroutines or file descriptors behind. It is test-only code (internal, and
// imported only from _test files).
package proxytest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"sync/atomic"
	"testing"
	"time"
)

var serial atomic.Int64

// CA is a test certificate authority.
type CA struct {
	Cert *x509.Certificate
	Key  *ecdsa.PrivateKey
	Pool *x509.CertPool // contains only this CA
}

// NewCA creates a self-signed root valid from an hour ago for a day.
func NewCA(t testing.TB, name string) *CA {
	t.Helper()
	return newCA(t, name, nil)
}

// Intermediate creates a CA certificate signed by ca. Its Pool holds the
// intermediate only (use the root's Pool as trust anchors).
func (ca *CA) Intermediate(t testing.TB, name string) *CA {
	t.Helper()
	return newCA(t, name, ca)
}

func newCA(t testing.TB, name string, parent *CA) *CA {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(serial.Add(1)), Subject: pkix.Name{CommonName: name},
		NotBefore: now.Add(-time.Hour), NotAfter: now.Add(24 * time.Hour),
		IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
	}
	signer, signerKey := tmpl, key
	if parent != nil {
		signer, signerKey = parent.Cert, parent.Key
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, signer, &key.PublicKey, signerKey)
	if err != nil {
		t.Fatal(err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	return &CA{Cert: cert, Key: key, Pool: pool}
}

// LeafOptions describes a server certificate.
type LeafOptions struct {
	DNSNames  []string
	IPs       []net.IP
	NotBefore time.Time // zero: an hour ago
	NotAfter  time.Time // zero: in a day
	// ClientAuthOnly gives the certificate the clientAuth EKU instead of
	// serverAuth, so it must be rejected for a server.
	ClientAuthOnly bool
	// Chain is appended after the leaf (intermediates the server sends).
	Chain []*x509.Certificate
}

// Leaf issues a server certificate signed by ca.
func (ca *CA) Leaf(t testing.TB, o LeafOptions) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	if o.NotBefore.IsZero() {
		o.NotBefore = now.Add(-time.Hour)
	}
	if o.NotAfter.IsZero() {
		o.NotAfter = now.Add(24 * time.Hour)
	}
	eku := x509.ExtKeyUsageServerAuth
	if o.ClientAuthOnly {
		eku = x509.ExtKeyUsageClientAuth
	}
	cn := "proxytest leaf"
	if len(o.DNSNames) > 0 {
		cn = o.DNSNames[0]
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(serial.Add(1)), Subject: pkix.Name{CommonName: cn},
		DNSNames: o.DNSNames, IPAddresses: o.IPs, NotBefore: o.NotBefore, NotAfter: o.NotAfter,
		ExtKeyUsage: []x509.ExtKeyUsage{eku}, KeyUsage: x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca.Cert, &key.PublicKey, ca.Key)
	if err != nil {
		t.Fatal(err)
	}
	chain := [][]byte{der}
	for _, c := range o.Chain {
		chain = append(chain, c.Raw)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: chain, PrivateKey: key, Leaf: leaf}
}

// SelfSigned returns a self-signed server certificate for dnsName (no CA).
func SelfSigned(t testing.TB, dnsName string) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(serial.Add(1)), Subject: pkix.Name{CommonName: dnsName},
		DNSNames: []string{dnsName}, NotBefore: now.Add(-time.Hour), NotAfter: now.Add(24 * time.Hour),
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, KeyUsage: x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, _ := x509.ParseCertificate(der)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: leaf}
}

// ServerTLS returns a server tls.Config presenting cert.
func ServerTLS(cert tls.Certificate) *tls.Config {
	return &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}
}
