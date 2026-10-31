package engine

import (
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"proxyflow/spike/engine/adapters"
)

// EventType enumerates engine events. Events never carry payload bytes or
// credentials; destinations are included only when Config.LogDestinations.
type EventType string

const (
	EvEngineStarted EventType = "engine_started"
	EvEngineStopped EventType = "engine_stopped"
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
}

type stats struct {
	tcpActive, tcpOpened, tcpFailed atomic.Int64
	bytesUp, bytesDown              atomic.Int64
	udpRejected, idleTimeouts       atomic.Int64
	panics                          atomic.Int64

	mu             sync.Mutex
	failures       map[string]int64
	dns            map[string]int64
	udpPorts       map[uint16]int64
	lastProxyError string
	lastProxyAt    time.Time
	consecutive    int64
}

func (s *stats) init() {
	s.failures = map[string]int64{}
	s.dns = map[string]int64{}
	s.udpPorts = map[uint16]int64{}
}

func (s *stats) failure(code adapters.Code) {
	s.mu.Lock()
	s.failures[string(code)]++
	s.mu.Unlock()
}

func (s *stats) dnsOutcome(o string) {
	s.mu.Lock()
	s.dns[o]++
	s.mu.Unlock()
}

func (s *stats) udpRejectedByPort(p uint16) {
	s.mu.Lock()
	if len(s.udpPorts) < 256 { // bounded
		s.udpPorts[p]++
	}
	s.mu.Unlock()
}

// proxyResult tracks proxy health: proxy-level failures (auth, TLS,
// unreachable...) drive the global connection state shown in the UI.
func (s *stats) proxyResult(code adapters.Code) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if code == "" {
		s.consecutive = 0
		return
	}
	if code.IsProxyLevel() {
		s.consecutive++
		s.lastProxyError = string(code)
		s.lastProxyAt = time.Now()
	}
}

// Snapshot is a point-in-time copy of the counters.
type Snapshot struct {
	Time                  time.Time        `json:"time"`
	TCPActive             int64            `json:"tcp_active"`
	TCPOpened             int64            `json:"tcp_opened"`
	TCPFailed             int64            `json:"tcp_failed"`
	BytesUp               int64            `json:"bytes_up"`
	BytesDown             int64            `json:"bytes_down"`
	UDPRejected           int64            `json:"udp_rejected"`
	IdleTimeouts          int64            `json:"idle_timeouts"`
	RecoveredPanics       int64            `json:"recovered_panics"`
	Failures              map[string]int64 `json:"failures"`
	DNS                   map[string]int64 `json:"dns"`
	UDPRejectedPorts      map[string]int64 `json:"udp_rejected_ports"`
	LastProxyError        string           `json:"last_proxy_error,omitempty"`
	ConsecutiveProxyFails int64            `json:"consecutive_proxy_failures"`
	FakeDNSMappings       int              `json:"fake_dns_mappings"`
	PacketsIn             uint64           `json:"packets_in"`
	PacketsOut            uint64           `json:"packets_out"`
	MalformedIn           uint64           `json:"malformed_in"`
	ICMPEchoRepliesSent   uint64           `json:"icmp_echo_replies_sent"`     // echo replies that reached the TUN (must stay 0)
	ICMPEchoRequestsDrop  uint64           `json:"icmp_echo_requests_dropped"` // echo requests dropped before the stack
	ICMPEchoRepliesBlock  uint64           `json:"icmp_echo_replies_blocked"`  // replies the stack produced anyway, discarded
	ICMPFragmentsDrop     uint64           `json:"icmp_fragments_dropped"`     // other fragments of ICMP packets, either direction
	Goroutines            int              `json:"goroutines"`
	RSSKiB                int64            `json:"rss_kib"`
	HeapInuseKiB          uint64           `json:"heap_inuse_kib"`
}

// Stats returns a snapshot including process resource usage.
func (e *Engine) Stats() Snapshot {
	s := &e.stats
	snap := Snapshot{
		Time: time.Now(), TCPActive: s.tcpActive.Load(), TCPOpened: s.tcpOpened.Load(),
		TCPFailed: s.tcpFailed.Load(), BytesUp: s.bytesUp.Load(), BytesDown: s.bytesDown.Load(),
		UDPRejected: s.udpRejected.Load(), IdleTimeouts: s.idleTimeouts.Load(), RecoveredPanics: s.panics.Load(),
		Failures: map[string]int64{}, DNS: map[string]int64{}, UDPRejectedPorts: map[string]int64{},
		FakeDNSMappings: e.cfg.FakeDNS.Pool.Len(), Goroutines: runtime.NumGoroutine(), RSSKiB: rssKiB(),
	}
	s.mu.Lock()
	for k, v := range s.failures {
		snap.Failures[k] = v
	}
	for k, v := range s.dns {
		snap.DNS[k] = v
	}
	for k, v := range s.udpPorts {
		snap.UDPRejectedPorts[strconv.Itoa(int(k))] = v
	}
	snap.LastProxyError = s.lastProxyError
	snap.ConsecutiveProxyFails = s.consecutive
	s.mu.Unlock()
	if e.stack != nil {
		st := e.stack.Stats()
		snap.PacketsIn = st.IP.PacketsReceived.Value()
		snap.PacketsOut = st.IP.PacketsSent.Value()
		snap.MalformedIn = st.IP.MalformedPacketsReceived.Value()
		generated := st.ICMP.V4.PacketsSent.EchoReply.Value() + st.ICMP.V6.PacketsSent.EchoReply.Value()
		var blocked uint64
		if e.echo != nil {
			blocked = e.echo.repliesBlocked.Load()
			snap.ICMPEchoRequestsDrop = e.echo.requestsDropped.Load()
			snap.ICMPFragmentsDrop = e.echo.fragmentsDropped.Load()
		}
		snap.ICMPEchoRepliesBlock = blocked
		if generated > blocked {
			snap.ICMPEchoRepliesSent = generated - blocked
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
