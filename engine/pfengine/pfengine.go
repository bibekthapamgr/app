// Package pfengine is the engine's API for the Android app, exported to
// Kotlin with gomobile bind (Java package app.proxyflow.engine.go.pfengine).
// It keeps to types gomobile supports: numbers, bool, string, []byte, error
// and the Platform callback interface.
//
// Secrets cross the boundary once, as the bytes of the app's credential
// encoding (CredentialCodec), never inside JSON. The engine copies them into
// adapters.Credentials, zeroes the bytes it was given, and zeroes its own copy
// when the engine stops. Nothing this package returns or logs contains them.
package pfengine

import (
	"context"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"github.com/bibekthapamgr/app/engine/adapters"
	"github.com/bibekthapamgr/app/engine/fakedns"
	"github.com/bibekthapamgr/app/engine/tunnel"
)

// Platform is implemented by the app (Kotlin). gomobile may call its methods
// from any goroutine, concurrently.
type Platform interface {
	// Protect keeps a socket out of the VPN (VpnService.protect). False fails
	// the connection: an unprotected socket could loop back into the tunnel.
	Protect(fd int64) bool
	// ResolveProxyHost resolves the proxy's hostname on the underlying network
	// (Network.getAllByName) and returns the addresses, comma-separated.
	ResolveProxyHost(host string) (string, error)
	// VerifyChain checks the proxy's certificate chain with the platform
	// X509TrustManager. derChain is each certificate as a 4-byte big-endian
	// length then its DER bytes, leaf first. It returns "" when trusted, else
	// "TLS_UNTRUSTED" or "TLS_EXPIRED", optionally followed by ": detail".
	// The hostname has already been checked by the engine.
	VerifyChain(derChain []byte, host string) string
	// OnEvent receives one log-safe event as JSON. It runs on one engine
	// goroutine; a slow receiver loses events, never traffic.
	OnEvent(json string)
}

// Network plan shared with the VpnService builder (docs/m1/06-architecture.md §4).
const (
	TunAddress4  = "198.18.0.1"
	TunAddress6  = "fd00:66::1"
	DNSServer4   = "198.18.0.2"
	FakeIPPrefix = "198.18.0.0/15"
	fakeReserved = 256 // 198.18.0.0-198.18.0.255: TUN, DNS and spare addresses
	fakeDNSTTL   = 5   // seconds (ADR-0003)
)

// config is the profile part of the JSON passed to Start, Probe and SelfTest
// (snake_case keys; unknown keys are rejected). It never contains the
// username or password.
type config struct {
	Protocol           string `json:"protocol"` // "SOCKS5" or "HTTP"
	Host               string `json:"host"`
	Port               int    `json:"port"`
	TLS                bool   `json:"tls"`
	TLSServerName      string `json:"tls_server_name,omitempty"`
	MTU                int    `json:"mtu,omitempty"`
	LogDestinations    bool   `json:"log_destinations,omitempty"`
	DialTimeoutMs      int64  `json:"dial_timeout_ms,omitempty"`
	HandshakeTimeoutMs int64  `json:"handshake_timeout_ms,omitempty"`
	IdleTimeoutMs      int64  `json:"idle_timeout_ms,omitempty"`
	MaxConns           int    `json:"max_conns,omitempty"`
}

func parseConfig(configJSON string) (config, error) {
	var c config
	dec := json.NewDecoder(strings.NewReader(configJSON))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&c); err != nil {
		return c, fmt.Errorf("invalid engine configuration: %v", err)
	}
	if c.Port < 1 || c.Port > 65535 {
		return c, errors.New("invalid engine configuration: port must be 1-65535")
	}
	return c, nil
}

// session is one running engine with what it owns.
type session struct {
	engine *tunnel.Engine
	creds  *adapters.Credentials
}

var (
	lifeMu  sync.Mutex // serialises Start, Stop and ResetFlows
	stateMu sync.Mutex // guards current
	current *session
)

func getCurrent() *session {
	stateMu.Lock()
	defer stateMu.Unlock()
	return current
}

