// Package engine connects a TUN file descriptor to the gVisor netstack and
// forwards every accepted TCP flow through the proxy dialer. It is the
// "packet engine" module of the ProxyFlow architecture.
//
// Traffic policy (ADR-0004):
//   - TCP over IPv4/IPv6: forwarded through the proxy. The upstream tunnel is
//     dialled BEFORE the app's handshake is completed, so if the proxy fails
//     the app receives a RST (connection refused) — never a direct path.
//   - UDP to port 53 (any destination): answered locally by fake-IP DNS.
//   - All other UDP: rejected (ICMP port unreachable, or silent drop).
//   - ICMP echo (ping): never answered. gVisor's network layer would answer
//     IPv6 pings for any address in promiscuous mode (also large, fragmented
//     ones), so echoFilter drops echo requests and ICMP fragments before the
//     stack and discards any echo reply or ICMP fragment on the way out
//     (echofilter.go). No ICMP transport protocol is registered.
//
// SPIKE CODE (Milestone 1).
package engine

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"

	"proxyflow/spike/engine/adapters"
	"proxyflow/spike/engine/fakedns"
)

// TunnelDialer opens a tunnel to a target through the proxy.
type TunnelDialer interface {
	Dial(ctx context.Context, t adapters.Target) (net.Conn, error)
}

// UDPRejectMode selects how non-DNS UDP is refused.
type UDPRejectMode int

const (
	UDPRejectICMP UDPRejectMode = iota // let the stack answer ICMP port-unreachable (fast QUIC fallback)
	UDPRejectDrop                      // silently drop
)

// Config configures an Engine.
type Config struct {
	FD      int    // TUN fd (IFF_TUN|IFF_NO_PI). Owned by the caller.
	MTU     uint32 // default 1500
	Dialer  TunnelDialer
	FakeDNS *fakedns.Responder // required in V1: DNS never leaves the device directly

	MaxConns        int           // concurrent TCP flows (default 1024)
	MaxInFlight     int           // pending TCP handshakes (default 512)
	DialTimeout     time.Duration // upstream tunnel establishment (default 15s)
	IdleTimeout     time.Duration // per-flow idle timeout (default 5m)
	DNSIdleTimeout  time.Duration // per-DNS-flow idle timeout (default 2s)
	TCPBufferBytes  int           // default gVisor send/receive buffer (0 = gVisor default)
	UDPReject       UDPRejectMode
	LogDestinations bool // privacy switch: include host/IP in events (default off)
	Events          func(Event)

	// WrapLink is a debug/test hook (e.g. a packet sniffer). Nil in production.
	WrapLink func(stack.LinkEndpoint) stack.LinkEndpoint
}

// Engine is a running packet engine.
type Engine struct {
	cfg    Config
	stack  *stack.Stack
	link   stack.LinkEndpoint
	echo   *echoFilter
	stats  stats
	sem    chan struct{}
	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu      sync.Mutex
	conns   map[uint64]func() // active flows -> force-close
	nextID  uint64
	stopped bool
}

const nicID tcpip.NICID = 1

// New validates cfg and builds the netstack (not yet reading packets).
func New(cfg Config) (*Engine, error) {
	if cfg.Dialer == nil || cfg.FakeDNS == nil {
		return nil, errors.New("engine: Dialer and FakeDNS are required")
	}
	if cfg.MTU == 0 {
		cfg.MTU = 1500
	}
	if cfg.MaxConns <= 0 {
		cfg.MaxConns = 1024
	}
	if cfg.MaxInFlight <= 0 {
		cfg.MaxInFlight = 512
	}
	if cfg.DialTimeout <= 0 {
		cfg.DialTimeout = 15 * time.Second
	}
	if cfg.IdleTimeout <= 0 {
		cfg.IdleTimeout = 5 * time.Minute
	}
	if cfg.DNSIdleTimeout <= 0 {
		cfg.DNSIdleTimeout = 2 * time.Second
	}
	ctx, cancel := context.WithCancel(context.Background())
	e := &Engine{cfg: cfg, sem: make(chan struct{}, cfg.MaxConns), ctx: ctx, cancel: cancel, conns: map[uint64]func(){}}
	e.stats.init()
	return e, nil
}

// Start creates the NIC on the TUN fd and begins processing packets.
func (e *Engine) Start() error {
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		// Deliberately no icmp.NewProtocol4/6 and no ICMP echo forwarder.
	})
	e.stack = s

	sack := tcpip.TCPSACKEnabled(true)
	_ = s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)
	if b := e.cfg.TCPBufferBytes; b > 0 {
		rcv := tcpip.TCPReceiveBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: b, Max: max(b, tcp.MaxBufferSize)}
		snd := tcpip.TCPSendBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: b, Max: max(b, tcp.MaxBufferSize)}
		_ = s.SetTransportProtocolOption(tcp.ProtocolNumber, &rcv)
		_ = s.SetTransportProtocolOption(tcp.ProtocolNumber, &snd)
	}

	// Transport handlers must be installed before the NIC starts delivering.
	tcpFwd := tcp.NewForwarder(s, 0, e.cfg.MaxInFlight, e.handleTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, e.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	tun, err := fdbased.New(&fdbased.Options{
		FDs:                []int{e.cfg.FD},
		MTU:                e.cfg.MTU,
		EthernetHeader:     false,
		PacketDispatchMode: fdbased.Readv,
		RXChecksumOffload:  true, // packets come from the local kernel over TUN
	})
	if err != nil {
		return fmt.Errorf("fdbased endpoint: %w", err)
	}
	var link stack.LinkEndpoint = tun
	if e.cfg.WrapLink != nil {
		link = e.cfg.WrapLink(link) // debug sniffer sees packets as the TUN carries them
	}
	e.echo = newEchoFilter(link)
	e.link = e.echo
	if terr := s.CreateNIC(nicID, e.echo); terr != nil {
		return fmt.Errorf("create NIC: %s", terr)
	}
	// Accept packets for every destination address and answer from it.
	if terr := s.SetPromiscuousMode(nicID, true); terr != nil {
		return fmt.Errorf("promiscuous: %s", terr)
	}
	if terr := s.SetSpoofing(nicID, true); terr != nil {
		return fmt.Errorf("spoofing: %s", terr)
	}
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})
	e.emit(Event{Type: EvEngineStarted})
	return nil
}

// Stop closes every flow, tears the stack down and waits for all goroutines.
// It does not close the TUN fd (the VpnService owns it).
func (e *Engine) Stop() {
	e.mu.Lock()
	if e.stopped {
		e.mu.Unlock()
		return
	}
	e.stopped = true
	closers := make([]func(), 0, len(e.conns))
	for _, c := range e.conns {
		closers = append(closers, c)
	}
	e.mu.Unlock()

	e.cancel()
	for _, c := range closers {
		c()
	}
	if e.stack != nil {
		e.stack.Close()
		e.stack.Wait()
	}
	e.wg.Wait()
	e.emit(Event{Type: EvEngineStopped})
}

func (e *Engine) track(closer func()) (uint64, bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.stopped {
		return 0, false
	}
	e.nextID++
	e.conns[e.nextID] = closer
	return e.nextID, true
}

func (e *Engine) untrack(id uint64) {
	e.mu.Lock()
	delete(e.conns, id)
	e.mu.Unlock()
}

func (e *Engine) emit(ev Event) {
	if e.cfg.Events != nil {
		ev.Time = time.Now()
		e.cfg.Events(ev)
	}
}

func toNetip(a tcpip.Address) netip.Addr {
	ip, _ := netip.AddrFromSlice(a.AsSlice())
	return ip.Unmap()
}
