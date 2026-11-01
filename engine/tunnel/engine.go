// Package tunnel connects a TUN file descriptor to the gVisor netstack and
// forwards every TCP flow through the proxy dialer: the packet engine of
// ProxyFlow (docs/m1/06-architecture.md §3–5).
//
// Traffic policy (ADR-0004):
//   - TCP over IPv4/IPv6: forwarded through the proxy. The tunnel to the proxy
//     is opened BEFORE the app's handshake is completed, so if the proxy
//     fails the app receives a RST (connection refused), never a direct path.
//   - UDP to port 53 (any destination): answered locally by fake-IP DNS
//     (ADR-0003); no query leaves the device.
//   - All other UDP: rejected (ICMP port unreachable, or silent drop).
//   - ICMP echo (ping): never answered. gVisor's network layer would answer
//     IPv6 pings for any address in promiscuous mode (also large, fragmented
//     ones), so echoFilter drops echo requests and ICMP fragments before the
//     stack and discards any echo reply or ICMP fragment on the way out
//     (echofilter.go). No ICMP transport protocol is registered.
//
// Lifecycle: New → Start → (running; ResetFlows any number of times) → Stop.
// Stop ends every flow, cancels tunnels being set up, tears the stack down and
// waits for every goroutine; it never closes the TUN fd, which the caller
// (the VpnService) owns. If the packet loop ends on its own (the TUN fd was
// closed or failed), the engine moves to StateFailed and reports
// ENGINE_STOPPED; the caller still calls Stop.
package tunnel

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"

	"github.com/bibekthapamgr/app/engine/adapters"
	"github.com/bibekthapamgr/app/engine/fakedns"
)

// TunnelDialer opens a tunnel to a target through the proxy
// (*adapters.Dialer in production).
type TunnelDialer interface {
	Dial(ctx context.Context, t adapters.Target) (net.Conn, error)
}

// UDPRejectMode selects how non-DNS UDP is refused.
type UDPRejectMode int

const (
	UDPRejectICMP UDPRejectMode = iota // the stack answers ICMP port unreachable (fast QUIC fallback)
	UDPRejectDrop                      // silently drop
)

// CodeEngineStopped is reported when the packet loop ends without Stop
// (catalogue code ENGINE_STOPPED, docs/m1/04 §4).
const CodeEngineStopped = "ENGINE_STOPPED"

// Defaults, matching docs/m1/06-architecture.md §5.
const (
	DefaultMTU              = 1500
	DefaultMaxConns         = 1024
	DefaultMaxInFlight      = 512
	DefaultMaxDNSFlows      = 256
	DefaultMaxDNSOverTCP    = 32
	DefaultDialTimeout      = 15 * time.Second
	DefaultIdleTimeout      = 5 * time.Minute
	DefaultDNSIdleTimeout   = 2 * time.Second
	DefaultRelayBufferBytes = 32 << 10
	DefaultTCPBufferBytes   = 64 << 10 // netstack send and receive buffer per flow; the maximum is twice this
	DefaultEventQueue       = 1024
	eventDrainTimeout       = 2 * time.Second
	eventAbandonGrace       = 500 * time.Millisecond
)

