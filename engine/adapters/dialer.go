package adapters

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Kind is the upstream proxy protocol.
type Kind int

const (
	KindSOCKS5 Kind = iota + 1
	KindHTTPConnect
)

func (k Kind) String() string {
	switch k {
	case KindSOCKS5:
		return "socks5"
	case KindHTTPConnect:
		return "http"
	}
	return "unknown"
}

// ParseKind maps the app's protocol names (ProxyProtocol.SOCKS5 and HTTP) to a
// Kind, ignoring case.
func ParseKind(s string) (Kind, error) {
	switch strings.ToLower(s) {
	case "socks5":
		return KindSOCKS5, nil
	case "http":
		return KindHTTPConnect, nil
	}
	return 0, fmt.Errorf("unsupported proxy protocol %q", s)
}

// Timeouts, matching docs/m1/06-architecture.md §5.
const (
	DefaultDialTimeout      = 10 * time.Second // TCP connect to the proxy, all its addresses together
	DefaultHandshakeTimeout = 10 * time.Second // TLS and the SOCKS5 / HTTP CONNECT exchange
	maxProxyAddrs           = 4                // addresses of the proxy host tried per dial
)

// ProxyConfig describes the user's proxy endpoint. It holds no secrets: the
// username and password are passed to NewDialer as *Credentials, so a config
// can be logged or printed freely.
type ProxyConfig struct {
	Kind Kind
	Host string // hostname or IP literal, without brackets
	Port uint16
	TLS  *TLSOptions // nil: plain TCP to the proxy

	DialTimeout      time.Duration // 0 means DefaultDialTimeout
	HandshakeTimeout time.Duration // 0 means DefaultHandshakeTimeout
}

// String returns a log-safe description such as "socks5+tls://proxy.example:1080".
func (c ProxyConfig) String() string {
	scheme := c.Kind.String()
	if c.TLS != nil {
		scheme += "+tls"
	}
	return scheme + "://" + net.JoinHostPort(c.Host, strconv.Itoa(int(c.Port)))
}

// Validate checks the configuration without touching the network.
func (c ProxyConfig) Validate() error {
	if c.Kind != KindSOCKS5 && c.Kind != KindHTTPConnect {
		return errors.New("unsupported proxy kind")
	}
	if !validProxyHost(c.Host) {
		return errors.New("proxy host is not a hostname or IP address")
	}
	if c.Port == 0 {
		return errors.New("proxy port must be 1-65535")
	}
	if c.TLS != nil {
		if c.TLS.Verifier == nil {
			return errors.New("TLS needs a certificate verifier")
		}
		if c.TLS.ServerName != "" && !ValidHostname(normalizeName(c.TLS.ServerName)) {
			return errors.New("TLS server name is not a hostname")
		}
	}
	if c.DialTimeout < 0 || c.HandshakeTimeout < 0 {
		return errors.New("timeouts must not be negative")
	}
	return nil
}

// normalizeName lower-cases a hostname and drops one trailing dot, the forms
// the app's validator accepts (ProfileValidator.isValidHostname).
func normalizeName(s string) string {
	return strings.TrimSuffix(strings.ToLower(s), ".")
}

func validProxyHost(h string) bool {
	if a, err := netip.ParseAddr(h); err == nil {
		return a.Zone() == ""
	}
	return ValidHostname(normalizeName(h))
}

// Target is the destination requested by an app inside the tunnel.
type Target struct {
	Host string     // hostname from the fake-DNS mapping; empty when the app used an IP literal
	Addr netip.Addr // destination IP as seen in the packet
	Port uint16
}

// HostPort renders the target for CONNECT requests: "name:port", "1.2.3.4:port" or "[v6]:port".
func (t Target) HostPort() string {
	h := t.Host
	if h == "" {
		h = t.Addr.Unmap().String()
	}
	return net.JoinHostPort(h, strconv.Itoa(int(t.Port)))
}

// Protector is called on every socket the engine opens to the outside world
// (the proxy, and the resolver when Go resolves) before connect(). On Android
// it is VpnService.protect(fd), as a second guard next to the app's own
// exclusion from the VPN. An error fails the dial: an unprotected socket could
// loop back into the tunnel.
type Protector func(fd uintptr) error

