package tunnel

import (
	"context"
	"errors"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/waiter"

	"github.com/bibekthapamgr/app/engine/adapters"
)

// handleTCP runs in its own goroutine per new flow (tcp.Forwarder contract).
// The tunnel through the proxy is dialled first; only a working tunnel
// completes the app's handshake. Any failure answers the app's SYN with a RST.
func (e *Engine) handleTCP(r *tcp.ForwarderRequest) {
	if !e.enter() {
		r.Complete(true)
		return
	}
	defer e.wg.Done()

	// Per-flow panic isolation: a bug in one flow must not take the tunnel
	// down (an engine crash leaves the VPN black-holed). The panic is counted
	// and reported as INTERNAL; the flow's sockets are closed.
	completed := false
	var cleanup func()
	defer func() {
		if p := recover(); p != nil {
			e.recovered()
			if cleanup != nil {
				cleanup()
			}
			if !completed {
				func() { defer func() { _ = recover() }(); r.Complete(true) }()
			}
		}
	}()

	id := r.ID()
	dst := toNetip(id.LocalAddress)
	if id.LocalPort == dnsPort {
		// DNS over TCP, to any address, is answered here like DNS over UDP (ADR-0011):
		// the query never leaves the phone, and the name still reaches the proxy as a name.
		select {
		case e.dnsSem <- struct{}{}:
			defer func() { <-e.dnsSem }()
		default:
			completed = true
			e.stats.dnsDropped.Add(1) // too many DNS clients at once: refused, the app retries
			r.Complete(true)
			return
		}
		var wq waiter.Queue
		ep, terr := r.CreateEndpoint(&wq)
		completed = true
		if terr != nil {
			r.Complete(true)
			return
		}
		r.Complete(false)
		conn := gonet.NewTCPConn(&wq, ep)
		cleanup = func() { ep.Abort(); conn.Close() }
		e.serveDNSOverTCP(conn, cleanup)
		return
	}
	if e.cfg.FakeDNS.Pool.Reserved(dst) {
		// ProxyFlow's own addresses (the TUN's, the DNS server's): there is nothing to
		// proxy. Android's opportunistic DNS-over-TLS probe to the VPN's DNS server (port
		// 853) ends here, and then uses plain DNS. Not a failure of any app's connection.
		completed = true
		e.stats.localRefused.Add(1)
		r.Complete(true)
		return
	}
	target := adapters.Target{Addr: dst, Port: id.LocalPort}
	f := &flow{kind: FlowTCP, port: id.LocalPort, dstKind: "ip"}
	if e.cfg.FakeDNS.Pool.Contains(dst) {
		f.dstKind = "hostname"
		// Pinned for the whole flow: the address cannot go to another name
		// while the app may still use it (fakedns.Pool).
		name, release, ok := e.cfg.FakeDNS.Pool.Acquire(dst)
		if !ok {
			completed = true
			e.refuse(r, f, adapters.CodeStaleMapping, "")
			return
		}
		defer release()
		target.Host = name
	}
	if e.cfg.LogDestinations {
		f.dst = target.HostPort()
	}

	select {
	case e.sem <- struct{}{}:
		defer func() { <-e.sem }()
	default:
		completed = true
		e.refuse(r, f, adapters.CodeLimit, "")
		return
	}

	ctx, cancel := context.WithTimeout(e.ctx, e.cfg.DialTimeout)
	defer cancel()
	f.setAbort(cancel)
	if !e.register(f) {
		completed = true
		r.Complete(true)
		return
	}
	defer e.unregister(f)

	start := time.Now()
	up, err := e.cfg.Dialer.Dial(ctx, target)
	if err != nil {
		completed = true
		code := adapters.CodeOf(err)
		e.stats.proxyResult(code)
		if adapters.IsTimeout(err) {
			e.stats.proxyTimeouts.Add(1)
		}
		e.refuse(r, f, code, adapters.DetailOf(err))
		return
	}
	cleanup = func() { up.Close() }
	e.stats.proxyResult("")

	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		// The app side of the handshake failed (the app gave up or reset).
		// The tunnel is closed; nothing is forwarded.
		up.Close()
		completed = true
		e.refuse(r, f, adapters.CodeAppHandshake, "app-side handshake: "+terr.String())
		return
	}
	r.Complete(false)
	completed = true
	setKeepalive(ep)
	down := gonet.NewTCPConn(&wq, ep)

	var closeOnce sync.Once
	closeBoth := func() { closeOnce.Do(func() { down.Close(); up.Close() }) }
	abort := func() { ep.Abort(); closeBoth() }
	cleanup = abort
	if !f.setAbort(abort) {
		abort() // Stop or ResetFlows came while the tunnel was being set up
		e.stats.canceled.Add(1)
		return
	}
	f.open.Store(true)
	e.stats.tcpActive.Add(1)
	e.stats.tcpOpened.Add(1)
	e.emit(Event{Type: EvConnOpen, ConnID: f.id, Proto: FlowTCP, DstKind: f.dstKind, Dst: f.dst, Port: f.port,
		SetupMs: time.Since(start).Milliseconds()})

	e.relay(f, down, up, closeBoth)

	e.stats.tcpActive.Add(-1)
	e.emit(Event{Type: EvConnClose, ConnID: f.id, Proto: FlowTCP, DstKind: f.dstKind, Dst: f.dst, Port: f.port,
		BytesUp: f.up.Load(), BytesDown: f.down.Load(), DurationMs: time.Since(start).Milliseconds()})
}