// Start runs the engine on the TUN descriptor. configJSON is a config; secret
// is the CredentialCodec encoding (empty: no sign-in) and is zeroed before
// Start returns. The descriptor stays owned by the caller: Stop never closes
// it. Errors read "CODE: detail" and are log-safe.
func Start(tunFD int64, configJSON string, secret []byte, p Platform) error {
	defer wipe(secret)
	lifeMu.Lock()
	defer lifeMu.Unlock()
	if s := getCurrent(); s != nil {
		if st := s.engine.State(); st == tunnel.StateRunning {
			return codeErr(adapters.CodeInternal, "the engine is already running")
		}
		stopSession(s)
	}
	if p == nil {
		return codeErr(adapters.CodeInternal, "no platform callbacks")
	}
	if tunFD < 0 || tunFD > 1<<30 {
		return codeErr(adapters.CodeInternal, "invalid TUN descriptor")
	}
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return codeErr(adapters.CodeInternal, err.Error())
	}
	dialer, creds, err := newDialer(cfg, secret, p)
	if err != nil {
		return codeErr(adapters.CodeInternal, err.Error())
	}
	engine, err := newEngine(int(tunFD), cfg, dialer, p)
	if err != nil {
		creds.Wipe()
		return codeErr(adapters.CodeInternal, err.Error())
	}
	if err := engine.Start(); err != nil {
		creds.Wipe()
		return codeErr(adapters.CodeInternal, err.Error())
	}
	stateMu.Lock()
	current = &session{engine: engine, creds: creds}
	stateMu.Unlock()
	return nil
}

// Stop ends every flow, stops the engine, waits for its goroutines and wipes
// the credentials. Safe to call at any time and more than once.
func Stop() {
	lifeMu.Lock()
	defer lifeMu.Unlock()
	if s := getCurrent(); s != nil {
		stopSession(s)
	}
}

func stopSession(s *session) {
	s.engine.Stop()
	s.creds.Wipe()
	stateMu.Lock()
	if current == s {
		current = nil
	}
	stateMu.Unlock()
}

// ResetFlows aborts every connection (apps reconnect), keeping the engine
// running; for underlying-network changes. Returns the number aborted.
func ResetFlows() int64 {
	lifeMu.Lock()
	defer lifeMu.Unlock()
	if s := getCurrent(); s != nil {
		return int64(s.engine.ResetFlows())
	}
	return 0
}

// Status returns tunnel.Status as JSON; state "stopped" when no engine runs.
func Status() string {
	if s := getCurrent(); s != nil {
		return toJSON(s.engine.Status())
	}
	return toJSON(tunnel.Status{State: tunnel.StateStopped.String()})
}

// Stats returns tunnel.Snapshot as JSON, or "{}" when no engine runs.
func Stats() string {
	if s := getCurrent(); s != nil {
		return toJSON(s.engine.Stats())
	}
	return "{}"
}

// Flows returns the open and connecting flows as a JSON array.
func Flows() string {
	if s := getCurrent(); s != nil {
		return toJSON(s.engine.Flows())
	}
	return "[]"
}

// NetworkPlan returns the addresses the VpnService builder uses, as JSON.
func NetworkPlan() string {
	return toJSON(map[string]any{
		"tun_address4": TunAddress4, "tun_address6": TunAddress6, "dns_server4": DNSServer4,
		"fake_ip_prefix": FakeIPPrefix, "default_mtu": tunnel.DefaultMTU,
	})
}

// Version identifies the engine build: module versions and the Go toolchain.
func Version() string {
	v := "pfengine (devel)"
	gvisor := "unknown"
	if bi, ok := debug.ReadBuildInfo(); ok {
		for _, d := range bi.Deps {
			if d.Path == "gvisor.dev/gvisor" {
				gvisor = d.Version
			}
			if d.Path == "github.com/bibekthapamgr/app/engine" && d.Version != "" {
				v = "pfengine " + d.Version
			}
		}
	}
	return fmt.Sprintf("%s; gvisor %s; %s %s/%s", v, gvisor, runtime.Version(), runtime.GOOS, runtime.GOARCH)
}

// --- building blocks shared with Probe and SelfTest -------------------------

func newDialer(cfg config, secret []byte, p Platform) (*adapters.Dialer, *adapters.Credentials, error) {
	kind, err := adapters.ParseKind(cfg.Protocol)
	if err != nil {
		return nil, nil, err
	}
	creds, err := adapters.ParseCredentials(secret)
	if err != nil {
		return nil, nil, err
	}
	pc := adapters.ProxyConfig{
		Kind: kind, Host: cfg.Host, Port: uint16(cfg.Port),
		DialTimeout: ms(cfg.DialTimeoutMs), HandshakeTimeout: ms(cfg.HandshakeTimeoutMs),
	}
	if cfg.TLS {
		pc.TLS = &adapters.TLSOptions{ServerName: cfg.TLSServerName, Verifier: platformVerifier{p}}
	}
	d, err := adapters.NewDialer(pc, creds, protector(p), resolver(p))
	if err != nil {
		creds.Wipe()
		return nil, nil, err
	}
	return d, creds, nil
}