// Resolver resolves the proxy's own hostname on the underlying network, never
// through the tunnel. On Android it is Network.getAllByName on the network the
// VPN runs over (ADR-0003 step 5); it is the only DNS that leaves the phone.
type Resolver func(ctx context.Context, host string) ([]netip.Addr, error)

// errProtect marks a dial that failed because the socket could not be protected.
var errProtect = errors.New("socket protection failed")

// aLongTimeAgo is a deadline in the past: setting it aborts blocked I/O.
var aLongTimeAgo = time.Unix(1, 0)

// Dialer opens tunnels to destinations through one proxy. It is safe for
// concurrent use; every Dial opens its own connection to the proxy.
type Dialer struct {
	cfg      ProxyConfig
	creds    *Credentials
	protect  Protector
	resolve  Resolver
	sessions tls.ClientSessionCache
	addrs    addrCache
	now      func() time.Time // tests
}

// Proxy host lookups are cached: every tunnel connects to the proxy, and a
// burst of app connections must not become a burst of lookups. Concurrent
// dials share one lookup. Failures are cached briefly, so a burst fails fast.
const (
	proxyAddrTTL         = 60 * time.Second
	proxyAddrNegativeTTL = 5 * time.Second
)

type addrCache struct {
	mu      sync.Mutex
	addrs   []netip.Addr
	err     error
	expires time.Time
	gen     uint64    // bumped by ForgetProxyAddrs; older lookups are not cached
	call    *addrCall // the lookup in flight
}

type addrCall struct {
	done     chan struct{}
	addrs    []netip.Addr
	err      error
	canceled bool // the dial that ran the lookup ended first; waiters retry
}

// NewDialer validates cfg and creds. creds may be nil when the proxy needs no
// sign-in; the Dialer keeps the pointer (every dial signs in again), so
// creds.Wipe() makes later dials fail with CANCELED. protect may be nil only
// in tests. resolve may be nil: Go's resolver then runs over protected sockets
// (desktop and tests; Android has no resolv.conf for it).
func NewDialer(cfg ProxyConfig, creds *Credentials, protect Protector, resolve Resolver) (*Dialer, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	if err := validateCredentials(cfg.Kind, creds); err != nil {
		return nil, err
	}
	if _, err := netip.ParseAddr(cfg.Host); err != nil {
		cfg.Host = normalizeName(cfg.Host)
	}
	if cfg.TLS != nil {
		t := *cfg.TLS
		t.ServerName = normalizeName(t.ServerName)
		cfg.TLS = &t
	}
	if cfg.DialTimeout == 0 {
		cfg.DialTimeout = DefaultDialTimeout
	}
	if cfg.HandshakeTimeout == 0 {
		cfg.HandshakeTimeout = DefaultHandshakeTimeout
	}
	d := &Dialer{cfg: cfg, creds: creds, protect: protect, resolve: resolve, now: time.Now}
	if cfg.TLS != nil {
		// Resumed sessions are verified again (VerifyConnection runs on every
		// handshake); the cache only saves round trips and CPU per connection.
		d.sessions = tls.NewLRUClientSessionCache(64)
	}
	if d.resolve == nil {
		d.resolve = d.goResolver()
	}
	return d, nil
}

// validateCredentials applies the limits the app's form validation also
// enforces (ProfileValidator): RFC 1929 for SOCKS5, RFC 7617 for HTTP Basic.
func validateCredentials(kind Kind, creds *Credentials) error {
	if creds == nil {
		return nil
	}
	err := creds.use(func(user, pass []byte) error {
		if len(user) == 0 {
			if len(pass) > 0 {
				return errors.New("password set without username")
			}
			return nil
		}
		limit := MaxSOCKS5CredentialBytes
		if kind == KindHTTPConnect {
			limit = MaxHTTPCredentialBytes
		}
		if len(user) > limit || len(pass) > limit {
			return fmt.Errorf("username and password are limited to %d bytes each", limit)
		}
		if kind == KindHTTPConnect && bytes.IndexByte(user, ':') >= 0 {
			return errors.New("HTTP Basic usernames cannot contain ':' (RFC 7617)")
		}
		return nil
	})
	if errors.Is(err, errWiped) {
		return errors.New("credentials were already wiped")
	}
	return err
}

// Config returns the normalised configuration (it holds no secrets).
func (d *Dialer) Config() ProxyConfig { return d.cfg }

