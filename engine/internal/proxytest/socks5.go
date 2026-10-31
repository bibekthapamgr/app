package proxytest

import (
	"bufio"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
)

// serveSOCKS5 implements the server side of RFC 1928 (CONNECT only) and
// RFC 1929, with the scripted deviations from Config.
func (s *Server) serveSOCKS5(c net.Conn) {
	br := bufio.NewReader(c)
	rec := Record{}
	recorded := false
	// decide records the outcome before the client can see it, so a test that
	// reads Records right after its dial returns always finds this connection.
	decide := func(outcome string) {
		rec.Outcome = outcome
		s.record(rec)
		recorded = true
	}
	defer func() {
		if !recorded {
			s.record(rec)
		}
	}()

	var hdr [2]byte
	if _, err := io.ReadFull(br, hdr[:]); err != nil || hdr[0] != 5 {
		rec.Outcome = "protocol"
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(br, methods); err != nil {
		rec.Outcome = "protocol"
		return
	}
	rec.Methods = methods
	s.delay()

	ver := byte(5)
	if s.cfg.SOCKSBadVer {
		ver = 4
	}
	method := byte(0xFF)
	switch {
	case s.cfg.SOCKSMethod > 0:
		method = byte(s.cfg.SOCKSMethod)
	case s.cfg.Username != "" && contains(methods, 0x02):
		method = 0x02
	case s.cfg.Username == "" && contains(methods, 0x00):
		method = 0x00
	}
	if method == 0xFF || ver != 5 {
		decide("auth-missing")
		_, _ = c.Write([]byte{ver, method})
		return
	}
	if _, err := c.Write([]byte{ver, method}); err != nil {
		return
	}
	if method == 0x02 {
		user, pass, ok := readUserPass(br)
		if !ok {
			rec.Outcome = "protocol"
			return
		}
		rec.Username, rec.Password = user, pass
		if user != s.cfg.Username || pass != s.cfg.Password {
			decide("auth-failed")
			_, _ = c.Write([]byte{1, 1})
			return
		}
		if _, err := c.Write([]byte{1, 0}); err != nil {
			return
		}
	}

	// VER CMD RSV ATYP
	var req [4]byte
	if _, err := io.ReadFull(br, req[:]); err != nil || req[0] != 5 {
		rec.Outcome = "protocol"
		return
	}
	var host string
	switch req[3] {
	case 1:
		var a [4]byte
		if _, err := io.ReadFull(br, a[:]); err != nil {
			return
		}
		host, rec.AddrType = netip.AddrFrom4(a).String(), "ipv4"
	case 4:
		var a [16]byte
		if _, err := io.ReadFull(br, a[:]); err != nil {
			return
		}
		host, rec.AddrType = netip.AddrFrom16(a).String(), "ipv6"
	case 3:
		var l [1]byte
		if _, err := io.ReadFull(br, l[:]); err != nil {
			return
		}
		name := make([]byte, l[0])
		if _, err := io.ReadFull(br, name); err != nil {
			return
		}
		host, rec.AddrType = string(name), "domain"
	default:
		decide("protocol")
		s.socksReply(c, 0x08, nil)
		return
	}
	var pb [2]byte
	if _, err := io.ReadFull(br, pb[:]); err != nil {
		return
	}
	port := binary.BigEndian.Uint16(pb[:])
	rec.Target = net.JoinHostPort(host, itoa(int(port)))
	s.delay()

	if req[1] != 1 {
		decide("protocol")
		s.socksReply(c, 0x07, nil)
		return
	}
	if s.cfg.SOCKSReply != 0 {
		decide("scripted")
		s.socksReply(c, s.cfg.SOCKSReply, nil)
		return
	}
	addr, ok := s.resolve(host, port)
	if !ok {
		decide("unreachable")
		s.socksReply(c, 0x04, nil)
		return
	}
	up, err := s.dialDest(addr)
	if err != nil {
		decide("refused")
		s.socksReply(c, 0x05, nil)
		return
	}
	decide("ok")
	if !s.socksReply(c, 0x00, up.LocalAddr()) {
		s.untrack(up)
		return
	}
	if len(s.cfg.TunnelPreface) > 0 {
		if _, err := c.Write(s.cfg.TunnelPreface); err != nil {
			s.untrack(up)
			return
		}
	}
	s.relay(readWriter{br, c}, c, up)
}

// socksReply writes VER REP RSV ATYP BND.ADDR BND.PORT.
func (s *Server) socksReply(c net.Conn, rep byte, bound net.Addr) bool {
	msg := []byte{5, rep, 0}
	ap := netip.AddrPortFrom(netip.IPv4Unspecified(), 0)
	if ta, ok := bound.(*net.TCPAddr); ok {
		ap = ta.AddrPort()
	}
	if a := ap.Addr().Unmap(); a.Is4() {
		b := a.As4()
		msg = append(msg, 1)
		msg = append(msg, b[:]...)
	} else {
		b := a.As16()
		msg = append(msg, 4)
		msg = append(msg, b[:]...)
	}
	msg = binary.BigEndian.AppendUint16(msg, ap.Port())
	_, err := c.Write(msg)
	return err == nil
}

func readUserPass(r io.Reader) (user, pass string, ok bool) {
	var vl [2]byte
	if _, err := io.ReadFull(r, vl[:]); err != nil || vl[0] != 1 {
		return "", "", false
	}
	u := make([]byte, vl[1])
	if _, err := io.ReadFull(r, u); err != nil {
		return "", "", false
	}
	var pl [1]byte
	if _, err := io.ReadFull(r, pl[:]); err != nil {
		return "", "", false
	}
	p := make([]byte, pl[0])
	if _, err := io.ReadFull(r, p); err != nil {
		return "", "", false
	}
	return string(u), string(p), true
}

func contains(b []byte, x byte) bool {
	for _, v := range b {
		if v == x {
			return true
		}
	}
	return false
}

// readWriter reads through a buffered reader (bytes already read past the
// handshake) and writes to the connection.
type readWriter struct {
	r io.Reader
	w io.Writer
}

func (rw readWriter) Read(p []byte) (int, error)  { return rw.r.Read(p) }
func (rw readWriter) Write(p []byte) (int, error) { return rw.w.Write(p) }
