package proxytest

import (
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"testing"
	"time"
)

// Protocol selects what a mock proxy speaks.
type Protocol int

const (
	SOCKS5 Protocol = iota + 1
	HTTPConnect
)

// Config scripts a mock proxy. The zero value of every knob means "behave
// like a correct proxy".
type Config struct {
	Protocol Protocol
	// Network to listen on: "tcp4" (default, 127.0.0.1) or "tcp6" ([::1]).
	Network string
	// Username and Password required from clients; empty Username = no sign-in.
	Username, Password string
	// TLS, when set, makes the proxy speak TLS first (TLS-to-proxy).
	TLS *tls.Config
	// Hosts maps destination hostnames (or IP literals) to dialable "ip:port"
	// addresses. A name not listed is unreachable (SOCKS5 REP 0x04, HTTP 502);
	// an IP literal not listed is dialled as given.
	Hosts map[string]string

	// Misbehaviour.
	Silent         bool          // accept, read, never answer
	CloseOnAccept  bool          // close the connection right after accepting
	ReplyDelay     time.Duration // wait before every reply
	SOCKSMethod    int           // >0: answer method selection with this byte (e.g. 0xFF)
	SOCKSReply     byte          // non-zero: answer CONNECT with this REP code
	SOCKSBadVer    bool          // answer method selection with version 4
	HTTPStatus     int           // non-zero and not 2xx: answer CONNECT with this status
	AuthScheme     string        // Proxy-Authenticate scheme on 407 (default "Basic")
	ExtraHeaders   []string      // extra header lines in the 200 response
	TunnelPreface  []byte        // bytes sent right after a successful reply, before relaying
	RawResponse    []byte        // HTTP: send these bytes instead of any response, then close
	DestDialTimout time.Duration // default 5 s
}

// Record is what the mock proxy saw for one client connection. Test-only:
// it holds the credentials the client sent so tests can check the encoding.
type Record struct {
	Target     string // "host:port" as requested
	AddrType   string // SOCKS5: "domain", "ipv4" or "ipv6"; HTTP: "authority"
	Methods    []byte // SOCKS5 methods offered
	Username   string // SOCKS5 RFC 1929 username sent, or decoded from HTTP Basic
	Password   string
	AuthHeader string // HTTP: raw Proxy-Authorization value
	Head       string // HTTP: request head as received
	Outcome    string // "ok", "auth-failed", "auth-missing", "unreachable", "refused", "scripted", "protocol"
}

// Server is a running mock proxy.
type Server struct {
	cfg  Config
	ln   net.Listener
	Addr netip.AddrPort

	mu      sync.Mutex
	conns   map[net.Conn]struct{}
	records []Record
	accepts int
	closed  bool
	done    chan struct{} // closed by Close: interrupts scripted delays
	wg      sync.WaitGroup
}

// NewServer starts a mock proxy and closes it when the test ends.
func NewServer(t testing.TB, cfg Config) *Server {
	t.Helper()
	network, addr := "tcp4", "127.0.0.1:0"
	if cfg.Network == "tcp6" {
		network, addr = "tcp6", "[::1]:0"
	}
	ln, err := net.Listen(network, addr)
	if err != nil {
		t.Fatalf("proxytest listen: %v", err)
	}
	if cfg.TLS != nil {
		ln = tls.NewListener(ln, cfg.TLS)
	}
	if cfg.DestDialTimout == 0 {
		cfg.DestDialTimout = 5 * time.Second
	}
	s := &Server{cfg: cfg, ln: ln, Addr: ln.Addr().(*net.TCPAddr).AddrPort(), conns: map[net.Conn]struct{}{}, done: make(chan struct{})}
	s.wg.Add(1)
	go s.serve()
	t.Cleanup(s.Close)
	return s
}

// Host and Port of the listener, for building a proxy configuration.
func (s *Server) Host() string { return s.Addr.Addr().String() }
func (s *Server) Port() uint16 { return s.Addr.Port() }

// Records returns a copy of what the proxy saw so far.
func (s *Server) Records() []Record {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]Record(nil), s.records...)
}

// Accepts is the number of TCP connections the proxy accepted.
func (s *Server) Accepts() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.accepts
}

// Active is the number of client and upstream connections still open.
func (s *Server) Active() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns)
}

// Close stops accepting, closes every connection and waits for all goroutines.
func (s *Server) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	close(s.done)
	s.ln.Close()
	for c := range s.conns {
		c.Close()
	}
	s.mu.Unlock()
	s.wg.Wait()
}

func (s *Server) track(c net.Conn) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		c.Close()
		return false
	}
	s.conns[c] = struct{}{}
	return true
}

func (s *Server) untrack(c net.Conn) {
	c.Close()
	s.mu.Lock()
	delete(s.conns, c)
	s.mu.Unlock()
}

func (s *Server) record(r Record) {
	s.mu.Lock()
	s.records = append(s.records, r)
	s.mu.Unlock()
}

func (s *Server) serve() {
	defer s.wg.Done()
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		s.accepts++
		s.mu.Unlock()
		if !s.track(c) {
			return
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			defer s.untrack(c)
			if s.cfg.CloseOnAccept {
				return
			}
			if s.cfg.Silent {
				_, _ = io.Copy(io.Discard, c)
				return
			}
			switch s.cfg.Protocol {
			case SOCKS5:
				s.serveSOCKS5(c)
			case HTTPConnect:
				s.serveHTTP(c)
			}
		}()
	}
}

func (s *Server) delay() {
	if s.cfg.ReplyDelay > 0 {
		t := time.NewTimer(s.cfg.ReplyDelay)
		defer t.Stop()
		select {
		case <-t.C:
		case <-s.done:
		}
	}
}

// resolve maps a requested destination to a dialable address. Hosts entries
// win, also for IP literals, so tests can send IPv6 destinations through the
// proxy on machines without IPv6.
func (s *Server) resolve(host string, port uint16) (string, bool) {
	if mapped, ok := s.cfg.Hosts[host]; ok {
		return mapped, true
	}
	if a, err := netip.ParseAddr(host); err == nil {
		return netip.AddrPortFrom(a, port).String(), true
	}
	return "", false
}

func (s *Server) dialDest(addr string) (net.Conn, error) {
	up, err := net.DialTimeout("tcp", addr, s.cfg.DestDialTimout)
	if err != nil {
		return nil, err
	}
	if !s.track(up) {
		return nil, errors.New("server closed")
	}
	return up, nil
}

// relay copies both ways with half-close until both directions end.
func (s *Server) relay(client io.ReadWriter, clientConn, up net.Conn) {
	defer s.untrack(up)
	done := make(chan struct{})
	go func() {
		_, _ = io.Copy(up, client)
		closeWrite(up)
		close(done)
	}()
	_, _ = io.Copy(clientConn, up)
	closeWrite(clientConn)
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
		return
	}
	_ = c.Close()
}

func itoa(n int) string { return strconv.Itoa(n) }
