package proxytest

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
)

// serveHTTP implements the server side of HTTP CONNECT with Basic proxy
// authentication, with the scripted deviations from Config.
func (s *Server) serveHTTP(c net.Conn) {
	br := bufio.NewReaderSize(c, 4096)
	rec := Record{AddrType: "authority"}
	recorded := false
	// decide records the outcome before the client can see it (see serveSOCKS5).
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

	var head strings.Builder
	var authority, auth string
	for n := 0; ; n++ {
		line, err := br.ReadString('\n')
		head.WriteString(line)
		if err != nil || n > 200 || head.Len() > 64<<10 {
			rec.Head, rec.Outcome = head.String(), "protocol"
			return
		}
		line = strings.TrimRight(line, "\r\n")
		if n == 0 {
			parts := strings.Split(line, " ")
			if len(parts) != 3 || parts[0] != "CONNECT" || !strings.HasPrefix(parts[2], "HTTP/1.") {
				rec.Head = head.String()
				decide("protocol")
				s.writeStatus(c, http.StatusMethodNotAllowed, nil)
				return
			}
			authority = parts[1]
			continue
		}
		if line == "" {
			break
		}
		if name, value, ok := strings.Cut(line, ":"); ok && strings.EqualFold(strings.TrimSpace(name), "Proxy-Authorization") {
			auth = strings.TrimSpace(value)
		}
	}
	rec.Head, rec.Target, rec.AuthHeader = head.String(), authority, auth
	if user, pass, ok := parseBasic(auth); ok {
		rec.Username, rec.Password = user, pass
	}
	s.delay()

	if s.cfg.RawResponse != nil {
		decide("scripted")
		_, _ = c.Write(s.cfg.RawResponse)
		return
	}
	if s.cfg.Username != "" {
		if auth == "" || rec.Username != s.cfg.Username || rec.Password != s.cfg.Password {
			scheme := s.cfg.AuthScheme
			if scheme == "" {
				scheme = "Basic"
			}
			if auth == "" {
				decide("auth-missing")
			} else {
				decide("auth-failed")
			}
			s.writeStatus(c, http.StatusProxyAuthRequired, []string{"Proxy-Authenticate: " + scheme + ` realm="proxytest"`})
			return
		}
	}
	if s.cfg.HTTPStatus != 0 && (s.cfg.HTTPStatus < 200 || s.cfg.HTTPStatus > 299) {
		decide("scripted")
		s.writeStatus(c, s.cfg.HTTPStatus, nil)
		return
	}
	host, portStr, err := net.SplitHostPort(authority)
	port, perr := strconv.ParseUint(portStr, 10, 16)
	if err != nil || perr != nil {
		decide("protocol")
		s.writeStatus(c, http.StatusBadRequest, nil)
		return
	}
	addr, ok := s.resolve(host, uint16(port))
	if !ok {
		decide("unreachable")
		s.writeStatus(c, http.StatusBadGateway, nil)
		return
	}
	up, err := s.dialDest(addr)
	if err != nil {
		decide("refused")
		s.writeStatus(c, http.StatusBadGateway, nil)
		return
	}
	status := 200
	if s.cfg.HTTPStatus != 0 {
		status = s.cfg.HTTPStatus
	}
	resp := fmt.Sprintf("HTTP/1.1 %d Connection established\r\n", status)
	for _, h := range s.cfg.ExtraHeaders {
		resp += h + "\r\n"
	}
	resp += "\r\n"
	decide("ok")
	if _, err := c.Write(append([]byte(resp), s.cfg.TunnelPreface...)); err != nil {
		s.untrack(up)
		return
	}
	s.relay(readWriter{br, c}, c, up)
}

func (s *Server) writeStatus(c net.Conn, status int, headers []string) {
	resp := fmt.Sprintf("HTTP/1.1 %d %s\r\n", status, http.StatusText(status))
	for _, h := range headers {
		resp += h + "\r\n"
	}
	resp += "Content-Length: 0\r\n\r\n"
	_, _ = c.Write([]byte(resp))
}

func parseBasic(v string) (user, pass string, ok bool) {
	scheme, enc, found := strings.Cut(v, " ")
	if !found || !strings.EqualFold(scheme, "Basic") {
		return "", "", false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(enc))
	if err != nil {
		return "", "", false
	}
	user, pass, ok = strings.Cut(string(raw), ":")
	return user, pass, ok
}
