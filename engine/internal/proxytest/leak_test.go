package proxytest

import (
	"net"
	"os"
	"testing"
	"time"
)

// The leak check must see a goroutine and a descriptor the code under test
// left behind, and stop seeing them once they are gone.
func TestLeakCheckDetectsLeaks(t *testing.T) {
	base := takeSnapshot()
	release := make(chan struct{})
	exited := make(chan struct{})
	go func() {
		<-release
		close(exited)
	}()
	f, err := os.Open(os.Args[0])
	if err != nil {
		t.Fatal(err)
	}
	g, fd := base.leaks()
	if len(g) != 1 {
		t.Fatalf("leaked goroutines = %d, want 1:\n%v", len(g), g)
	}
	if FDCount() >= 0 && len(fd) != 1 {
		t.Fatalf("leaked descriptors = %v, want 1", fd)
	}
	close(release)
	<-exited
	f.Close()
	deadline := time.Now().Add(2 * time.Second)
	for {
		g, fd = base.leaks()
		if len(g) == 0 && len(fd) == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("still reported after cleanup: %v %v", g, fd)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestFixturesCleanUpAfterThemselves(t *testing.T) {
	base := takeSnapshot()
	t.Run("fixtures", func(t *testing.T) {
		echo := NewEcho(t, "tcp4")
		s := NewServer(t, Config{Protocol: SOCKS5, Hosts: map[string]string{"e.test": echo.String()}, ReplyDelay: time.Hour})
		h := NewServer(t, Config{Protocol: HTTPConnect, Silent: true})
		for _, addr := range []string{s.Addr.String(), h.Addr.String(), echo.String()} {
			c, err := dialTCP(addr)
			if err != nil {
				t.Fatal(err)
			}
			_, _ = c.Write([]byte{5, 1, 0})
			defer c.Close()
		}
	})
	deadline := time.Now().Add(3 * time.Second)
	for {
		g, fd := base.leaks()
		if len(g) == 0 && len(fd) == 0 {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("fixtures left goroutines or descriptors behind:\n%v\n%v", g, fd)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func dialTCP(addr string) (net.Conn, error) { return net.DialTimeout("tcp", addr, time.Second) }
