package tunnel

import (
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// maxDNSMessage bounds one DNS query read from an app.
const maxDNSMessage = 4096

// handleUDP intercepts DNS (port 53, any destination) and rejects all other
// UDP. Returning false lets the stack answer ICMP port unreachable, which
// makes QUIC/HTTP3 clients fall back to TCP quickly; nothing is forwarded.
// udp.Forwarder calls it synchronously from the stack.
func (e *Engine) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	if id.LocalPort != 53 {
		e.stats.udpRejected.Add(1)
		e.stats.udpRejectedByPort(id.LocalPort)
		ev := Event{Type: EvUDPRejected, Proto: "udp", Port: id.LocalPort}
		if e.cfg.LogDestinations {
			ev.Dst = toNetip(id.LocalAddress).String()
		}
		e.emit(ev)
		return e.cfg.UDPReject == UDPRejectDrop
	}
	select {
	case e.dnsSem <- struct{}{}:
	default:
		e.stats.dnsDropped.Add(1) // too many DNS sockets: drop, the app retries
		return true
	}
	if !e.enter() {
		<-e.dnsSem
		return true
	}
	var wq waiter.Queue
	ep, err := r.CreateEndpoint(&wq)
	if err != nil {
		<-e.dnsSem
		e.wg.Done()
		return true
	}
	go e.serveDNS(gonet.NewUDPConn(&wq, ep))
	return true
}

func (e *Engine) serveDNS(c *gonet.UDPConn) {
	defer e.wg.Done()
	defer func() { <-e.dnsSem }()
	defer c.Close()
	defer func() {
		if p := recover(); p != nil {
			e.stats.panics.Add(1)
		}
	}()
	f := &flow{kind: FlowDNS, port: 53}
	f.setAbort(func() { c.Close() })
	if !e.register(f) {
		return
	}
	defer e.unregister(f)
	f.open.Store(true)
	buf := make([]byte, maxDNSMessage)
	for {
		_ = c.SetReadDeadline(time.Now().Add(e.cfg.DNSIdleTimeout))
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		f.up.Add(int64(n))
		resp, outcome, name := e.cfg.FakeDNS.Handle(buf[:n])
		e.stats.dnsAnswered(outcome, name)
		ev := Event{Type: EvDNS, Proto: "udp", Port: 53, Detail: string(outcome)}
		if e.cfg.LogDestinations {
			ev.Dst = name
		}
		e.emit(ev)
		if resp != nil {
			if w, err := c.Write(resp); err == nil {
				f.down.Add(int64(w))
			}
		}
	}
}