// String is log-safe: it says whether sign-in is on, never with what.
func (d *Dialer) String() string {
	signIn := "off"
	if d.creds.Present() {
		signIn = "on"
	}
	return d.cfg.String() + " (sign-in " + signIn + ")"
}

func (d *Dialer) netDialer() *net.Dialer {
	nd := &net.Dialer{KeepAlive: 30 * time.Second}
	if d.protect != nil {
		nd.Control = func(_, _ string, c syscall.RawConn) error {
			var perr error
			if err := c.Control(func(fd uintptr) { perr = d.protect(fd) }); err != nil {
				return fmt.Errorf("%w: %v", errProtect, err)
			}
			if perr != nil {
				return fmt.Errorf("%w: %v", errProtect, perr)
			}
			return nil
		}
	}
	return nd
}

func (d *Dialer) goResolver() Resolver {
	r := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
		return d.netDialer().DialContext(ctx, network, address)
	}}
	return func(ctx context.Context, host string) ([]netip.Addr, error) {
		return r.LookupNetIP(ctx, "ip", host)
	}
}

// Dial opens a tunnel to target: protected TCP to the proxy, then TLS when
// configured, then the SOCKS5 or HTTP CONNECT handshake. Every failure is a
// categorised *Error; there is no fallback to a direct connection. When ctx
// ends, a dial in progress stops at once: CANCELED if ctx was canceled,
// PROXY_UNREACHABLE or HANDSHAKE_TIMEOUT if its deadline passed.
func (d *Dialer) Dial(ctx context.Context, target Target) (net.Conn, error) {
	if target.Port == 0 {
		return nil, newErr(CodeTargetUnsupported, "destination port 0", nil)
	}
	if target.Host == "" && !target.Addr.IsValid() {
		return nil, newErr(CodeInternal, "destination has neither hostname nor address", nil)
	}
	if ctx.Err() != nil {
		return nil, connectCtxErr(ctx)
	}
	raw, err := d.connectProxy(ctx)
	if err != nil {
		return nil, err
	}
	tunnel, err := d.handshake(ctx, raw, target)
	if err != nil {
		raw.Close()
		return nil, err
	}
	return tunnel, nil
}

// connectProxy resolves the proxy host if needed and opens a protected TCP
// connection, trying up to maxProxyAddrs addresses within DialTimeout.
func (d *Dialer) connectProxy(ctx context.Context) (net.Conn, error) {
	cctx, cancel := context.WithTimeout(ctx, d.cfg.DialTimeout)
	defer cancel()
	addrs, err := d.proxyAddrs(cctx)
	if err != nil {
		if ctx.Err() != nil {
			return nil, connectCtxErr(ctx)
		}
		return nil, err
	}
	deadline, _ := cctx.Deadline()
	var lastErr error
	for i, a := range addrs {
		remaining := time.Until(deadline)
		if remaining <= 0 {
			break
		}
		actx, acancel := context.WithTimeout(cctx, partialTimeout(remaining, len(addrs)-i))
		c, err := d.netDialer().DialContext(actx, "tcp", netip.AddrPortFrom(a, d.cfg.Port).String())
		acancel()
		if err == nil {
			if i > 0 {
				d.preferAddr(a)
			}
			return c, nil
		}
		lastErr = err
		if ctx.Err() != nil || errors.Is(err, errProtect) {
			break
		}
	}
	if ctx.Err() != nil {
		return nil, connectCtxErr(ctx)
	}
	return nil, classifyConnectError(lastErr)
}

// proxyAddrs returns the proxy's addresses: the configured IP, or the cached
// or shared result of looking up its name.
func (d *Dialer) proxyAddrs(ctx context.Context) ([]netip.Addr, error) {
	if a, err := netip.ParseAddr(d.cfg.Host); err == nil {
		return []netip.Addr{a.Unmap()}, nil
	}
	c := &d.addrs
	for {
		c.mu.Lock()
		if d.now().Before(c.expires) {
			addrs, err := c.addrs, c.err
			c.mu.Unlock()
			return addrs, err
		}
		if call := c.call; call != nil {
			c.mu.Unlock()
			select {
			case <-call.done:
				if call.canceled {
					continue // the dial that looked up gave up; look up for this one
				}
				return call.addrs, call.err
			case <-ctx.Done():
				return nil, newErr(CodeProxyDNS, "looking up the proxy host timed out", ctx.Err())
			}
		}
		call := &addrCall{done: make(chan struct{})}
		c.call = call
		gen := c.gen
		c.mu.Unlock()
		return d.lead(ctx, call, gen)
	}
}

