package tunnel

import (
	"encoding/binary"
	"io"
	"net"
	"time"
)

// dnsPort is where the engine answers DNS itself, over UDP and TCP, whatever the
// destination address (ADR-0003, ADR-0011).
const dnsPort = 53

// serveDNSOverTCP answers DNS over TCP (RFC 7766: each message has a 2-byte
// length first) on a connection an app opened to port 53 of any address, with
// the same fake-IP answers as over UDP. Nothing is forwarded. The connection
// closes when it stays idle for DNSIdleTimeout, carries a message this engine
// does not answer, or the engine stops. handleTCP calls it on its own
// goroutine, inside its panic isolation; abort ends the connection.
func (e *Engine) serveDNSOverTCP(c net.Conn, abort func()) {
	defer c.Close()
	f := &flow{kind: FlowDNS, port: dnsPort}
	f.setAbort(abort)
	if !e.register(f) {
		return
	}
	defer e.unregister(f)
	f.open.Store(true)
	head := make([]byte, 2)
	buf := make([]byte, maxDNSMessage)
	for {
		_ = c.SetDeadline(time.Now().Add(e.cfg.DNSIdleTimeout))
		if _, err := io.ReadFull(c, head); err != nil {
			return
		}
		n := int(binary.BigEndian.Uint16(head))
		if n == 0 || n > len(buf) {
			return
		}
		if _, err := io.ReadFull(c, buf[:n]); err != nil {
			return
		}
		f.up.Add(int64(len(head) + n))
		resp, outcome, name := e.cfg.FakeDNS.Handle(buf[:n])
		e.stats.dnsAnswered(outcome, name)
		ev := Event{Type: EvDNS, Proto: "tcp", Port: dnsPort, Detail: string(outcome)}
		if e.cfg.LogDestinations {
			ev.Dst = name
		}
		e.emit(ev)
		if resp == nil {
			return // not a query (a response, say): nothing to answer on this connection
		}
		out := make([]byte, len(head)+len(resp))
		binary.BigEndian.PutUint16(out, uint16(len(resp)))
		copy(out[len(head):], resp)
		if _, err := c.Write(out); err != nil {
			return
		}
		e.stats.dnsTCP.Add(1) // answered queries only (dns_tcp_queries)
		f.down.Add(int64(len(out)))
	}
}
