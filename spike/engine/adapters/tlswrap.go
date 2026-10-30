package adapters

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
)

// tlsWrap performs a strictly verified TLS handshake with the proxy endpoint.
// There is no option to skip verification: a failure always closes the tunnel
// attempt (fail closed) and is reported with a specific category so the UI
// can explain it (untrusted / hostname mismatch / expired / not TLS).
func tlsWrap(ctx context.Context, conn net.Conn, host string, o *TLSOptions) (net.Conn, error) {
	sni := o.ServerName
	if sni == "" {
		sni = host
	}
	cfg := &tls.Config{
		ServerName:       sni,
		RootCAs:          o.RootCAs,
		MinVersion:       tls.VersionTLS12,
		VerifyConnection: o.VerifyConnection, // runs only after standard verification passed
	}
	tc := tls.Client(conn, cfg)
	if err := tc.HandshakeContext(ctx); err != nil {
		return nil, classifyTLSError(err)
	}
	return tc, nil
}

func classifyTLSError(err error) error {
	var (
		hostErr    x509.HostnameError
		authErr    x509.UnknownAuthorityError
		invalidErr x509.CertificateInvalidError
		recErr     tls.RecordHeaderError
		netErr     net.Error
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
	case errors.As(err, &recErr):
		return newErr(CodeTLSHandshake, "proxy did not answer with TLS (is TLS enabled on this port?)", err)
	case errors.As(err, &netErr) && netErr.Timeout():
		return newErr(CodeTimeout, "TLS handshake timed out", err)
	}
	return newErr(CodeTLSHandshake, "TLS handshake with proxy failed", err)
}
