package adapters

import (
	"errors"
	"fmt"
	"io"
	"sync"
	"unicode/utf8"
)

// Credentials is a proxy username and password. Both are kept as bytes, never
// as Go strings (which cannot be wiped), are never printed by any fmt verb or
// encoder, and are zeroed by Wipe. The engine keeps them for the session,
// because every new app connection signs in to the proxy again (threat I-12).
type Credentials struct {
	mu       sync.RWMutex
	username []byte
	password []byte
	wiped    bool
}

// Credential limits shared with the app's form validation.
const (
	MaxSOCKS5CredentialBytes = 255  // RFC 1929
	MaxHTTPCredentialBytes   = 1024 // keeps the Proxy-Authorization header under 3 KB
	credentialsFormatVersion = 1
)

var errWiped = errors.New("credentials were wiped")

// NewCredentials copies username and password; the caller may wipe its own
// copies. A nil or empty username means "no sign-in".
func NewCredentials(username, password []byte) *Credentials {
	return &Credentials{username: append([]byte(nil), username...), password: append([]byte(nil), password...)}
}

// ParseCredentials decodes the app's credential encoding (CredentialCodec in
// core/data): a version byte (1), then the username and the password, each as
// a 2-byte big-endian length followed by UTF-8 bytes. The result holds copies;
// the caller wipes encoded. An empty input means "no sign-in".
func ParseCredentials(encoded []byte) (*Credentials, error) {
	if len(encoded) == 0 {
		return NewCredentials(nil, nil), nil
	}
	if encoded[0] != credentialsFormatVersion {
		return nil, errors.New("unknown credential encoding")
	}
	field := func(at int) ([]byte, int, error) {
		if at+2 > len(encoded) {
			return nil, 0, errors.New("truncated credentials")
		}
		n := int(encoded[at])<<8 | int(encoded[at+1])
		if at+2+n > len(encoded) {
			return nil, 0, errors.New("truncated credentials")
		}
		return encoded[at+2 : at+2+n], at + 2 + n, nil
	}
	user, next, err := field(1)
	if err != nil {
		return nil, err
	}
	pass, end, err := field(next)
	if err != nil {
		return nil, err
	}
	if end != len(encoded) {
		return nil, errors.New("trailing bytes after credentials")
	}
	if !utf8.Valid(user) || !utf8.Valid(pass) {
		return nil, errors.New("credentials are not valid UTF-8")
	}
	return NewCredentials(user, pass), nil
}

// Present reports whether a username is configured (sign-in on).
func (c *Credentials) Present() bool {
	if c == nil {
		return false
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	return !c.wiped && len(c.username) > 0
}

// use runs f with the secret bytes while holding the read lock. f must not
// keep, copy into long-lived memory, or format the slices.
func (c *Credentials) use(f func(user, pass []byte) error) error {
	if c == nil {
		return errWiped
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	if c.wiped {
		return errWiped
	}
	return f(c.username, c.password)
}

// Wipe zeroes both secrets. It waits for handshakes using them to finish;
// later handshakes fail with CANCELED. Calling it again does nothing.
func (c *Credentials) Wipe() {
	if c == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	wipe(c.username)
	wipe(c.password)
	c.username, c.password, c.wiped = nil, nil, true
}

// String, GoString and Format keep every fmt verb from printing the secrets.
func (c *Credentials) String() string   { return "<redacted>" }
func (c *Credentials) GoString() string { return "<redacted>" }

// Format implements fmt.Formatter for all verbs (%v, %+v, %#v, %s, %q, %x...).
func (c *Credentials) Format(f fmt.State, _ rune) { _, _ = io.WriteString(f, "<redacted>") }

// MarshalText keeps encoders (JSON, XML, text) from serialising the secrets.
func (c *Credentials) MarshalText() ([]byte, error) { return []byte("<redacted>"), nil }

// wipe zeroes b.
func wipe(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