// Config configures an Engine.
type Config struct {
	FD      int    // TUN fd (IFF_TUN|IFF_NO_PI, or a datagram socket in tests). Owned by the caller.
	MTU     uint32 // default 1500
	Dialer  TunnelDialer
	FakeDNS *fakedns.Responder // required: DNS never leaves the device directly

	MaxConns    int // concurrent TCP flows, including ones being set up (default 1024)
	MaxInFlight int // TCP handshakes the stack holds open while tunnels are set up (default 512)
	MaxDNSFlows int // concurrent DNS client sockets (default 256)
	// MaxDNSOverTCP bounds DNS-over-TCP connections apart from MaxDNSFlows, so that
	// connections an app keeps open cannot take the slots DNS over UDP needs (default 32).
	MaxDNSOverTCP int
	// LocalAddrs are the TUN's own addresses outside the placeholder pool (its IPv6
	// address): like the pool's reserved addresses, TCP to them other than DNS is
	// refused on the phone, there being nothing to proxy (ADR-0011).
	LocalAddrs     []netip.Addr
	DialTimeout    time.Duration // whole tunnel set-up per flow (default 15 s)
	IdleTimeout    time.Duration // per-flow idle timeout, both directions together (default 5 min)
	DNSIdleTimeout time.Duration // per-DNS-socket idle timeout (default 2 s)
	// TCPBufferBytes is the netstack's send and receive buffer per flow (default
	// 64 KiB, at most twice that, never auto-tuned upwards). The netstack only
	// faces the apps on the phone, where round trips take microseconds, so small
	// buffers cost little throughput and bound what a stalled flow holds in
	// memory (gVisor's own defaults are 1 MiB growing to 4 MiB, per direction).
	TCPBufferBytes   int
	RelayBufferBytes int // relay buffer per direction per flow (default 32 KiB)
	UDPReject        UDPRejectMode
	LogDestinations  bool // privacy switch: include host/IP in events and flows (default off)

	// Events receives log-safe events on one dedicated goroutine, in order.
	// A slow receiver never stalls traffic: when EventQueue events are
	// waiting, new ones are dropped and counted (Snapshot.EventsDropped).
	Events     func(Event)
	EventQueue int // default 1024

	// WrapLink is a debug/test hook (e.g. a packet sniffer). Nil in production.
	WrapLink func(stack.LinkEndpoint) stack.LinkEndpoint
}

// State is the engine lifecycle.
type State int32

const (
	StateNew      State = iota // built, not started
	StateRunning               // processing packets
	StateFailed                // the packet loop ended without Stop (TUN closed or failed); call Stop
	StateStopping              // Stop in progress
	StateStopped               // stopped; an engine is not restarted, build a new one
)

func (s State) String() string {
	switch s {
	case StateNew:
		return "new"
	case StateRunning:
		return "running"
	case StateFailed:
		return "failed"
	case StateStopping:
		return "stopping"
	case StateStopped:
		return "stopped"
	}
	return "unknown"
}

// ErrorInfo is a log-safe error with its time.
type ErrorInfo struct {
	Code   string    `json:"code"`
	Detail string    `json:"detail,omitempty"`
	Time   time.Time `json:"time"`
}

// Engine is a packet engine.
type Engine struct {
	cfg     Config
	stack   atomic.Pointer[stack.Stack]
	echo    atomic.Pointer[echoFilter]
	stats   stats
	sem     chan struct{} // MaxConns slots
	dnsSem  chan struct{} // MaxDNSFlows slots
	tcpDNS  chan struct{} // MaxDNSOverTCP slots
	ctx     context.Context
	cancel  context.CancelFunc
	wg      sync.WaitGroup // flow goroutines, only added under mu while running
	bufPool sync.Pool

	mu        sync.Mutex
	state     State
	flows     map[uint64]*flow
	nextID    uint64
	startedAt time.Time
	lastErr   *ErrorInfo
	stopped   chan struct{} // closed when Stop has finished

	events    chan Event
	evMu      sync.RWMutex
	evClosed  bool
	evDone    chan struct{}
	evDropped atomic.Int64
	evState   atomic.Int32 // evIdle, evDelivering or evAbandoned (Stop gave up waiting)
}

const nicID tcpip.NICID = 1

