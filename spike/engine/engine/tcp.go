package engine

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

	"proxyflow/spike/engine/adapters"
)

var bufPool = sync.Pool{New: func() any { b := make([]byte, 32<<10); return &b }}

// handleTCP runs in its own goroutine per new flow (tcp.Forwarder contract).
func (e *Engine) handleTCP(r *tcp.ForwarderRequest) {
	e.wg.Add(1)
	defer e.wg.Done()
	// Per-flow panic isolation: a bug in one flow must not take the whole
	// tunnel down (an engine crash leaves the VPN black-holed). The panic is
	// counted and surfaced as an INTERNAL failure so it is still visible.
	completed := false
	defer func() {
		if p := recover(); p != nil {
			e.stats.panics.Add(1)
			e.stats.failure(adapters.CodeInternal)
			e.emit(Event{Type: EvConnFail, Proto: "tcp", Code: string(adapters.CodeInternal), Detail: "recovered panic"})
			if !completed {
				func() { defer func() { recover() }(); r.Complete(true) }()
			}
		}
	}()

	id := r.ID()
	dst := toNetip(id.LocalAddress)
	target := adapters.Target{Addr: dst, Port: id.LocalPort}
	ev := Event{Type: EvConnOpen, Proto: "tcp", Port: id.LocalPort}

	if e.cfg.FakeDNS.Pool.Contains(dst) {
		name, ok := e.cfg.FakeDNS.Pool.Reverse(dst)
		if !ok {
			e.refuse(r, ev, adapters.CodeStaleMapping)
			return
		}
		target.Host = name
		ev.DstKind = "hostname"
	} else {
		ev.DstKind = "ip"
	}
	if e.cfg.LogDestinations {
		ev.Dst = target.HostPort()
	}

	select {
	case e.sem <- struct{}{}:
		defer func() { <-e.sem }()
	default:
		e.refuse(r, ev, adapters.CodeLimit)
		return
	}

	// Dial the proxy tunnel first: only a working tunnel completes the app's handshake.
	ctx, cancel := context.WithTimeout(e.ctx, e.cfg.DialTimeout)
	start := time.Now()
	up, err := e.cfg.Dialer.Dial(ctx, target)
	cancel()
	if err != nil {
		code := adapters.CodeOf(err)
		e.stats.proxyResult(code)
		ev.Detail = safeDetail(err)
		e.refuse(r, ev, code)
		return
	}
	e.stats.proxyResult("")

	var wq waiter.Queue
	ep, terr := r.CreateEndpoint(&wq)
	if terr != nil {
		// The app side of the handshake failed (e.g. the app gave up or
		// reset). The upstream tunnel is closed; nothing is forwarded.
		up.Close()
		ev.Detail = "app-side handshake: " + terr.String()
		e.refuse(r, ev, adapters.CodeAppHandshake)
		return
	}
	r.Complete(false)
	completed = true
	setKeepalive(ep)
	down := gonet.NewTCPConn(&wq, ep)

	var closeOnce sync.Once
	closeBoth := func() { closeOnce.Do(func() { down.Close(); up.Close() }) }
	cid, ok := e.track(closeBoth)
	if !ok {
		closeBoth()
		return
	}
	defer e.untrack(cid)

	e.stats.tcpActive.Add(1)
	e.stats.tcpOpened.Add(1)
	ev.ConnID = cid
	ev.SetupMs = time.Since(start).Milliseconds()
	e.emit(ev)

	upBytes, downBytes := e.relay(down, up, closeBoth)

	e.stats.tcpActive.Add(-1)
	e.emit(Event{Type: EvConnClose, ConnID: cid, Proto: "tcp", BytesUp: upBytes, BytesDown: downBytes,
		DurationMs: time.Since(start).Milliseconds()})
}

func (e *Engine) refuse(r *tcp.ForwarderRequest, ev Event, code adapters.Code) {
	r.Complete(true) // RST to the app: fail closed, no direct fallback
	e.stats.tcpFailed.Add(1)
	e.stats.failure(code)
	ev.Type = EvConnFail
	ev.Code = string(code)
	e.emit(ev)
}

// relay copies both directions until both are done, with half-close and an
// idle timeout. Returns bytes app->proxy and proxy->app.
func (e *Engine) relay(down, up net.Conn, closeBoth func()) (int64, int64) {
	var upN, downN atomic.Int64
	var last atomic.Int64 // unix nanos of the last byte in either direction
	last.Store(time.Now().UnixNano())
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); e.pipe(up, down, &upN, &e.stats.bytesUp, &last, closeBoth) }()
	go func() { defer wg.Done(); e.pipe(down, up, &downN, &e.stats.bytesDown, &last, closeBoth) }()
	wg.Wait()
	closeBoth()
	return upN.Load(), downN.Load()
}

// pipe copies src->dst. The idle timeout is shared by both directions so a
// long one-way transfer is not cut off by silence in the other direction.
func (e *Engine) pipe(dst, src net.Conn, n, total, last *atomic.Int64, closeBoth func()) {
	bp := bufPool.Get().(*[]byte)
	defer bufPool.Put(bp)
	buf := *bp
	for {
		_ = src.SetReadDeadline(time.Now().Add(e.cfg.IdleTimeout))
		r, rerr := src.Read(buf)
		if r > 0 {
			last.Store(time.Now().UnixNano())
			_ = dst.SetWriteDeadline(time.Now().Add(e.cfg.IdleTimeout))
			w, werr := dst.Write(buf[:r])
			n.Add(int64(w))
			total.Add(int64(w))
			if werr != nil {
				closeBoth()
				return
			}
		}
		if rerr != nil {
			if errors.Is(rerr, os.ErrDeadlineExceeded) &&
				time.Since(time.Unix(0, last.Load())) < e.cfg.IdleTimeout {
				continue // the other direction is active
			}
			if errors.Is(rerr, io.EOF) {
				// Orderly close from src: propagate half-close to dst.
				if cw, ok := dst.(interface{ CloseWrite() error }); ok {
					_ = cw.CloseWrite()
					return
				}
			}
			if errors.Is(rerr, os.ErrDeadlineExceeded) {
				e.stats.idleTimeouts.Add(1)
			}
			closeBoth()
			return
		}
	}
}

func setKeepalive(ep tcpip.Endpoint) {
	ep.SocketOptions().SetKeepAlive(true)
	idle := tcpip.KeepaliveIdleOption(60 * time.Second)
	_ = ep.SetSockOpt(&idle)
	intvl := tcpip.KeepaliveIntervalOption(30 * time.Second)
	_ = ep.SetSockOpt(&intvl)
	_ = ep.SetSockOptInt(tcpip.KeepaliveCountOption, 9)
}

// safeDetail returns the categorised error detail (never credentials/payload).
func safeDetail(err error) string {
	var ae *adapters.Error
	if errors.As(err, &ae) {
		return ae.Detail
	}
	return ""
}
