package engine

import (
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

// handleUDP intercepts DNS (port 53, any destination) and rejects all other
// UDP. Returning false lets the stack answer ICMP port-unreachable, which
// makes QUIC/HTTP3 clients fall back to TCP quickly; nothing is forwarded.
func (e *Engine) handleUDP(r *udp.ForwarderRequest) bool {
	id := r.ID()
	if id.LocalPort == 53 {
		var wq waiter.Queue
		ep, err := r.CreateEndpoint(&wq)
		if err != nil {
			return true
		}
		conn := gonet.NewUDPConn(&wq, ep)
		e.wg.Add(1)
		go e.serveDNS(conn)
		return true
	}
	e.stats.udpRejected.Add(1)
	e.stats.udpRejectedByPort(id.LocalPort)
	if e.cfg.Events != nil {
		ev := Event{Type: EvUDPRejected, Proto: "udp", Port: id.LocalPort}
		if e.cfg.LogDestinations {
			ev.Dst = toNetip(id.LocalAddress).String()
		}
		e.emit(ev)
	}
	return e.cfg.UDPReject == UDPRejectDrop
}

func (e *Engine) serveDNS(c *gonet.UDPConn) {
	defer e.wg.Done()
	cid, ok := e.track(func() { c.Close() })
	if !ok {
		c.Close()
		return
	}
	defer e.untrack(cid)
	defer c.Close()
	buf := make([]byte, 4096)
	for {
		_ = c.SetReadDeadline(time.Now().Add(e.cfg.DNSIdleTimeout))
		n, err := c.Read(buf)
		if err != nil {
			return
		}
		resp, outcome, name := e.cfg.FakeDNS.Handle(buf[:n])
		e.stats.dnsOutcome(string(outcome))
		if e.cfg.Events != nil {
			ev := Event{Type: EvDNS, Proto: "udp", Port: 53, Detail: string(outcome)}
			if e.cfg.LogDestinations {
				ev.Dst = name
			}
			e.emit(ev)
		}
		if resp != nil {
			_, _ = c.Write(resp)
		}
	}
}
