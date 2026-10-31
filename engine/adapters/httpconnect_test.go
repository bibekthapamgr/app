package adapters

import (
	"bufio"
	"bytes"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func readRequestHead(c net.Conn) string {
	br := bufio.NewReader(c)
	var sb strings.Builder
	for {
		line, err := br.ReadString('\n')
		sb.WriteString(line)
		if err != nil || line == "\r\n" {
			return sb.String()
		}
	}
}

func TestHTTPConnectRequestAndBufferedTunnelBytes(t *testing.T) {
	var head string
	cli := scriptedServer(t, func(c net.Conn) {
		head = readRequestHead(c)
		// Proxy sends the 200 and the first tunnel bytes in one write.
		c.Write([]byte("HTTP/1.1 200 Connection established\r\nVia: test\r\n\r\nHELLO"))
		io.Copy(io.Discard, c)
	})
	conn, err := httpConnectHandshake(cli, creds("user", "pass"), Target{Host: "example.com", Port: 443})
	if err != nil {
		t.Fatalf("handshake: %v", err)
	}
	want := "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\nProxy-Authorization: Basic dXNlcjpwYXNz\r\n\r\n"
	if head != want {
		t.Fatalf("request head:\n%q\nwant:\n%q", head, want)
	}
	buf := make([]byte, 5)
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "HELLO" {
		t.Fatalf("bytes after the header block must be delivered to the app, got %q %v", buf, err)
	}
}

func TestHTTPConnectIPv6LiteralAndNoCredentials(t *testing.T) {
	var head string
	cli := scriptedServer(t, func(c net.Conn) {
		head = readRequestHead(c)
		c.Write([]byte("HTTP/1.0 200 OK\r\n\r\n"))
	})
	_, err := httpConnectHandshake(cli, nil, Target{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443})
	if err != nil {
		t.Fatalf("handshake: %v", err)
	}
	if !strings.HasPrefix(head, "CONNECT [2001:db8::1]:443 HTTP/1.1\r\n") {
		t.Fatalf("IPv6 literal must be bracketed: %q", head)
	}
	if strings.Contains(head, "Proxy-Authorization") {
		t.Fatalf("no credentials configured, none may be sent: %q", head)
	}
}

func TestHTTPConnectStatusMapping(t *testing.T) {
	cases := []struct {
		resp      string
		sentCreds bool
		want      Code
	}{
		{"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"x\"\r\n\r\n", false, CodeAuthRequired},
		{"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"x\"\r\n\r\n", true, CodeAuthFailed},
		{"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Digest realm=\"x\"\r\n\r\n", true, CodeAuthFailed},
		{"HTTP/1.1 403 Forbidden\r\n\r\n", false, CodeTargetNotAllowed},
		{"HTTP/1.1 502 Bad Gateway\r\n\r\n", false, CodeTargetUnreachable},
		{"HTTP/1.1 503 Service Unavailable\r\n\r\n", false, CodeTargetUnreachable},
		{"HTTP/1.1 504 Gateway Timeout\r\n\r\n", false, CodeTargetUnreachable},
		{"HTTP/1.1 405 Method Not Allowed\r\n\r\n", false, CodeProtocol},
		{"HTTP/1.1 301 Moved\r\nLocation: x\r\n\r\n", false, CodeProtocol},
		{"SSH-2.0-OpenSSH_9.6\r\n\r\n", false, CodeProtocol},
		{"HTTP/1.1 2000 OK\r\n\r\n", false, CodeProtocol},
		{"HTTP/1.1 101 Switching Protocols\r\nUpgrade: x\r\n\r\n", false, CodeProtocol},
		{"HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 407 x\r\n\r\n", false, CodeAuthRequired},
		{"HTTP/1.1 103 Early Hints\r\nLink: x\r\n\r\nHTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 403 x\r\n\r\n", false, CodeTargetNotAllowed},
	}
	for _, tc := range cases {
		var c *Credentials
		if tc.sentCreds {
			c = creds("u", "p")
		}
		resp := tc.resp
		cli := scriptedServer(t, func(c net.Conn) {
			readRequestHead(c)
			c.Write([]byte(resp))
		})
		_, err := httpConnectHandshake(cli, c, Target{Host: "a.test", Port: 443})
		if CodeOf(err) != tc.want {
			t.Errorf("%q (creds=%v): got %v, want %s", firstLine(resp), tc.sentCreds, err, tc.want)
		}
	}
}

func TestHTTPConnectDigestOnlyIsExplained(t *testing.T) {
	err := connectStatusError(connectResponse{Status: 407, AuthSchemes: []string{"digest"}}, true)
	if err == nil || !strings.Contains(err.Error(), "digest") {
		t.Fatalf("expected a Digest-specific explanation, got %v", err)
	}
}

func TestHTTPConnectSchemesNeverEchoProxyText(t *testing.T) {
	resp, err := readConnectResponse(bufio.NewReader(strings.NewReader(
		"HTTP/1.1 407 x\r\nProxy-Authenticate: \x1b[31mEvil" + strings.Repeat("A", 500) + " realm=x\r\nProxy-Authenticate: NTLM\r\n\r\n")))
	if err != nil {
		t.Fatal(err)
	}
	err = connectStatusError(resp, true)
	if CodeOf(err) != CodeAuthFailed || strings.Contains(err.Error(), "Evil") || strings.Contains(err.Error(), "\x1b") || len(err.Error()) > 200 {
		t.Fatalf("detail must name known schemes only: %q", err)
	}
	if !strings.Contains(err.Error(), "ntlm") {
		t.Fatalf("known scheme missing from %q", err)
	}
}

func TestHTTPConnectOversizedHeaders(t *testing.T) {
	big := "HTTP/1.1 200 OK\r\nX-Junk: " + strings.Repeat("a", 20<<10) + "\r\n\r\n"
	_, err := readConnectResponse(bufio.NewReader(strings.NewReader(big)))
	if CodeOf(err) != CodeProtocol {
		t.Fatalf("headers over 16 KiB must be rejected, got %v", err)
	}
	many := "HTTP/1.1 200 OK\r\n" + strings.Repeat("A: b\r\n", 200) + "\r\n"
	_, err = readConnectResponse(bufio.NewReader(strings.NewReader(many)))
	if CodeOf(err) != CodeProtocol {
		t.Fatalf("more than 100 header lines must be rejected, got %v", err)
	}
}

// Interim 1xx responses before the 200 are skipped; the tunnel starts after
// the final response, and bytes sent with it still reach the app.
func TestHTTPConnectSkipsInterimResponses(t *testing.T) {
	cli := scriptedServer(t, func(c net.Conn) {
		readRequestHead(c)
		c.Write([]byte("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 102 Processing\r\nX: y\r\n\r\nHTTP/1.1 200 Connection established\r\n\r\nhello"))
		io.Copy(io.Discard, c)
	})
	conn, err := httpConnectHandshake(cli, nil, Target{Host: "a.test", Port: 443})
	if err != nil {
		t.Fatalf("interim responses must be skipped: %v", err)
	}
	buf := make([]byte, 5)
	_ = conn.SetReadDeadline(time.Now().Add(testTimeout))
	if _, err := io.ReadFull(conn, buf); err != nil || string(buf) != "hello" {
		t.Fatalf("tunnel bytes after the final response: %q %v", buf, err)
	}
}

// The budgets cover every response together: 1xx responses cannot be used
// to make the client read without bound.
func TestHTTPConnectInterimResponsesShareTheBudget(t *testing.T) {
	many := strings.Repeat("HTTP/1.1 100 Continue\r\n\r\n", 101) + "HTTP/1.1 200 OK\r\n\r\n"
	if _, err := readConnectResponse(bufio.NewReader(strings.NewReader(many))); CodeOf(err) != CodeProtocol {
		t.Fatalf("101 interim responses must exceed the line budget, got %v", err)
	}
	big := strings.Repeat("HTTP/1.1 103 Early Hints\r\nLink: "+strings.Repeat("a", 1000)+"\r\n\r\n", 20) + "HTTP/1.1 200 OK\r\n\r\n"
	if _, err := readConnectResponse(bufio.NewReader(strings.NewReader(big))); CodeOf(err) != CodeProtocol {
		t.Fatalf("20 KiB of interim responses must exceed the size budget, got %v", err)
	}
	few := strings.Repeat("HTTP/1.1 100 Continue\r\n\r\n", 3) + "HTTP/1.1 200 OK\r\n\r\n"
	if resp, err := readConnectResponse(bufio.NewReader(strings.NewReader(few))); err != nil || resp.Status != 200 {
		t.Fatalf("a few interim responses: %+v %v", resp, err)
	}
}

func TestHTTPConnectRejectsInjectedHostname(t *testing.T) {
	cli := scriptedServer(t, func(c net.Conn) { io.Copy(io.Discard, c) })
	_, err := httpConnectHandshake(cli, nil, Target{Host: "a.test:80 HTTP/1.1\r\nX-Evil: 1\r\n", Port: 443})
	if CodeOf(err) != CodeTargetUnsupported {
		t.Fatalf("crafted hostname must be rejected before anything is written, got %v", err)
	}
}

func firstLine(s string) string { l, _, _ := strings.Cut(s, "\r\n"); return l }

func FuzzReadConnectResponse(f *testing.F) {
	f.Add([]byte("HTTP/1.1 200 Connection established\r\n\r\n"))
	f.Add([]byte("HTTP/1.1 407 x\r\nProxy-Authenticate: Basic\r\n\r\n"))
	f.Add([]byte("HTTP/1.0 200\r\n\r\n"))
	f.Fuzz(func(t *testing.T, data []byte) {
		resp, err := readConnectResponse(bufio.NewReader(bytes.NewReader(data)))
		if err == nil {
			_ = connectStatusError(resp, true) // must never panic
		}
	})
}
