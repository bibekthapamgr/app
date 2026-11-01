// Package adapters opens tunnels to destinations through the user's proxy: a
// protected TCP connection to the proxy, optional TLS to the proxy, then a
// SOCKS5 (RFC 1928/1929) or HTTP CONNECT (RFC 9110, Basic auth RFC 7617)
// handshake. Every failure is a categorised *Error; there is no fallback to a
// direct connection (ADR-0002).
//
// Secrets: the username and password live in Credentials as bytes, are copied
// into short-lived buffers only to be written to the proxy, and those buffers
// are zeroed right after. Nothing in this package formats them into errors,
// logs or strings.
package adapters

import (
	"errors"
	"fmt"
)

// Code is a stable error category shared with the app's error catalogue
// (docs/m1/04-ux-screens-and-error-states.md §4), so engine failures map 1:1 to
// designed screens and log entries. Codes are part of the engine API: never
// rename one, only add.
type Code string

const (
	// Proxy-level failures: they affect every connection, so the app surfaces
	// them as a global connection state.
	CodeProxyUnreachable Code = "PROXY_UNREACHABLE" // TCP connect to proxy failed / timed out
	CodeProxyDNS         Code = "PROXY_DNS_FAILED"  // proxy hostname could not be resolved
	CodeAuthRequired     Code = "AUTH_REQUIRED"     // proxy demands credentials, none configured
	CodeAuthFailed       Code = "AUTH_FAILED"       // proxy rejected the configured credentials
	CodeTLSUntrusted     Code = "TLS_UNTRUSTED"     // certificate chain not trusted
	CodeTLSHostname      Code = "TLS_HOSTNAME"      // certificate does not match the proxy hostname
	CodeTLSExpired       Code = "TLS_EXPIRED"       // certificate expired / not yet valid
	CodeTLSHandshake     Code = "TLS_HANDSHAKE"     // other TLS failure (e.g. proxy does not speak TLS)
	CodeProtocol         Code = "PROTOCOL_ERROR"    // malformed / unexpected proxy response
	CodeTimeout          Code = "HANDSHAKE_TIMEOUT" // proxy accepted TCP but did not finish the handshake

	// Target-level failures: the proxy works but refused or could not reach
	// one destination. Surfaced per connection in Logs, not as a global error.
	CodeTargetNotAllowed   Code = "TARGET_NOT_ALLOWED"    // SOCKS5 0x02, HTTP 403
	CodeTargetUnreachable  Code = "TARGET_UNREACHABLE"    // SOCKS5 0x03/0x04/0x06, HTTP 502/503/504
	CodeTargetRefused      Code = "TARGET_REFUSED"        // SOCKS5 0x05
	CodeTargetUnsupported  Code = "TARGET_UNSUPPORTED"    // SOCKS5 0x07/0x08, invalid or over-long hostname
	CodeProxyGeneralFailed Code = "PROXY_GENERAL_FAILURE" // SOCKS5 0x01, other HTTP status

	// Engine-local outcomes.
	CodeStaleMapping Code = "STALE_DNS_MAPPING"    // fake IP with no hostname mapping
	CodeLimit        Code = "CONNECTION_LIMIT"     // engine connection cap reached
	CodeAppHandshake Code = "APP_HANDSHAKE_FAILED" // app-side TCP handshake did not complete
	CodeCanceled     Code = "CANCELED"             // the engine stopped while the tunnel was being set up
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

// Error is a categorised adapter error. Detail never contains credentials or
// payload bytes and is safe to log. Err is the underlying cause, kept for
// errors.Is/As; it never contains secrets either (handshake buffers are not
// wrapped into errors).
type Error struct {
	Code   Code
	Detail string
	Err    error
	// timeout marks a PROXY_UNREACHABLE that is a timeout (see IsTimeout).
	timeout bool
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

// newTimeoutErr is a PROXY_UNREACHABLE that IsTimeout recognises.
func newTimeoutErr(detail string, cause error) *Error {
	return &Error{Code: CodeProxyUnreachable, Detail: detail, Err: cause, timeout: true}
}

// IsTimeout reports whether err is a timeout of the proxy itself: connecting to
// it took too long (PROXY_UNREACHABLE) or it did not finish its handshake — TLS,
// SOCKS5 or HTTP CONNECT — in time (HANDSHAKE_TIMEOUT). The app's diagnostics
// count these as proxy timeouts. A slow lookup of the proxy's own name is a
// DNS failure (PROXY_DNS_FAILED), not one of these.
func IsTimeout(err error) bool {
	var e *Error
	if !errors.As(err, &e) {
		return false
	}
	return e.Code == CodeTimeout || e.timeout
}

// CodeOf extracts the Code from an error chain, or CodeInternal.
func CodeOf(err error) Code {
	var e *Error
	if errors.As(err, &e) {
		return e.Code
	}
	return CodeInternal
}

// DetailOf returns the log-safe detail of a categorised error, or "".
func DetailOf(err error) string {
	var e *Error
	if errors.As(err, &e) {
		return e.Detail
	}
	return ""
}
