package adapters

import (
	"bufio"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
)

// HTTP CONNECT client (RFC 9110 §9.3.6) with optional preemptive Basic proxy
// authentication (RFC 7617). Digest is out of V1 scope.

const (
	maxConnectHeaderBytes = 16 << 10 // a hostile proxy cannot make us buffer more
	maxConnectHeaderLines = 100
)

// connectResponse is the parsed status line plus the headers we act on.
type connectResponse struct {
	Status        int
	AuthSchemes   []string // from Proxy-Authenticate, lower-cased scheme tokens
	HeaderByteLen int
}

func httpConnectHandshake(conn net.Conn, user, pass string, t Target) (net.Conn, error) {
	if t.Host != "" && !ValidHostname(t.Host) {
		// Defence in depth against request/header injection via crafted names.
		return nil, newErr(CodeTargetUnsupported, "invalid hostname", nil)
	}
	hp := t.HostPort()
	var b strings.Builder
	b.WriteString("CONNECT ")
	b.WriteString(hp)
	b.WriteString(" HTTP/1.1\r\nHost: ")
	b.WriteString(hp)
	b.WriteString("\r\n")
	if user != "" {
		b.WriteString("Proxy-Authorization: Basic ")
		b.WriteString(base64.StdEncoding.EncodeToString([]byte(user + ":" + pass)))
		b.WriteString("\r\n")
	}
	b.WriteString("\r\n")
	if _, err := io.WriteString(conn, b.String()); err != nil {
		return nil, err
	}

	br := bufio.NewReaderSize(conn, 4096)
	resp, err := readConnectResponse(br)
	if err != nil {
		return nil, err
	}
	if err := connectStatusError(resp, user != ""); err != nil {
		return nil, err
	}
	if br.Buffered() > 0 {
		// Bytes after the header block already belong to the tunnel.
		return &bufferedConn{Conn: conn, r: br}, nil
	}
	return conn, nil
}

// readConnectResponse reads the status line and headers, bounded in size.
func readConnectResponse(br *bufio.Reader) (connectResponse, error) {
	var resp connectResponse
	total := 0
	readLine := func() (string, error) {
		var line []byte
		for {
			chunk, isPrefix, err := br.ReadLine()
			if err != nil {
				return "", err
			}
			total += len(chunk) + 2
			if total > maxConnectHeaderBytes {
				return "", newErr(CodeProtocol, "proxy response headers too large", nil)
			}
			line = append(line, chunk...)
			if !isPrefix {
				return string(line), nil
			}
		}
	}
	status, err := readLine()
	if err != nil {
		return resp, err
	}
	// "HTTP/1.1 200 Connection established"
	if !strings.HasPrefix(status, "HTTP/1.") || len(status) < 12 || status[8] != ' ' {
		return resp, newErr(CodeProtocol, "not an HTTP proxy response", nil)
	}
	code, err := strconv.Atoi(status[9:12])
	if err != nil || code < 100 || code > 599 || (len(status) > 12 && status[12] != ' ') {
		return resp, newErr(CodeProtocol, "malformed HTTP status line", nil)
	}
	resp.Status = code
	for lines := 0; ; lines++ {
		if lines > maxConnectHeaderLines {
			return resp, newErr(CodeProtocol, "too many proxy response headers", nil)
		}
		h, err := readLine()
		if err != nil {
			return resp, err
		}
		if h == "" {
			break
		}
		name, value, ok := strings.Cut(h, ":")
		if !ok {
			continue // tolerate junk header lines; we only care about a few
		}
		if strings.EqualFold(strings.TrimSpace(name), "Proxy-Authenticate") {
			scheme, _, _ := strings.Cut(strings.TrimSpace(value), " ")
			resp.AuthSchemes = append(resp.AuthSchemes, strings.ToLower(scheme))
		}
	}
	resp.HeaderByteLen = total
	return resp, nil
}

func connectStatusError(resp connectResponse, sentCreds bool) error {
	s := resp.Status
	switch {
	case s >= 200 && s < 300:
		return nil
	case s == 407:
		if !sentCreds {
			return newErr(CodeAuthRequired, "proxy requires authentication (HTTP 407)", nil)
		}
		basic := false
		for _, sc := range resp.AuthSchemes {
			if sc == "basic" {
				basic = true
			}
		}
		if len(resp.AuthSchemes) > 0 && !basic {
			return newErr(CodeAuthFailed, fmt.Sprintf("proxy offers only %s authentication; V1 supports Basic", strings.Join(resp.AuthSchemes, ",")), nil)
		}
		return newErr(CodeAuthFailed, "proxy rejected the username or password (HTTP 407)", nil)
	case s == 403:
		return newErr(CodeTargetNotAllowed, "proxy refused this destination (HTTP 403)", nil)
	case s == 405 || s == 501:
		return newErr(CodeProtocol, fmt.Sprintf("proxy does not support CONNECT (HTTP %d)", s), nil)
	case s == 502 || s == 503 || s == 504:
		return newErr(CodeTargetUnreachable, fmt.Sprintf("proxy could not reach the destination (HTTP %d)", s), nil)
	case s >= 300 && s < 400, s == 400:
		return newErr(CodeProtocol, fmt.Sprintf("unexpected HTTP %d to CONNECT", s), nil)
	}
	return newErr(CodeProxyGeneralFailed, fmt.Sprintf("proxy answered HTTP %d", s), nil)
}

// bufferedConn returns already-buffered tunnel bytes before reading the socket.
type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.r.Read(p) }

// CloseWrite forwards half-close when the underlying connection supports it.
func (c *bufferedConn) CloseWrite() error {
	if cw, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return cw.CloseWrite()
	}
	return nil
}