func newEngine(fd int, cfg config, dialer tunnel.TunnelDialer, p Platform) (*tunnel.Engine, error) {
	pool, err := fakedns.NewPool(netip.MustParsePrefix(FakeIPPrefix), fakeReserved, 0)
	if err != nil {
		return nil, err
	}
	tc := tunnel.Config{
		FD: fd, MTU: uint32(cfg.MTU), Dialer: dialer, FakeDNS: &fakedns.Responder{Pool: pool, TTL: fakeDNSTTL},
		MaxConns: cfg.MaxConns, IdleTimeout: ms(cfg.IdleTimeoutMs), LogDestinations: cfg.LogDestinations,
	}
	if p != nil {
		tc.Events = func(ev tunnel.Event) { p.OnEvent(toJSON(ev)) }
	}
	return tunnel.New(tc)
}

func protector(p Platform) adapters.Protector {
	return func(fd uintptr) error {
		if !p.Protect(int64(fd)) {
			return errors.New("VpnService.protect returned false")
		}
		return nil
	}
}

// resolver adapts ResolveProxyHost. The platform call cannot be interrupted,
// so a context that ends first abandons it (it finishes in the background).
func resolver(p Platform) adapters.Resolver {
	return func(ctx context.Context, host string) ([]netip.Addr, error) {
		type result struct {
			list string
			err  error
		}
		ch := make(chan result, 1)
		go func() {
			list, err := p.ResolveProxyHost(host)
			ch <- result{list, err}
		}()
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case r := <-ch:
			if r.err != nil {
				return nil, errors.New("the platform could not resolve the proxy host")
			}
			var out []netip.Addr
			for _, s := range strings.Split(r.list, ",") {
				if a, err := netip.ParseAddr(strings.TrimSpace(s)); err == nil {
					out = append(out, a)
				}
			}
			return out, nil
		}
	}
}

// platformVerifier hands the chain to the platform trust manager (ADR-0005).
type platformVerifier struct{ p Platform }

func (v platformVerifier) VerifyChain(chain []*x509.Certificate, serverName string) error {
	ders := make([][]byte, len(chain))
	for i, c := range chain {
		ders[i] = c.Raw
	}
	return parseVerdict(v.p.VerifyChain(encodeChain(ders), serverName))
}

// encodeChain packs DER certificates as the VerifyChain callback expects.
func encodeChain(ders [][]byte) []byte {
	n := 0
	for _, d := range ders {
		n += 4 + len(d)
	}
	out := make([]byte, 0, n)
	for _, d := range ders {
		out = binary.BigEndian.AppendUint32(out, uint32(len(d)))
		out = append(out, d...)
	}
	return out
}

func parseVerdict(verdict string) error {
	if verdict == "" {
		return nil
	}
	code, detail, _ := strings.Cut(verdict, ":")
	detail = strings.TrimSpace(detail)
	switch adapters.Code(strings.TrimSpace(code)) {
	case adapters.CodeTLSExpired:
		return &adapters.Error{Code: adapters.CodeTLSExpired, Detail: orDefault(detail, "proxy certificate is expired or not yet valid")}
	case adapters.CodeTLSHostname:
		return &adapters.Error{Code: adapters.CodeTLSHostname, Detail: orDefault(detail, "proxy certificate does not match the proxy hostname")}
	}
	return &adapters.Error{Code: adapters.CodeTLSUntrusted, Detail: orDefault(detail, "proxy certificate is not trusted by this phone")}
}

func orDefault(s, d string) string {
	if s == "" {
		return d
	}
	return s
}

// --- helpers ------------------------------------------------------------------

func codeErr(code adapters.Code, detail string) error {
	return fmt.Errorf("%s: %s", code, detail)
}

func toJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return "{}"
	}
	return string(b)
}

func ms(v int64) time.Duration { return time.Duration(v) * time.Millisecond }

func wipe(b []byte) {
	for i := range b {
		b[i] = 0
	}
}

// splitTarget parses "host:port" for Probe and SelfTest.
func splitTarget(target string) (adapters.Target, error) {
	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		return adapters.Target{}, errors.New("target must be host:port")
	}
	var port int
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil || port < 1 || port > 65535 || fmt.Sprint(port) != portStr {
		return adapters.Target{}, errors.New("target port must be 1-65535")
	}
	if a, err := netip.ParseAddr(host); err == nil && a.Zone() == "" {
		return adapters.Target{Addr: a.Unmap(), Port: uint16(port)}, nil
	}
	name := strings.TrimSuffix(strings.ToLower(host), ".")
	if !adapters.ValidHostname(name) {
		return adapters.Target{}, errors.New("target host is not a hostname or IP address")
	}
	return adapters.Target{Host: name, Port: uint16(port)}, nil
}
