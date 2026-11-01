package tunnel

import (
	"github.com/bibekthapamgr/app/engine/fakedns"
	"hash/fnv"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/bibekthapamgr/app/engine/adapters"
)

// EventType enumerates engine events. Events never carry payload bytes or
// credentials; destinations are included only with Config.LogDestinations.
type EventType string

const (
	EvEngineStarted EventType = "engine_started"
	EvEngineStopped EventType = "engine_stopped"
	EvEngineFailed  EventType = "engine_failed" // the packet loop ended without Stop
	EvFlowsReset    EventType = "flows_reset"
	EvConnOpen      EventType = "conn_open"
	EvConnClose     EventType = "conn_close"
	EvConnFail      EventType = "conn_fail"
	EvDNS           EventType = "dns"
	EvUDPRejected   EventType = "udp_rejected"
)

// Event is one log-safe engine event.
type Event struct {
	Time       time.Time `json:"time"`
	Type       EventType `json:"type"`
	ConnID     uint64    `json:"conn_id,omitempty"`
	Proto      string    `json:"proto,omitempty"`
	DstKind    string    `json:"dst_kind,omitempty"` // "hostname" | "ip"
	Dst        string    `json:"dst,omitempty"`      // only with LogDestinations
	Port       uint16    `json:"port,omitempty"`
	Code       string    `json:"code,omitempty"`
	Detail     string    `json:"detail,omitempty"`
	SetupMs    int64     `json:"setup_ms,omitempty"`
	BytesUp    int64     `json:"bytes_up,omitempty"`
	BytesDown  int64     `json:"bytes_down,omitempty"`
	DurationMs int64     `json:"duration_ms,omitempty"`
	Count      int       `json:"count,omitempty"`
}

type stats struct {
	tcpActive, tcpOpened, tcpFailed atomic.Int64
	bytesUp, bytesDown              atomic.Int64
	udpRejected, idleTimeouts       atomic.Int64
	panics, canceled, resets        atomic.Int64
	dnsDropped, dnsTCP              atomic.Int64
	proxyTimeouts, localRefused     atomic.Int64

	mu               sync.Mutex
	failures         map[string]int64
	hostnameFailures map[string]int64
	dns              map[string]int64
	names            map[uint64]struct{} // hashes of the names this engine answered with a placeholder (bounded)
	udpPorts         map[uint16]int64
	lastProxy        *ErrorInfo
	lastFailure      *ErrorInfo
	consecutive      int64
}

func (s *stats) init() {
	s.failures = map[string]int64{}
	s.hostnameFailures = map[string]int64{}
	s.dns = map[string]int64{}
	s.names = map[uint64]struct{}{}
	s.udpPorts = map[uint16]int64{}
}

// failure counts a failed flow; hostname says its destination was a name (the
// proxy was asked to resolve it).
func (s *stats) failure(code adapters.Code, detail string, hostname bool) {
	s.mu.Lock()
	s.failures[string(code)]++
	if hostname {
		s.hostnameFailures[string(code)]++
	}
	s.lastFailure = &ErrorInfo{Code: string(code), Detail: detail, Time: time.Now()}
	if code.IsProxyLevel() {
		s.lastProxy = s.lastFailure
	}
	s.mu.Unlock()
}

// maxNames bounds the names counted per engine (as many as the placeholder pool maps at once).
const maxNames = 16384

// dnsAnswered counts a DNS message by outcome and, for a name answered with a placeholder, the name
// once per engine (only a hash is kept; the pool is shared by every engine, so its size would count
// earlier connections' names too).
func (s *stats) dnsAnswered(o fakedns.Outcome, name string) {
	s.mu.Lock()
	s.dns[string(o)]++
	if o == fakedns.OutcomeMapped && name != "" && len(s.names) < maxNames {
		h := fnv.New64a()
		_, _ = h.Write([]byte(strings.ToLower(strings.TrimSuffix(name, "."))))
		s.names[h.Sum64()] = struct{}{}
	}
	s.mu.Unlock()
}

func (s *stats) udpRejectedByPort(p uint16) {
	s.mu.Lock()
	if _, ok := s.udpPorts[p]; ok || len(s.udpPorts) < 256 { // bounded
		s.udpPorts[p]++
	}
	s.mu.Unlock()
}

// proxyResult tracks proxy health for the escalation rule (docs/m1/04 §4): a
// success resets the count, a proxy-level failure increments it, target-level
// failures and CANCELED leave it alone.
func (s *stats) proxyResult(code adapters.Code) {
	s.mu.Lock()
	defer s.mu.Unlock()
	switch {
	case code == "":
		s.consecutive = 0
	case code.IsProxyLevel():
		s.consecutive++
	}
}

