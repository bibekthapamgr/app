package adapters

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"time"
)

// CertVerifier decides whether the certificate chain the proxy presented
// (leaf first) is trusted. The hostname is checked by tlsWrap itself; a
// verifier checks trust and validity. It returns nil to accept; to reject it
// returns an *Error with a TLS code (any other error counts as TLS_UNTRUSTED).
//
// On Android the app passes a verifier backed by the platform X509TrustManager
// (system CAs only, platform distrust rules, network security config;
// ADR-0005). RootsVerifier serves tests and desktop tools.
type CertVerifier interface {
	// VerifyChain gets the presented chain (leaf first) and the server name it
	// was checked against, which platform trust managers use for per-domain
	// rules such as pinning.
	VerifyChain(chain []*x509.Certificate, serverName string) error
}

// TLSOptions enables TLS between the device and the proxy endpoint.
// Verification cannot be turned off: a nil Verifier is a configuration error.
type TLSOptions struct {
	// ServerName is sent as SNI and must match the certificate. Defaults to
	// the proxy host.
	ServerName string
	Verifier   CertVerifier
}

// RootsVerifier verifies with Go's crypto/x509 against explicit roots.
type RootsVerifier struct {
	Roots *x509.CertPool
	// Now overrides the clock (tests); nil means time.Now.
	Now func() time.Time
}

// VerifyChain implements CertVerifier.
func (v RootsVerifier) VerifyChain(chain []*x509.Certificate, _ string) error {
	if len(chain) == 0 {
		return newErr(CodeTLSUntrusted, "proxy presented no certificate", nil)
	}
	opts := x509.VerifyOptions{Roots: v.Roots, Intermediates: x509.NewCertPool(), KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	if v.Now != nil {
		opts.CurrentTime = v.Now()
	}
	for _, c := range chain[1:] {
		opts.Intermediates.AddCert(c)
	}
	if _, err := chain[0].Verify(opts); err != nil {
		return classifyCertError(err)
	}
	return nil
}

// tlsWrap performs a TLS handshake with the proxy endpoint and verifies it:
// the leaf must match the server name (checked here, with Go's RFC 6125
// matching) and the verifier must trust the chain. Go's built-in verification
// is replaced, never skipped: InsecureSkipVerify only hands the decision to
// VerifyConnection, which runs on every handshake, resumed ones included.
// There is no option to accept an unverified proxy, and no plaintext fallback.
func tlsWrap(ctx context.Context, conn net.Conn, host string, o *TLSOptions, sessions tls.ClientSessionCache) (*tls.Conn, error) {
	if o == nil || o.Verifier == nil {
		return nil, newErr(CodeInternal, "TLS enabled without a certificate verifier", nil)
	}
	sni := o.ServerName
	if sni == "" {
		sni = host
	}
	cfg := &tls.Config{
		ServerName:         sni,
		MinVersion:         tls.VersionTLS12,
		InsecureSkipVerify: true, // replaced by VerifyConnection below, see the doc comment
		VerifyConnection: func(cs tls.ConnectionState) error {
			return verifyPeer(cs, sni, o.Verifier)
		},
		ClientSessionCache: sessions,
	}
	tc := tls.Client(conn, cfg)
	if err := tc.HandshakeContext(ctx); err != nil {
		return nil, classifyTLSError(err)
	}
	return tc, nil
}

// verifyPeer checks the leaf against serverName, then asks the verifier.
func verifyPeer(cs tls.ConnectionState, serverName string, v CertVerifier) error {
	if len(cs.PeerCertificates) == 0 {
		return newErr(CodeTLSUntrusted, "proxy presented no certificate", nil)
	}
	if err := cs.PeerCertificates[0].VerifyHostname(serverName); err != nil {
		return newErr(CodeTLSHostname, "proxy certificate does not match the proxy hostname", err)
	}
	if err := v.VerifyChain(cs.PeerCertificates, serverName); err != nil {
		var e *Error
		if errors.As(err, &e) {
			return e
		}
		return newErr(CodeTLSUntrusted, "proxy certificate is not trusted", err)
	}
	return nil
}

func classifyCertError(err error) error {
	var (
		hostErr    x509.HostnameError
		authErr    x509.UnknownAuthorityError
		invalidErr x509.CertificateInvalidError
	)
	switch {
	case errors.As(err, &hostErr):
		return newErr(CodeTLSHostname, "proxy certificate does not match the proxy hostname", err)
	case errors.As(err, &authErr):
		return newErr(CodeTLSUntrusted, "proxy certificate is not issued by a trusted authority", err)
	case errors.As(err, &invalidErr):
		if invalidErr.Reason == x509.Expired {
			return newErr(CodeTLSExpired, "proxy certificate is expired or not yet valid", err)
		}
		return newErr(CodeTLSUntrusted, "proxy certificate is invalid", err)
	}
	return newErr(CodeTLSUntrusted, "proxy certificate could not be verified", err)
}

func classifyTLSError(err error) error {
	var (
		e       *Error
		recErr  tls.RecordHeaderError
		alert   tls.AlertError
		netErr  net.Error
		certErr *tls.CertificateVerificationError
	)
	switch {
	case errors.As(err, &e):
		return e // from verifyPeer
	case errors.As(err, &certErr):
		return classifyCertError(certErr.Err)
	case errors.As(err, &recErr):
		return newErr(CodeTLSHandshake, "proxy did not answer with TLS (is TLS enabled on this port?)", err)
	case errors.As(err, &netErr) && netErr.Timeout():
		return newErr(CodeTimeout, "TLS handshake timed out", err)
	case errors.As(err, &alert):
		return newErr(CodeTLSHandshake, "proxy ended the TLS handshake", err)
	}
	return newErr(CodeTLSHandshake, "TLS handshake with proxy failed", err)
}