// lead runs the lookup that concurrent dials share and publishes its result,
// also if the lookup panics (then the waiters look up for themselves).
func (d *Dialer) lead(ctx context.Context, call *addrCall, gen uint64) (addrs []netip.Addr, err error) {
	c := &d.addrs
	defer func() {
		c.mu.Lock()
		defer c.mu.Unlock()
		call.addrs, call.err = addrs, err
		// A lookup that ended with its own dial, or never returned, is no
		// answer for anyone else.
		call.canceled = (err != nil && ctx.Err() != nil) || (err == nil && len(addrs) == 0)
		if c.call == call {
			c.call = nil
		}
		if gen == c.gen && !call.canceled {
			c.addrs, c.err = addrs, err
			ttl := proxyAddrTTL
			if err != nil {
				ttl = proxyAddrNegativeTTL
			}
			c.expires = d.now().Add(ttl)
		}
		close(call.done)
	}()
	return d.lookupProxy(ctx)
}

// ForgetProxyAddrs drops the cached proxy addresses, for a change of the
// underlying network: the next dial looks the proxy host up again.
func (d *Dialer) ForgetProxyAddrs() {
	c := &d.addrs
	c.mu.Lock()
	defer c.mu.Unlock()
	c.gen++
	c.addrs, c.err, c.expires = nil, nil, time.Time{}
}

// preferAddr moves a reachable address to the front of the cached list, so
// later dials do not wait for the unreachable ones first.
func (d *Dialer) preferAddr(a netip.Addr) {
	c := &d.addrs
	c.mu.Lock()
	defer c.mu.Unlock()
	for i, x := range c.addrs {
		if x == a && i > 0 {
			addrs := make([]netip.Addr, 0, len(c.addrs))
			addrs = append(addrs, a)
			addrs = append(addrs, c.addrs[:i]...)
			c.addrs = append(addrs, c.addrs[i+1:]...)
			return
		}
	}
}

func (d *Dialer) lookupProxy(ctx context.Context) ([]netip.Addr, error) {
	ips, err := d.resolve(ctx, d.cfg.Host)
	if err != nil {
		if ctx.Err() != nil {
			return nil, newErr(CodeProxyDNS, "looking up the proxy host timed out", err)
		}
		return nil, newErr(CodeProxyDNS, "the proxy host could not be resolved", err)
	}
	var out []netip.Addr
	seen := map[netip.Addr]bool{}
	for _, ip := range ips {
		ip = ip.Unmap()
		if !ip.IsValid() || ip.IsUnspecified() || ip.IsMulticast() || seen[ip] {
			continue
		}
		seen[ip] = true
		out = append(out, ip.WithZone(""))
		if len(out) == maxProxyAddrs {
			break
		}
	}
	if len(out) == 0 {
		return nil, newErr(CodeProxyDNS, "the proxy host has no usable address", nil)
	}
	return out, nil
}

// partialTimeout splits the time left between the addresses left, like the
// standard library's dialer, with a 2 s floor so a slow first address cannot
// starve the others.
func partialTimeout(remaining time.Duration, addrsLeft int) time.Duration {
	t := remaining / time.Duration(addrsLeft)
	const saneMinimum = 2 * time.Second
	if t < saneMinimum {
		if remaining < saneMinimum {
			return remaining
		}
		return saneMinimum
	}
	return t
}

