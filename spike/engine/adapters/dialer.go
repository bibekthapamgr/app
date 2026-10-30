package adapters

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
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

// TLSOptions enables TLS between the device and the proxy endpoint.
// Verification is always on: there is deliberately no "insecure" switch.
type TLSOptions struct {
	// ServerName is used for SNI and certificate hostname verification.
	// Defaults to ProxyConfig.Host.
	ServerName string
	// RootCAs is the trust store. On Android the production engine must not
	// rely on Go's default roots (see ADR-0005: Go's Android root list includes
	// user-installed CAs and misses the Conscrypt APEX store); the app passes
	// the platform system trust anchors or a platform-verifier hook instead.
	RootCAs *x509.CertPool
	// VerifyConnection, if set, runs after standard verification succeeds
	// (hook for a platform X509TrustManager / pinning). It can only add checks.
	VerifyConnection func(tls.ConnectionState) error
}

// ProxyConfig describes the user's proxy. Password must never be logged:
// use String(), which redacts it.
type ProxyConfig struct {
	Kind     Kind
	Host     string // hostname or IP literal
	Port     uint16
	Username string
	Password string
	TLS      *TLSOptions

	DialTimeout      time.Duration // TCP connect to proxy (default 10s)
	HandshakeTimeout time.Duration // TLS + proxy handshake (default 10s)
}

// String returns a log-safe description (no username, no password).
func (c ProxyConfig) String() string {
	scheme := c.Kind.String()
	if c.TLS != nil {
		scheme += "+tls"
	}
	auth := "none"
	if c.Username != "" || c.Password != "" {
		auth = "configured"
	}
	return fmt.Sprintf("%s://%s (auth: %s)", scheme, net.JoinHostPort(c.Host, strconv.Itoa(int(c.Port))), auth)
}

// GoString keeps %#v from printing the password.
func (c ProxyConfig) GoString() string { return c.String() }

// Validate checks the configuration without touching the network.
func (c ProxyConfig) Validate() error {
	if c.Kind != KindSOCKS5 && c.Kind != KindHTTPConnect {
		return errors.New("unsupported proxy kind")
	}
	if c.Host == "" {
		return errors.New("proxy host is empty")
	}
	if c.Port == 0 {
		return errors.New("proxy port must be 1-65535")
	}
	if c.Kind == KindSOCKS5 && (len(c.Username) > 255 || len(c.Password) > 255) {
		return errors.New("SOCKS5 username and password are limited to 255 bytes each (RFC 1929)")
	}
	if c.Password != "" && c.Username == "" {
		return errors.New("password set without username")
	}
	if c.Kind == KindHTTPConnect && strings.Contains(c.Username, ":") {
		return errors.New("HTTP Basic usernames cannot contain ':' (RFC 7617)")
	}
	return nil
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

// Protector is called on every upstream socket before connect(). On Android it
// maps to VpnService.protect(fd) (or is a no-op when the app's own UID is
// excluded with addDisallowedApplication); in the Linux harness it sets SO_MARK
// so policy routing sends the socket to the underlying network.
type Protector func(fd uintptr) error

// Dialer opens tunnels to targets through the configured proxy.
type Dialer struct {
	cfg      ProxyConfig
	protect  Protector
	resolver *net.Resolver
}

// NewDialer validates cfg. protect may be nil (no protection, tests only).
// resolver resolves the proxy hostname on the underlying network; nil uses a
// resolver whose sockets are also protected.
func NewDialer(cfg ProxyConfig, protect Protector, resolver *net.Resolver) (*Dialer, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	if cfg.DialTimeout == 0 {
		cfg.DialTimeout = 10 * time.Second
	}
	if cfg.HandshakeTimeout == 0 {
		cfg.HandshakeTimeout = 10 * time.Second
	}
	d := &Dialer{cfg: cfg, protect: protect, resolver: resolver}
	if d.resolver == nil {
		d.resolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			return d.netDialer().DialContext(ctx, network, address)
		}}
	}
	return d, nil
}