// refuse answers the app's SYN with a RST: fail closed, no direct fallback.
// CANCELED (Stop or ResetFlows during set-up) is counted but not reported as
// a failure.
func (e *Engine) refuse(r *tcp.ForwarderRequest, f *flow, code adapters.Code, detail string) {
	r.Complete(true)
	if code == adapters.CodeCanceled {
		e.stats.canceled.Add(1)
		return
	}
	e.stats.tcpFailed.Add(1)
	e.stats.failure(code, detail, f.dstKind == "hostname")
	e.emit(Event{Type: EvConnFail, ConnID: f.id, Proto: FlowTCP, DstKind: f.dstKind, Dst: f.dst, Port: f.port,
		Code: string(code), Detail: detail})
}

func (e *Engine) recovered() {
	e.stats.panics.Add(1)
	e.stats.failure(adapters.CodeInternal, "recovered panic", false)
	e.emit(Event{Type: EvConnFail, Proto: FlowTCP, Code: string(adapters.CodeInternal), Detail: "recovered panic"})
}

// relay copies both directions until both are done, with half-close and an
// idle timeout shared by both directions.
func (e *Engine) relay(f *flow, down, up net.Conn, closeBoth func()) {
	var last atomic.Int64 // unix nanos of the last byte in either direction
	last.Store(time.Now().UnixNano())
	var wg sync.WaitGroup
	wg.Add(2)
	go e.pipe(&wg, up, down, &f.up, &e.stats.bytesUp, &last, closeBoth)
	go e.pipe(&wg, down, up, &f.down, &e.stats.bytesDown, &last, closeBoth)
	wg.Wait()
	closeBoth()
}

// pipe copies src to dst with one pooled buffer. A blocked Write stops the
// Reads, so a slow side throttles the fast one through TCP flow control
// instead of queueing data in memory (backpressure).
func (e *Engine) pipe(wg *sync.WaitGroup, dst, src net.Conn, n, total, last *atomic.Int64, closeBoth func()) {
	defer wg.Done()
	defer func() {
		if p := recover(); p != nil {
			e.recovered()
			closeBoth()
		}
	}()
	bp := e.bufPool.Get().(*[]byte)
	defer e.bufPool.Put(bp)
	buf := *bp
	idle := e.cfg.IdleTimeout
	for {
		_ = src.SetReadDeadline(time.Now().Add(idle))
		r, rerr := src.Read(buf)
		if r > 0 {
			last.Store(time.Now().UnixNano())
			_ = dst.SetWriteDeadline(time.Now().Add(idle))
			w, werr := dst.Write(buf[:r])
			n.Add(int64(w))
			total.Add(int64(w))
			if werr != nil {
				closeBoth()
				return
			}
		}
		if rerr != nil {
			if isTimeout(rerr) && time.Since(time.Unix(0, last.Load())) < idle {
				continue // the other direction is active
			}
			if errors.Is(rerr, io.EOF) {
				// Orderly close from src: pass the half-close on.
				if cw, ok := dst.(interface{ CloseWrite() error }); ok {
					_ = cw.CloseWrite()
					return
				}
			}
			if isTimeout(rerr) {
				e.stats.idleTimeouts.Add(1)
			}
			closeBoth()
			return
		}
	}
}

// isTimeout reports a read or write deadline. Sockets report
// os.ErrDeadlineExceeded; gVisor's gonet connections report their own error,
// which only says so through net.Error.
func isTimeout(err error) bool {
	var ne net.Error
	return errors.Is(err, os.ErrDeadlineExceeded) || (errors.As(err, &ne) && ne.Timeout())
}

func setKeepalive(ep tcpip.Endpoint) {
	ep.SocketOptions().SetKeepAlive(true)
	idle := tcpip.KeepaliveIdleOption(60 * time.Second)
	_ = ep.SetSockOpt(&idle)
	intvl := tcpip.KeepaliveIntervalOption(30 * time.Second)
	_ = ep.SetSockOpt(&intvl)
	_ = ep.SetSockOptInt(tcpip.KeepaliveCountOption, 9)
}
