package adapters

import (
	"bufio"
	"bytes"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
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
	conn, err := httpConnectHandshake(cli, "user", "pass", Target{Host: "example.com", Port: 443})
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
	_, err := httpConnectHandshake(cli, "", "", Target{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443})
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
	}
	for _, tc := range cases {
		user := ""
		if tc.sentCreds {
			user = "u"
		}
		resp := tc.resp
		cli := scriptedServer(t, func(c net.Conn) {
			readRequestHead(c)
			c.Write([]byte(resp))
		})
		_, err := httpConnectHandshake(cli, user, "p", Target{Host: "a.test", Port: 443})
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

func TestHTTPConnectRejectsInjectedHostname(t *testing.T) {
	cli := scriptedServer(t, func(c net.Conn) { io.Copy(io.Discard, c) })
	_, err := httpConnectHandshake(cli, "", "", Target{Host: "a.test:80 HTTP/1.1\r\nX-Evil: 1\r\n", Port: 443})
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
