package adapters

import (
	"bytes"
	"errors"
	"io"
	"net"
	"net/netip"
	"testing"
)

// scriptedServer runs fn on the server end of an in-memory connection.
func scriptedServer(t *testing.T, fn func(c net.Conn)) net.Conn {
	t.Helper()
	cli, srv := net.Pipe()
	go func() {
		defer srv.Close()
		fn(srv)
	}()
	t.Cleanup(func() { cli.Close() })
	return cli
}

func readN(c net.Conn, n int) []byte {
	b := make([]byte, n)
	_, _ = io.ReadFull(c, b)
	return b
}

func TestSOCKS5NoAuthDomainTarget(t *testing.T) {
	var gotGreeting, gotReq []byte
	cli := scriptedServer(t, func(c net.Conn) {
		gotGreeting = readN(c, 3)
		c.Write([]byte{5, 0})
		gotReq = readN(c, 4+1+len("example.com")+2)
		c.Write([]byte{5, 0, 0, 1, 10, 0, 0, 1, 0x1f, 0x90})
	})
	err := socks5Handshake(cli, nil, Target{Host: "example.com", Port: 80})
	if err != nil {
		t.Fatalf("handshake: %v", err)
	}
	if !bytes.Equal(gotGreeting, []byte{5, 1, 0}) {
		t.Fatalf("greeting = %v; must offer only no-auth when no credentials", gotGreeting)
	}
	want := append([]byte{5, 1, 0, 3, 11}, "example.com"...)
	want = append(want, 0, 80)
	if !bytes.Equal(gotReq, want) {
		t.Fatalf("request = %v, want %v (hostname must be sent for remote resolution)", gotReq, want)
	}
}

func TestSOCKS5UserPassSuccessIPv6Reply(t *testing.T) {
	var auth []byte
	cli := scriptedServer(t, func(c net.Conn) {
		if g := readN(c, 4); !bytes.Equal(g, []byte{5, 2, 0, 2}) {
			return
		}
		c.Write([]byte{5, 2})
		auth = readN(c, 3+len("alice")+len("s3cret"))
		c.Write([]byte{1, 0})
		readN(c, 10) // IPv4 CONNECT request
		reply := []byte{5, 0, 0, 4}
		reply = append(reply, make([]byte, 16)...)
		reply = append(reply, 0, 1)
		c.Write(reply)
	})
	err := socks5Handshake(cli, creds("alice", "s3cret"), Target{Addr: netip.MustParseAddr("192.0.2.7"), Port: 443})
	if err != nil {
		t.Fatalf("handshake: %v", err)
	}
	want := append([]byte{1, 5}, "alice"...)
	want = append(want, 6)
	want = append(want, "s3cret"...)
	if !bytes.Equal(auth, want) {
		t.Fatalf("RFC 1929 sub-negotiation = %v, want %v", auth, want)
	}
}

func TestSOCKS5AuthFailures(t *testing.T) {
	t.Run("rejected credentials", func(t *testing.T) {
		cli := scriptedServer(t, func(c net.Conn) {
			readN(c, 4)
			c.Write([]byte{5, 2})
			readN(c, 3+1+1)
			c.Write([]byte{1, 1})
		})
		err := socks5Handshake(cli, creds("u", "p"), Target{Host: "a.test", Port: 1})
		if CodeOf(err) != CodeAuthFailed {
			t.Fatalf("got %v, want AUTH_FAILED", err)
		}
	})
	t.Run("auth required but none configured", func(t *testing.T) {
		cli := scriptedServer(t, func(c net.Conn) {
			readN(c, 3)
			c.Write([]byte{5, 0xFF})
		})
		err := socks5Handshake(cli, nil, Target{Host: "a.test", Port: 1})
		if CodeOf(err) != CodeAuthRequired {
			t.Fatalf("got %v, want AUTH_REQUIRED", err)
		}
	})
	t.Run("no acceptable method although credentials were offered", func(t *testing.T) {
		cli := scriptedServer(t, func(c net.Conn) {
			readN(c, 4)
			c.Write([]byte{5, 0xFF})
		})
		err := socks5Handshake(cli, creds("u", "p"), Target{Host: "a.test", Port: 1})
		if CodeOf(err) != CodeAuthFailed {
			t.Fatalf("got %v, want AUTH_FAILED", err)
		}
	})
	t.Run("server picks method we did not offer", func(t *testing.T) {
		cli := scriptedServer(t, func(c net.Conn) {
			readN(c, 3)
			c.Write([]byte{5, 2})
		})
		err := socks5Handshake(cli, nil, Target{Host: "a.test", Port: 1})
		if CodeOf(err) != CodeProtocol {
			t.Fatalf("got %v, want PROTOCOL_ERROR (credentials must not be sent unasked)", err)
		}
	})
}