// Snapshot is a point-in-time copy of the engine's state and counters.
type Snapshot struct {
	Time                  time.Time        `json:"time"`
	State                 string           `json:"state"`
	StartedAt             time.Time        `json:"started_at,omitzero"`
	UptimeMs              int64            `json:"uptime_ms"`
	LastError             *ErrorInfo       `json:"last_error,omitempty"`       // engine-level (ENGINE_STOPPED, start failure)
	LastFailure           *ErrorInfo       `json:"last_failure,omitempty"`     // last failed flow
	LastProxyError        *ErrorInfo       `json:"last_proxy_error,omitempty"` // last proxy-level failure
	ConsecutiveProxyFails int64            `json:"consecutive_proxy_failures"` // drives the escalation rule
	Flows                 int              `json:"flows"`                      // tracked flows of every kind
	TCPConnecting         int              `json:"tcp_connecting"`             // tunnels being set up
	TCPActive             int64            `json:"tcp_active"`                 // flows relaying bytes
	TCPOpened             int64            `json:"tcp_opened"`                 // since start
	TCPFailed             int64            `json:"tcp_failed"`                 // refused with a RST
	Canceled              int64            `json:"canceled"`                   // set-ups ended by Stop/ResetFlows
	Resets                int64            `json:"resets"`                     // ResetFlows calls
	BytesUp               int64            `json:"bytes_up"`                   // app → proxy
	BytesDown             int64            `json:"bytes_down"`                 // proxy → app
	UDPRejected           int64            `json:"udp_rejected"`
	IdleTimeouts          int64            `json:"idle_timeouts"`
	RecoveredPanics       int64            `json:"recovered_panics"`
	InFlightDrops         uint64           `json:"in_flight_drops"`   // SYNs dropped at MaxInFlight (the app retries)
	DNSDropped            int64            `json:"dns_dropped"`       // DNS sockets refused at MaxDNSFlows, DNS-over-TCP connections at MaxDNSOverTCP
	DNSOverTCP            int64            `json:"dns_tcp_queries"`   // DNS queries answered over TCP (port 53)
	DNSNames              int              `json:"dns_names"`         // different names this engine answered with a placeholder
	ProxyTimeouts         int64            `json:"proxy_timeouts"`    // connecting to the proxy or its handshake timed out
	LocalRefused          int64            `json:"local_refused"`     // TCP to ProxyFlow's own addresses (not DNS), refused
	EventsDropped         int64            `json:"events_dropped"`    // events lost to a full queue
	Failures              map[string]int64 `json:"failures"`          // by error code
	HostnameFailures      map[string]int64 `json:"hostname_failures"` // failures of flows to a name, by error code
	DNS                   map[string]int64 `json:"dns"`               // by outcome, over UDP and TCP
	UDPRejectedPorts      map[string]int64 `json:"udp_rejected_ports"`
	FakeDNSMappings       int              `json:"fake_dns_mappings"`
	PacketsIn             uint64           `json:"packets_in"`
	PacketsOut            uint64           `json:"packets_out"`
	MalformedIn           uint64           `json:"malformed_in"`
	ICMPEchoRepliesSent   uint64           `json:"icmp_echo_replies_sent"`     // echo replies that reached the TUN (must stay 0)
	ICMPEchoRequestsDrop  uint64           `json:"icmp_echo_requests_dropped"` // echo requests dropped before the stack
	ICMPEchoRepliesBlock  uint64           `json:"icmp_echo_replies_blocked"`  // replies the stack produced anyway, discarded
	ICMPFragmentsDrop     uint64           `json:"icmp_fragments_dropped"`     // other fragments of ICMP packets, either direction
	Goroutines            int              `json:"goroutines"`                 // whole process
	RSSKiB                int64            `json:"rss_kib"`
	HeapInuseKiB          uint64           `json:"heap_inuse_kib"`
}

// Status is the small, cheap part of the engine state, for frequent polling.
type Status struct {
	State                 string     `json:"state"`
	StartedAt             time.Time  `json:"started_at,omitzero"`
	LastError             *ErrorInfo `json:"last_error,omitempty"`
	LastProxyError        *ErrorInfo `json:"last_proxy_error,omitempty"`
	ConsecutiveProxyFails int64      `json:"consecutive_proxy_failures"`
	Flows                 int        `json:"flows"`
	TCPActive             int64      `json:"tcp_active"`
}