// New validates cfg and builds an engine (not yet reading packets).
func New(cfg Config) (*Engine, error) {
	if cfg.Dialer == nil || cfg.FakeDNS == nil || cfg.FakeDNS.Pool == nil {
		return nil, errors.New("engine: Dialer and FakeDNS are required")
	}
	if cfg.FD < 0 {
		return nil, errors.New("engine: invalid TUN file descriptor")
	}
	setDefault := func(v *int, d int) {
		if *v <= 0 {
			*v = d
		}
	}
	setDefault(&cfg.MaxConns, DefaultMaxConns)
	setDefault(&cfg.MaxInFlight, DefaultMaxInFlight)
	setDefault(&cfg.MaxDNSFlows, DefaultMaxDNSFlows)
	setDefault(&cfg.MaxDNSOverTCP, DefaultMaxDNSOverTCP)
	setDefault(&cfg.RelayBufferBytes, DefaultRelayBufferBytes)
	setDefault(&cfg.TCPBufferBytes, DefaultTCPBufferBytes)
	if cfg.TCPBufferBytes < 4<<10 {
		return nil, fmt.Errorf("engine: TCP buffer %d below 4 KiB", cfg.TCPBufferBytes)
	}
	setDefault(&cfg.EventQueue, DefaultEventQueue)
	if cfg.MTU == 0 {
		cfg.MTU = DefaultMTU
	}
	if cfg.MTU < 1280 || cfg.MTU > 65535 {
		return nil, fmt.Errorf("engine: MTU %d outside 1280-65535", cfg.MTU)
	}
	setDur := func(v *time.Duration, d time.Duration) {
		if *v <= 0 {
			*v = d
		}
	}
	setDur(&cfg.DialTimeout, DefaultDialTimeout)
	setDur(&cfg.IdleTimeout, DefaultIdleTimeout)
	setDur(&cfg.DNSIdleTimeout, DefaultDNSIdleTimeout)

	ctx, cancel := context.WithCancel(context.Background())
	e := &Engine{
		cfg: cfg, sem: make(chan struct{}, cfg.MaxConns), dnsSem: make(chan struct{}, cfg.MaxDNSFlows), tcpDNS: make(chan struct{}, cfg.MaxDNSOverTCP),
		ctx: ctx, cancel: cancel, flows: map[uint64]*flow{}, stopped: make(chan struct{}),
	}
	size := cfg.RelayBufferBytes
	e.bufPool.New = func() any { b := make([]byte, size); return &b }
	e.stats.init()
	return e, nil
}