// Config returns a copy of the configuration (callers must not log Password).
func (d *Dialer) Config() ProxyConfig { return d.cfg }

func (d *Dialer) netDialer() *net.Dialer {
	nd := &net.Dialer{Timeout: d.cfg.DialTimeout, KeepAlive: 30 * time.Second}
	if d.protect != nil {
		nd.Control = func(network, address string, c syscall.RawConn) error {
			var perr error
			if err := c.Control(func(fd uintptr) { perr = d.protect(fd) }); err != nil {
				return err
			}
			if perr != nil {
				// Fail closed: an unprotected socket would loop into the tunnel.
				return fmt.Errorf("protect socket: %w", perr)
			}
			return nil
		}
	}
	return nd
}

// connectProxy resolves (if needed) and opens a protected TCP connection to the proxy.
func (d *Dialer) connectProxy(ctx context.Context) (net.Conn, error) {
	host := d.cfg.Host
	port := strconv.Itoa(int(d.cfg.Port))
	var addrs []string
	if ip, err := netip.ParseAddr(host); err == nil {
		addrs = []string{net.JoinHostPort(ip.String(), port)}
	} else {
		rctx, cancel := context.WithTimeout(ctx, d.cfg.DialTimeout)
		ips, err := d.resolver.LookupNetIP(rctx, "ip", host)
		cancel()
		if err != nil || len(ips) == 0 {
			return nil, newErr(CodeProxyDNS, "cannot resolve proxy host", err)
		}
		for _, ip := range ips {
			addrs = append(addrs, net.JoinHostPort(ip.Unmap().String(), port))
		}
	}
	var lastErr error
	for _, a := range addrs {
		c, err := d.netDialer().DialContext(ctx, "tcp", a)
		if err == nil {
			return c, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			break
		}
	}
	return nil, newErr(CodeProxyUnreachable, "cannot connect to proxy", lastErr)
}

// Dial opens a tunnel to target: protected TCP -> [TLS] -> SOCKS5/HTTP CONNECT.
// On any failure it returns a categorised *Error and never falls back to a
// direct connection.
func (d *Dialer) Dial(ctx context.Context, target Target) (net.Conn, error) {
	if target.Port == 0 {
		return nil, newErr(CodeProtocol, "target port is zero", nil)
	}
	conn, err := d.connectProxy(ctx)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(d.cfg.HandshakeTimeout)
	if dl, ok := ctx.Deadline(); ok && dl.Before(deadline) {
		deadline = dl
	}
	_ = conn.SetDeadline(deadline)

	if d.cfg.TLS != nil {
		tconn, err := tlsWrap(ctx, conn, d.cfg.Host, d.cfg.TLS)
		if err != nil {
			conn.Close()
			return nil, err
		}
		conn = tconn
	}

	// tunnel may wrap conn (buffered bytes after CONNECT); conn is always the
	// socket to close on failure. (Regression: assigning the handshake result
	// to conn left it nil on failure and crashed the engine — found by the
	// end-to-end Squid 403 test, see TestDialHTTPConnectRefusalNoPanic.)
	tunnel := conn
	switch d.cfg.Kind {
	case KindSOCKS5:
		err = socks5Handshake(conn, d.cfg.Username, d.cfg.Password, target)
	case KindHTTPConnect:
		tunnel, err = httpConnectHandshake(conn, d.cfg.Username, d.cfg.Password, target)
	}
	if err != nil {
		conn.Close()
		return nil, classifyIOError(err)
	}
	_ = conn.SetDeadline(time.Time{})
	return tunnel, nil
}

// classifyIOError maps raw I/O errors during a handshake to categories.
func classifyIOError(err error) error {
	var e *Error
	if errors.As(err, &e) {
		return err
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return newErr(CodeTimeout, "proxy did not complete handshake in time", err)
	}
	return newErr(CodeProtocol, "handshake I/O failure", err)
}