// Status returns the lifecycle state, the last errors and the flow count.
func (e *Engine) Status() Status {
	st := Status{TCPActive: e.stats.tcpActive.Load()}
	e.mu.Lock()
	st.State = e.state.String()
	st.StartedAt = e.startedAt
	if e.lastErr != nil {
		c := *e.lastErr
		st.LastError = &c
	}
	st.Flows = len(e.flows)
	e.mu.Unlock()
	e.stats.mu.Lock()
	if e.stats.lastProxy != nil {
		c := *e.stats.lastProxy
		st.LastProxyError = &c
	}
	st.ConsecutiveProxyFails = e.stats.consecutive
	e.stats.mu.Unlock()
	return st
}

// Stats returns a snapshot including process resource usage.
func (e *Engine) Stats() Snapshot {
	now := time.Now()
	s := &e.stats
	s.mu.Lock()
	names := len(s.names)
	s.mu.Unlock()
	snap := Snapshot{
		Time: now, TCPActive: s.tcpActive.Load(), TCPOpened: s.tcpOpened.Load(), TCPFailed: s.tcpFailed.Load(),
		Canceled: s.canceled.Load(), Resets: s.resets.Load(), BytesUp: s.bytesUp.Load(), BytesDown: s.bytesDown.Load(),
		UDPRejected: s.udpRejected.Load(), IdleTimeouts: s.idleTimeouts.Load(), RecoveredPanics: s.panics.Load(),
		DNSDropped: s.dnsDropped.Load(), DNSOverTCP: s.dnsTCP.Load(), DNSNames: names, ProxyTimeouts: s.proxyTimeouts.Load(),
		LocalRefused: s.localRefused.Load(), EventsDropped: e.evDropped.Load(),
		Failures: map[string]int64{}, HostnameFailures: map[string]int64{}, DNS: map[string]int64{}, UDPRejectedPorts: map[string]int64{},
		FakeDNSMappings: e.cfg.FakeDNS.Pool.Len(), Goroutines: runtime.NumGoroutine(), RSSKiB: rssKiB(),
	}

	e.mu.Lock()
	snap.State = e.state.String()
	if !e.startedAt.IsZero() {
		snap.StartedAt = e.startedAt
		if e.state == StateRunning || e.state == StateFailed {
			snap.UptimeMs = now.Sub(e.startedAt).Milliseconds()
		}
	}
	if e.lastErr != nil {
		c := *e.lastErr
		snap.LastError = &c
	}
	snap.Flows = len(e.flows)
	for _, f := range e.flows {
		if f.kind == FlowTCP && !f.open.Load() {
			snap.TCPConnecting++
		}
	}
	e.mu.Unlock()

	s.mu.Lock()
	for k, v := range s.failures {
		snap.Failures[k] = v
	}
	for k, v := range s.hostnameFailures {
		snap.HostnameFailures[k] = v
	}
	for k, v := range s.dns {
		snap.DNS[k] = v
	}
	for k, v := range s.udpPorts {
		snap.UDPRejectedPorts[strconv.Itoa(int(k))] = v
	}
	if s.lastProxy != nil {
		c := *s.lastProxy
		snap.LastProxyError = &c
	}
	if s.lastFailure != nil {
		c := *s.lastFailure
		snap.LastFailure = &c
	}
	snap.ConsecutiveProxyFails = s.consecutive
	s.mu.Unlock()

	if st := e.stack.Load(); st != nil {
		stackStats := st.Stats()
		snap.PacketsIn = stackStats.IP.PacketsReceived.Value()
		snap.PacketsOut = stackStats.IP.PacketsSent.Value()
		snap.MalformedIn = stackStats.IP.MalformedPacketsReceived.Value()
		snap.InFlightDrops = stackStats.TCP.ForwardMaxInFlightDrop.Value()
		generated := stackStats.ICMP.V4.PacketsSent.EchoReply.Value() + stackStats.ICMP.V6.PacketsSent.EchoReply.Value()
		if echo := e.echo.Load(); echo != nil {
			blocked := echo.repliesBlocked.Load()
			snap.ICMPEchoRepliesBlock = blocked
			snap.ICMPEchoRequestsDrop = echo.requestsDropped.Load()
			snap.ICMPFragmentsDrop = echo.fragmentsDropped.Load()
			if generated > blocked {
				snap.ICMPEchoRepliesSent = generated - blocked
			}
		}
	}
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	snap.HeapInuseKiB = ms.HeapInuse / 1024
	return snap
}

func rssKiB() int64 {
	b, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return -1
	}
	for _, line := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(line, "VmRSS:") {
			f := strings.Fields(line)
			if len(f) >= 2 {
				v, _ := strconv.ParseInt(f[1], 10, 64)
				return v
			}
		}
	}
	return -1
}
