// Package adapters implements the upstream proxy protocol adapters used by the
// ProxyFlow feasibility spike: a protected TCP dialer, optional TLS to the
// proxy, and SOCKS5 / HTTP CONNECT handshakes.
//
// SPIKE CODE (Milestone 1). It exists to prove the networking approach and is
// not the production engine. Production code is written in M4 against the
// approved ADRs.
package adapters

import (
	"errors"
	"fmt"
)

// Code is a stable, user-interface-facing error category. The same codes are
// used by the UX error-state catalogue (docs/m1/04-ux-screens-and-error-states.md)
// so engine failures map 1:1 to designed screens and log entries.
type Code string

const (
	// Proxy-level failures: they affect every connection, so the app surfaces
	// them as a global connection state.
	CodeProxyUnreachable Code = "PROXY_UNREACHABLE" // TCP connect to proxy failed / timed out
	CodeProxyDNS         Code = "PROXY_DNS_FAILED"  // proxy hostname could not be resolved
	CodeAuthRequired     Code = "AUTH_REQUIRED"     // proxy demands credentials, none configured
	CodeAuthFailed       Code = "AUTH_FAILED"       // proxy rejected the configured credentials
	CodeTLSUntrusted     Code = "TLS_UNTRUSTED"     // certificate chain not trusted
	CodeTLSHostname      Code = "TLS_HOSTNAME"      // certificate does not match proxy hostname
	CodeTLSExpired       Code = "TLS_EXPIRED"       // certificate expired / not yet valid
	CodeTLSHandshake     Code = "TLS_HANDSHAKE"     // other TLS failure (e.g. proxy does not speak TLS)
	CodeProtocol         Code = "PROTOCOL_ERROR"    // malformed / unexpected proxy response
	CodeTimeout          Code = "HANDSHAKE_TIMEOUT" // proxy accepted TCP but did not finish handshake

	// Target-level failures: the proxy works but refused or could not reach
	// one destination. Surfaced per connection in Logs, not as a global error.
	CodeTargetNotAllowed   Code = "TARGET_NOT_ALLOWED"    // SOCKS5 0x02, HTTP 403
	CodeTargetUnreachable  Code = "TARGET_UNREACHABLE"    // SOCKS5 0x03/0x04/0x06, HTTP 502/503/504
	CodeTargetRefused      Code = "TARGET_REFUSED"        // SOCKS5 0x05
	CodeTargetUnsupported  Code = "TARGET_UNSUPPORTED"    // SOCKS5 0x07/0x08, hostname too long
	CodeProxyGeneralFailed Code = "PROXY_GENERAL_FAILURE" // SOCKS5 0x01, other HTTP status

	// Engine-local failures.
	CodeStaleMapping Code = "STALE_DNS_MAPPING"    // fake IP with no hostname mapping
	CodeLimit        Code = "CONNECTION_LIMIT"     // engine connection cap reached
	CodeAppHandshake Code = "APP_HANDSHAKE_FAILED" // app-side TCP handshake did not complete
	CodeInternal     Code = "INTERNAL"
)

// IsProxyLevel reports whether the code indicates the proxy itself is unusable
// (as opposed to a single destination being refused).
func (c Code) IsProxyLevel() bool {
	switch c {
	case CodeProxyUnreachable, CodeProxyDNS, CodeAuthRequired, CodeAuthFailed,
		CodeTLSUntrusted, CodeTLSHostname, CodeTLSExpired, CodeTLSHandshake,
		CodeProtocol, CodeTimeout:
		return true
	}
	return false
}

// Error is a categorised adapter error. Detail must never contain credentials
// or payload bytes; it is safe to log.
type Error struct {
	Code   Code
	Detail string
	Err    error // underlying error, may be nil; never contains secrets
}

func (e *Error) Error() string {
	if e.Detail == "" {
		return string(e.Code)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Detail)
}

func (e *Error) Unwrap() error { return e.Err }

func newErr(code Code, detail string, cause error) *Error {
	return &Error{Code: code, Detail: detail, Err: cause}
}

// CodeOf extracts the Code from an error chain, or CodeInternal.
func CodeOf(err error) Code {
	var e *Error
	if errors.As(err, &e) {
		return e.Code
	}
	return CodeInternal
}
