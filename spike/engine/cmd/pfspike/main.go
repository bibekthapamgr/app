// Command pfspike runs the ProxyFlow spike engine on a Linux TUN device.
//
// It is the Linux stand-in for the Android VpnService integration:
//   - the TUN fd is opened with IFF_TUN|IFF_NO_PI, the same format Android's
//     VpnService.Builder.establish() returns;
//   - upstream sockets get SO_MARK, which policy routing sends to the
//     underlying network — the same mechanism Android's netd uses for
//     VpnService.protect() / sockets of an app excluded from the VPN.
//
// Credentials are read from PF_PROXY_USERNAME / PF_PROXY_PASSWORD, never argv.
//
// SPIKE CODE (Milestone 1).
package main

import (
	"bufio"
	"context"
	"crypto/x509"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net"
	"net/netip"
	"net/url"
	"os"
	"os/signal"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"golang.org/x/sys/unix"

	"proxyflow/spike/engine/adapters"
	"proxyflow/spike/engine/engine"
	"proxyflow/spike/engine/fakedns"
)

func main() {
	var (
		tunName   = flag.String("tun", "tun0", "TUN interface to attach to")
		mtu       = flag.Uint("mtu", 1500, "MTU")
		proxyURL  = flag.String("proxy", "", "socks5://host:port | http://host:port | socks5+tls://... | http+tls://...")
		sni       = flag.String("sni", "", "TLS server name (default: proxy host)")
		caFile    = flag.String("ca", "", "PEM trust anchors for TLS-to-proxy (default: system roots)")
		mark      = flag.Int("mark", 438, "SO_MARK for upstream sockets (emulates VpnService.protect)")
		proxyDNS  = flag.String("proxy-dns", "", "resolver ip:port on the underlying network for the proxy hostname")
		pool      = flag.String("pool", "198.18.0.0/15", "fake-IP pool")
		reserved  = flag.Int("reserved", 256, "addresses at the start of the pool kept for TUN/DNS")
		ttl       = flag.Uint("dns-ttl", 5, "fake DNS TTL seconds")
		maxConns  = flag.Int("max-conns", 1024, "max concurrent TCP flows")
		tcpBuf    = flag.Int("tcp-buf", 0, "gVisor default TCP buffer bytes (0 = gVisor default)")
		udpReject = flag.String("udp-reject", "icmp", "icmp | drop")
		logDst    = flag.Bool("log-destinations", false, "include destinations in events (privacy switch)")
		statsFile = flag.String("stats", "", "write JSON stats snapshots here")
		eventFile = flag.String("events", "", "write JSONL events here")
		readyFile = flag.String("ready", "", "touch this file once the engine is running")
		dialTO    = flag.Duration("dial-timeout", 10*time.Second, "upstream dial timeout")
	)
	flag.Parse()

	cfg, err := parseProxy(*proxyURL)
	if err != nil {
		log.Fatalf("proxy: %v", err)
	}
	cfg.Username = os.Getenv("PF_PROXY_USERNAME")
	cfg.Password = os.Getenv("PF_PROXY_PASSWORD")
	_ = os.Unsetenv("PF_PROXY_PASSWORD")
	cfg.DialTimeout = *dialTO
	if cfg.TLS != nil {
		cfg.TLS.ServerName = *sni
		if *caFile != "" {
			pem, err := os.ReadFile(*caFile)
			if err != nil {
				log.Fatalf("ca: %v", err)
			}
			cp := x509.NewCertPool()
			if !cp.AppendCertsFromPEM(pem) {
				log.Fatalf("ca: no certificates in %s", *caFile)
			}
			cfg.TLS.RootCAs = cp
		}
	}

	protect := func(fd uintptr) error {
		return unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, *mark)
	}
	var resolver *net.Resolver
	if *proxyDNS != "" {
		nd := &net.Dialer{Timeout: 5 * time.Second, Control: func(_, _ string, c syscall.RawConn) error {
			var perr error
			if err := c.Control(func(fd uintptr) { perr = protect(fd) }); err != nil {
				return err
			}
			return perr
		}}
		resolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return nd.DialContext(ctx, network, *proxyDNS)
		}}
	}
	dialer, err := adapters.NewDialer(cfg, protect, resolver)
	if err != nil {
		log.Fatalf("dialer: %v", err)
	}

	prefix, err := netip.ParsePrefix(*pool)
	if err != nil {
		log.Fatalf("pool: %v", err)
	}
	fp, err := fakedns.NewPool(prefix, *reserved, 0)
	if err != nil {
		log.Fatalf("pool: %v", err)
	}

	fd, err := openTUN(*tunName)
	if err != nil {
		log.Fatalf("tun: %v", err)
	}

	var evMu sync.Mutex
	var evOut *bufio.Writer
	if *eventFile != "" {
		f, err := os.OpenFile(*eventFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
		if err != nil {
			log.Fatalf("events: %v", err)
		}
		defer f.Close()
		evOut = bufio.NewWriter(f)
	}
	emit := func(ev engine.Event) {
		if evOut == nil {
			return
		}
		b, _ := json.Marshal(ev)
		evMu.Lock()
		evOut.Write(b)
		evOut.WriteByte('\n')
		evOut.Flush()
		evMu.Unlock()
	}

	mode := engine.UDPRejectICMP
	if *udpReject == "drop" {
		mode = engine.UDPRejectDrop
	}
	eng, err := engine.New(engine.Config{
		FD: fd, MTU: uint32(*mtu), Dialer: dialer,
		FakeDNS:  &fakedns.Responder{Pool: fp, TTL: uint32(*ttl)},
		MaxConns: *maxConns, TCPBufferBytes: *tcpBuf, UDPReject: mode,
		LogDestinations: *logDst, Events: emit, DialTimeout: *dialTO + 5*time.Second,
	})
	if err != nil {
		log.Fatalf("engine: %v", err)
	}
	if err := eng.Start(); err != nil {
		log.Fatalf("start: %v", err)
	}
	log.Printf("pfspike: engine up on %s via %s", *tunName, cfg.String())
	if *readyFile != "" {
		_ = os.WriteFile(*readyFile, []byte(strconv.Itoa(os.Getpid())), 0o644)
	}

	writeStats := func(extra map[string]any) {
		if *statsFile == "" {
			return
		}
		snap := eng.Stats()
		var out any = snap
		if extra != nil {
			b, _ := json.Marshal(snap)
			m := map[string]any{}
			_ = json.Unmarshal(b, &m)
			for k, v := range extra {
				m[k] = v
			}
			out = m
		}
		b, _ := json.MarshalIndent(out, "", "  ")
		tmp := *statsFile + ".tmp"
		if os.WriteFile(tmp, b, 0o644) == nil {
			_ = os.Rename(tmp, *statsFile)
		}
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-tick.C:
			writeStats(nil)
		case <-sig:
			before := runtime.NumGoroutine()
			activeAtStop := eng.Stats().TCPActive
			t0 := time.Now()
			eng.Stop() // closes every flow and waits for its goroutines
			stopMs := time.Since(t0).Milliseconds()
			// Settle before counting goroutines (GC + 200 ms); not part of stop_ms.
			runtime.GC()
			time.Sleep(200 * time.Millisecond)
			writeStats(map[string]any{
				"stopped":                true,
				"stop_ms":                stopMs,
				"settle_ms":              200,
				"tcp_active_at_stop":     activeAtStop,
				"goroutines_before_stop": before,
				"goroutines_after_stop":  runtime.NumGoroutine(),
			})
			unix.Close(fd)
			log.Printf("pfspike: stopped cleanly")
			return
		}
	}
}

func parseProxy(raw string) (adapters.ProxyConfig, error) {
	var cfg adapters.ProxyConfig
	u, err := url.Parse(raw)
	if err != nil {
		return cfg, err
	}
	if u.User != nil {
		return cfg, fmt.Errorf("put credentials in PF_PROXY_USERNAME/PF_PROXY_PASSWORD, not the URL")
	}
	scheme := u.Scheme
	if strings.HasSuffix(scheme, "+tls") {
		cfg.TLS = &adapters.TLSOptions{}
		scheme = strings.TrimSuffix(scheme, "+tls")
	}
	switch scheme {
	case "socks5":
		cfg.Kind = adapters.KindSOCKS5
	case "http":
		cfg.Kind = adapters.KindHTTPConnect
	default:
		return cfg, fmt.Errorf("unsupported scheme %q", u.Scheme)
	}
	cfg.Host = u.Hostname()
	p, err := strconv.Atoi(u.Port())
	if err != nil || p < 1 || p > 65535 {
		return cfg, fmt.Errorf("bad port")
	}
	cfg.Port = uint16(p)
	return cfg, nil
}
