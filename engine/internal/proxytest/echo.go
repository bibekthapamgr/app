package proxytest

import (
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
)

// Echo is a TCP destination that sends back every byte it receives and
// half-closes when the client does. Greeting, if set, is sent first.
type Echo struct {
	Addr     netip.AddrPort
	Greeting []byte

	ln     net.Listener
	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
	served int
	wg     sync.WaitGroup
}

// NewEcho starts an echo server on 127.0.0.1 ("tcp4") or [::1] ("tcp6") and
// closes it when the test ends.
func NewEcho(t testing.TB, network string) *Echo {
	t.Helper()
	addr := "127.0.0.1:0"
	if network == "tcp6" {
		addr = "[::1]:0"
	} else {
		network = "tcp4"
	}
	ln, err := net.Listen(network, addr)
	if err != nil {
		t.Fatalf("proxytest echo listen: %v", err)
	}
	e := &Echo{Addr: ln.Addr().(*net.TCPAddr).AddrPort(), ln: ln, conns: map[net.Conn]struct{}{}}
	e.wg.Add(1)
	go e.serve()
	t.Cleanup(e.Close)
	return e
}

// String is the dialable "ip:port".
func (e *Echo) String() string { return e.Addr.String() }

// Served is the number of connections accepted.
func (e *Echo) Served() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.served
}

// Active is the number of connections still open.
func (e *Echo) Active() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return len(e.conns)
}

// Close stops the server, closes all connections and waits for goroutines.
func (e *Echo) Close() {
	e.mu.Lock()
	if e.closed {
		e.mu.Unlock()
		return
	}
	e.closed = true
	e.ln.Close()
	for c := range e.conns {
		c.Close()
	}
	e.mu.Unlock()
	e.wg.Wait()
}

func (e *Echo) serve() {
	defer e.wg.Done()
	for {
		c, err := e.ln.Accept()
		if err != nil {
			return
		}
		e.mu.Lock()
		if e.closed {
			e.mu.Unlock()
			c.Close()
			return
		}
		e.conns[c] = struct{}{}
		e.served++
		e.mu.Unlock()
		e.wg.Add(1)
		go func() {
			defer e.wg.Done()
			defer func() {
				c.Close()
				e.mu.Lock()
				delete(e.conns, c)
				e.mu.Unlock()
			}()
			if len(e.Greeting) > 0 {
				if _, err := c.Write(e.Greeting); err != nil {
					return
				}
			}
			_, _ = io.Copy(c, c)
			closeWrite(c)
		}()
	}
}