// Start builds the netstack on the TUN fd and begins processing packets. On
// error nothing is left running; the engine is then stopped.
func (e *Engine) Start() (err error) {
	e.mu.Lock()
	if e.state != StateNew {
		st := e.state
		e.mu.Unlock()
		return fmt.Errorf("engine: cannot start in state %s", st)
	}
	e.mu.Unlock()
	e.startEvents()

	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
		// Deliberately no icmp.NewProtocol4/6 and no ICMP echo forwarder.
	})
	defer func() {
		if err != nil {
			e.mu.Lock()
			e.state = StateStopping
			e.mu.Unlock()
			e.cancel()
			s.Close()
			s.Wait()
			e.wg.Wait()
			e.mu.Lock()
			e.state = StateStopped
			e.lastErr = &ErrorInfo{Code: string(adapters.CodeInternal), Detail: err.Error(), Time: time.Now()}
			e.mu.Unlock()
			e.closeEvents()
			close(e.stopped)
		}
	}()

	sack := tcpip.TCPSACKEnabled(true)
	if terr := s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack); terr != nil {
		return fmt.Errorf("engine: enable SACK: %s", terr)
	}
	b := e.cfg.TCPBufferBytes
	rcv := tcpip.TCPReceiveBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: b, Max: 2 * b}
	snd := tcpip.TCPSendBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: b, Max: 2 * b}
	moderate := tcpip.TCPModerateReceiveBufferOption(false) // no auto-tuning past the maximum
	if terr := s.SetTransportProtocolOption(tcp.ProtocolNumber, &rcv); terr != nil {
		return fmt.Errorf("engine: TCP receive buffer: %s", terr)
	}
	if terr := s.SetTransportProtocolOption(tcp.ProtocolNumber, &snd); terr != nil {
		return fmt.Errorf("engine: TCP send buffer: %s", terr)
	}
	if terr := s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate); terr != nil {
		return fmt.Errorf("engine: TCP receive buffer moderation: %s", terr)
	}

	// Transport handlers must be installed before the NIC starts delivering.
	// The forwarder sets each accepted flow's receive buffer itself (gVisor's
	// default would be 1 MiB, whatever the protocol options say).
	tcpFwd := tcp.NewForwarder(s, 2*b, e.cfg.MaxInFlight, e.handleTCP)
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)
	udpFwd := udp.NewForwarder(s, e.handleUDP)
	s.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)

	tun, ferr := fdbased.New(&fdbased.Options{
		FDs:                []int{e.cfg.FD},
		MTU:                e.cfg.MTU,
		EthernetHeader:     false,
		PacketDispatchMode: fdbased.Readv,
		RXChecksumOffload:  true, // packets come from the local kernel over TUN
		ClosedFunc:         e.packetLoopEnded,
	})
	if ferr != nil {
		return fmt.Errorf("engine: TUN endpoint: %w", ferr)
	}
	var link stack.LinkEndpoint = tun
	if e.cfg.WrapLink != nil {
		link = e.cfg.WrapLink(link) // a debug sniffer sees packets as the TUN carries them
	}
	echo := newEchoFilter(link)
	e.echo.Store(echo)
	e.stack.Store(s)
	e.mu.Lock()
	e.state = StateRunning
	e.startedAt = time.Now()
	e.mu.Unlock()

	if terr := s.CreateNIC(nicID, echo); terr != nil {
		return fmt.Errorf("engine: create NIC: %s", terr)
	}
	// Accept packets for every destination address and answer from it.
	if terr := s.SetPromiscuousMode(nicID, true); terr != nil {
		return fmt.Errorf("engine: promiscuous mode: %s", terr)
	}
	if terr := s.SetSpoofing(nicID, true); terr != nil {
		return fmt.Errorf("engine: spoofing: %s", terr)
	}
	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})
	e.emit(Event{Type: EvEngineStarted})
	return nil
}

// packetLoopEnded is fdbased's ClosedFunc: it runs when the TUN read loop
// ends, also during Stop. Outside Stop it means the TUN fd closed or failed.
func (e *Engine) packetLoopEnded(terr tcpip.Error) {
	e.mu.Lock()
	if e.state != StateRunning {
		e.mu.Unlock()
		return
	}
	e.state = StateFailed
	detail := "the TUN interface was closed"
	if terr != nil {
		detail = "reading the TUN interface failed: " + terr.String()
	}
	e.lastErr = &ErrorInfo{Code: CodeEngineStopped, Detail: detail, Time: time.Now()}
	e.mu.Unlock()
	e.emit(Event{Type: EvEngineFailed, Code: CodeEngineStopped, Detail: detail})
}

// Stop ends every flow, cancels tunnels being set up, tears the stack down
// and waits for all goroutines. It does not close the TUN fd. It is safe to
// call more than once and from several goroutines; every call returns after
// the engine has stopped.
func (e *Engine) Stop() {
	e.mu.Lock()
	switch e.state {
	case StateStopping, StateStopped:
		e.mu.Unlock()
		<-e.stopped
		return
	case StateNew:
		e.state = StateStopped
		e.mu.Unlock()
		e.cancel()
		close(e.stopped)
		return
	}
	e.state = StateStopping
	flows := e.flowList()
	e.mu.Unlock()

	e.cancel() // tunnels being set up end with CANCELED
	for _, f := range flows {
		f.abort()
	}
	if s := e.stack.Load(); s != nil {
		s.Close()
		s.Wait()
	}
	e.wg.Wait()
	e.mu.Lock()
	e.state = StateStopped
	e.mu.Unlock()
	e.emit(Event{Type: EvEngineStopped})
	e.closeEvents()
	close(e.stopped)
}