func TestSOCKS5ReplyCodes(t *testing.T) {
	cases := map[byte]Code{
		0x01: CodeProxyGeneralFailed, 0x02: CodeTargetNotAllowed, 0x03: CodeTargetUnreachable,
		0x04: CodeTargetUnreachable, 0x05: CodeTargetRefused, 0x06: CodeTargetUnreachable,
		0x07: CodeTargetUnsupported, 0x08: CodeTargetUnsupported, 0x09: CodeProxyGeneralFailed,
	}
	for rep, want := range cases {
		// Only the 4 header bytes: a failure reply must be reported even when
		// the server closes before sending BND.ADDR/BND.PORT.
		err := readSOCKS5Reply(bytes.NewReader([]byte{5, rep, 0, 1}))
		if CodeOf(err) != want {
			t.Errorf("REP 0x%02x: got %v, want %s", rep, err, want)
		}
		if want.IsProxyLevel() {
			t.Errorf("REP 0x%02x must be target-level, not proxy-level", rep)
		}
	}
}

func TestSOCKS5NotASocksServer(t *testing.T) {
	cli := scriptedServer(t, func(c net.Conn) {
		readN(c, 3)
		c.Write([]byte("HTTP/1.1 400 Bad Request\r\n\r\n"))
	})
	err := socks5Handshake(cli, nil, Target{Host: "a.test", Port: 1})
	if CodeOf(err) != CodeProtocol {
		t.Fatalf("got %v, want PROTOCOL_ERROR", err)
	}
}

func TestSOCKS5RequestEncoding(t *testing.T) {
	v4, _ := socks5ConnectRequest(Target{Addr: netip.MustParseAddr("::ffff:1.2.3.4"), Port: 8080})
	if !bytes.Equal(v4, []byte{5, 1, 0, 1, 1, 2, 3, 4, 0x1f, 0x90}) {
		t.Fatalf("IPv4-mapped target must be sent as ATYP IPv4: %v", v4)
	}
	v6, _ := socks5ConnectRequest(Target{Addr: netip.MustParseAddr("2001:db8::1"), Port: 443})
	if v6[3] != atypIPv6 || len(v6) != 4+16+2 {
		t.Fatalf("IPv6 encoding wrong: %v", v6)
	}
	if _, err := socks5ConnectRequest(Target{Host: "evil.test\r\nX: y", Port: 80}); CodeOf(err) != CodeTargetUnsupported {
		t.Fatalf("hostname with CR/LF must be rejected, got %v", err)
	}
	long := bytes.Repeat([]byte("a"), 63)
	name := string(long) + "." + string(long) + "." + string(long) + "." + string(long) // 255 bytes
	if _, err := socks5ConnectRequest(Target{Host: name, Port: 80}); err == nil {
		t.Fatalf("over-long hostname must be rejected")
	}
}

func TestSOCKS5TruncatedReply(t *testing.T) {
	cli := scriptedServer(t, func(c net.Conn) {
		readN(c, 3)
		c.Write([]byte{5, 0})
		readN(c, 4+1+6+2)
		c.Write([]byte{5, 0, 0}) // then close
	})
	err := socks5Handshake(cli, nil, Target{Host: "a.test", Port: 80})
	if err == nil || !(errors.Is(err, io.ErrUnexpectedEOF) || errors.Is(err, io.EOF)) {
		t.Fatalf("truncated reply must fail, got %v", err)
	}
}

func FuzzReadSOCKS5Reply(f *testing.F) {
	f.Add([]byte{5, 0, 0, 1, 1, 2, 3, 4, 0, 80})
	f.Add([]byte{5, 0, 0, 3, 3, 'a', 'b', 'c', 0, 80})
	f.Add([]byte{5, 1, 0, 1})
	f.Fuzz(func(t *testing.T, data []byte) {
		_ = readSOCKS5Reply(bytes.NewReader(data)) // must never panic
	})
}