// handshake runs TLS (if configured) and the proxy protocol on raw, bounded by
// HandshakeTimeout and interrupted at once when ctx ends.
func (d *Dialer) handshake(ctx context.Context, raw net.Conn, target Target) (net.Conn, error) {
	deadline := time.Now().Add(d.cfg.HandshakeTimeout)
	if dl, ok := ctx.Deadline(); ok && dl.Before(deadline) {
		deadline = dl
	}
	if err := raw.SetDeadline(deadline); err != nil {
		return nil, newErr(CodeProxyUnreachable, "the proxy connection closed", err)
	}
	stop := context.AfterFunc(ctx, func() { _ = raw.SetDeadline(aLongTimeAgo) })

	var conn net.Conn = raw
	var err error
	if d.cfg.TLS != nil {
		var tc *tls.Conn
		if tc, err = tlsWrap(ctx, raw, d.cfg.Host, d.cfg.TLS, d.sessions); err == nil {
			conn = tc
		}
	}
	tunnel := conn
	if err == nil {
		switch d.cfg.Kind {
		case KindSOCKS5:
			err = socks5Handshake(conn, d.creds, target)
		case KindHTTPConnect:
			tunnel, err = httpConnectHandshake(conn, d.creds, target)
		}
	}
	if !stop() {
		// ctx ended while the handshake ran; the connection's deadline may
		// already be in the past, so it must not be used even if err is nil.
		return nil, handshakeCtxErr(ctx)
	}
	if err != nil {
		return nil, classifyHandshakeError(ctx, err)
	}
	if err := raw.SetDeadline(time.Time{}); err != nil {
		return nil, newErr(CodeProtocol, "the proxy closed the connection", err)
	}
	if tc, ok := conn.(*tls.Conn); ok && tc.ConnectionState().Version < tls.VersionTLS13 {
		tunnel = noHalfClose{tunnel}
	}
	return tunnel, nil
}

// noHalfClose hides CloseWrite. TLS 1.2 cannot half-close: close_notify ends
// the whole connection (RFC 5246 §7.2.1), so passing an app's half-close on as
// one would make the proxy drop the rest of the reply. Over TLS 1.2 the upload
// direction just stops. TLS 1.3's close_notify closes only the writing side
// (RFC 8446 §6.1), so it is passed on.
type noHalfClose struct{ net.Conn }

func (noHalfClose) CloseWrite() error { return nil }

// connectCtxErr reports why ctx ended while connecting to the proxy.
func connectCtxErr(ctx context.Context) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return newErr(CodeProxyUnreachable, "timed out connecting to the proxy", nil)
	}
	return newErr(CodeCanceled, "canceled while connecting to the proxy", nil)
}

// handshakeCtxErr reports why ctx ended during the proxy handshake.
func handshakeCtxErr(ctx context.Context) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return newErr(CodeTimeout, "the proxy did not complete the handshake in time", nil)
	}
	return newErr(CodeCanceled, "canceled during the proxy handshake", nil)
}

func classifyConnectError(err error) error {
	var ne net.Error
	switch {
	case err == nil:
		return newErr(CodeProxyUnreachable, "timed out connecting to the proxy", nil)
	case errors.Is(err, errProtect):
		return newErr(CodeProxyUnreachable, "the proxy connection could not be kept out of the VPN (protect failed)", err)
	case errors.Is(err, syscall.ECONNREFUSED):
		return newErr(CodeProxyUnreachable, "the proxy refused the connection (nothing listening on that port?)", err)
	case errors.Is(err, context.DeadlineExceeded), errors.As(err, &ne) && ne.Timeout():
		return newErr(CodeProxyUnreachable, "timed out connecting to the proxy", err)
	case errors.Is(err, syscall.ENETUNREACH), errors.Is(err, syscall.EHOSTUNREACH):
		return newErr(CodeProxyUnreachable, "no route to the proxy", err)
	}
	return newErr(CodeProxyUnreachable, "cannot connect to the proxy", err)
}

// classifyHandshakeError maps raw I/O errors during a handshake to categories.
func classifyHandshakeError(ctx context.Context, err error) error {
	if ctx.Err() != nil {
		return handshakeCtxErr(ctx)
	}
	var e *Error
	if errors.As(err, &e) {
		return e
	}
	var ne net.Error
	switch {
	case errors.As(err, &ne) && ne.Timeout():
		return newErr(CodeTimeout, "the proxy did not complete the handshake in time", err)
	case errors.Is(err, io.EOF), errors.Is(err, io.ErrUnexpectedEOF):
		return newErr(CodeProtocol, "the proxy closed the connection during the handshake", err)
	case errors.Is(err, syscall.ECONNRESET), errors.Is(err, syscall.EPIPE):
		return newErr(CodeProtocol, "the proxy reset the connection during the handshake", err)
	}
	return newErr(CodeProtocol, "handshake I/O failure", err)
}