// ResetFlows aborts every flow (the apps see a reset and reconnect) and keeps
// the engine running. The VPN service calls it after the underlying network
// changed, because connections to the proxy over the old network are dead.
// It returns the number of flows aborted.
func (e *Engine) ResetFlows() int {
	e.mu.Lock()
	if e.state != StateRunning {
		e.mu.Unlock()
		return 0
	}
	flows := e.flowList()
	e.mu.Unlock()
	for _, f := range flows {
		f.abort()
	}
	e.stats.resets.Add(1)
	e.emit(Event{Type: EvFlowsReset, Count: len(flows)})
	return len(flows)
}

// State returns the lifecycle state.
func (e *Engine) State() State {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.state
}

// LastError returns the last engine-level error (ENGINE_STOPPED, or why Start
// failed), or nil.
func (e *Engine) LastError() *ErrorInfo {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.lastErr == nil {
		return nil
	}
	c := *e.lastErr
	return &c
}

// enter registers a flow goroutine; false once Stop has begun. Adding to the
// WaitGroup only under mu while running guarantees Stop's Wait sees every
// goroutine that will ever touch the engine.
func (e *Engine) enter() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.state != StateRunning && e.state != StateFailed {
		return false
	}
	e.wg.Add(1)
	return true
}

// --- events ---------------------------------------------------------------

func (e *Engine) startEvents() {
	if e.cfg.Events == nil {
		return
	}
	e.events = make(chan Event, e.cfg.EventQueue)
	e.evDone = make(chan struct{})
	go func() {
		defer close(e.evDone)
		for ev := range e.events {
			e.deliver(ev)
		}
	}()
}

// Event delivery states. A call to the receiver begins only by moving from
// evIdle to evDelivering, so once Stop has set evAbandoned no call can begin.
const (
	evIdle int32 = iota
	evDelivering
	evAbandoned
)

// deliver calls the receiver, isolating the engine from its panics. Once Stop
// has given up waiting for a slow receiver, the rest of the queue is dropped.
func (e *Engine) deliver(ev Event) {
	if !e.evState.CompareAndSwap(evIdle, evDelivering) {
		e.evDropped.Add(1)
		return
	}
	defer e.evState.CompareAndSwap(evDelivering, evIdle) // stays evAbandoned if Stop gave up meanwhile
	defer func() { _ = recover() }()
	e.cfg.Events(ev)
}

func (e *Engine) emit(ev Event) {
	if e.cfg.Events == nil {
		return
	}
	ev.Time = time.Now()
	e.evMu.RLock()
	defer e.evMu.RUnlock()
	if e.evClosed || e.events == nil {
		return
	}
	select {
	case e.events <- ev:
	default:
		e.evDropped.Add(1)
	}
}

// closeEvents stops the event goroutine after it delivered what is queued,
// waiting at most eventDrainTimeout for a slow receiver. After that the queued
// events are dropped and the call in progress gets eventAbandonGrace to
// return. No call begins after closeEvents returns; only a receiver stuck in a
// call for longer can still be inside it.
func (e *Engine) closeEvents() {
	e.evMu.Lock()
	if e.evClosed || e.events == nil {
		e.evClosed = true
		e.evMu.Unlock()
		return
	}
	e.evClosed = true
	close(e.events)
	e.evMu.Unlock()
	t := time.NewTimer(eventDrainTimeout)
	defer t.Stop()
	select {
	case <-e.evDone:
	case <-t.C:
		e.evState.Store(evAbandoned)
		grace := time.NewTimer(eventAbandonGrace)
		defer grace.Stop()
		select {
		case <-e.evDone:
		case <-grace.C:
		}
	}
}

func toNetip(a tcpip.Address) netip.Addr {
	ip, _ := netip.AddrFromSlice(a.AsSlice())
	return ip.Unmap()
}
