package tunnel

import (
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// Flow kinds.
const (
	FlowTCP = "tcp"
	FlowDNS = "dns"
)

// Flow states.
const (
	FlowConnecting = "connecting" // the tunnel through the proxy is being set up
	FlowOpen       = "open"       // bytes are relayed
)

// flow is one tracked connection. abort ends it at once: a connection being
// set up is canceled, an open one is reset towards the app and closed towards
// the proxy.
type flow struct {
	id      uint64
	kind    string
	dstKind string // "hostname" or "ip"
	dst     string // only with LogDestinations
	port    uint16
	opened  time.Time

	open      atomic.Bool
	aborted   atomic.Bool
	up, down  atomic.Int64 // bytes app→proxy, proxy→app
	mu        sync.Mutex
	abortFunc func()
}

// setAbort replaces the abort function. It reports false if the flow was
// aborted before, in which case the caller must end the flow itself.
func (f *flow) setAbort(fn func()) bool {
	f.mu.Lock()
	f.abortFunc = fn
	f.mu.Unlock()
	return !f.aborted.Load()
}

func (f *flow) abort() {
	f.aborted.Store(true)
	f.mu.Lock()
	fn := f.abortFunc
	f.mu.Unlock()
	if fn != nil {
		fn()
	}
}

// FlowInfo is a log-safe view of one flow for the UI's diagnostics.
type FlowInfo struct {
	ID        uint64 `json:"id"`
	Kind      string `json:"kind"`
	State     string `json:"state"`
	DstKind   string `json:"dst_kind,omitempty"`
	Dst       string `json:"dst,omitempty"` // only with LogDestinations
	Port      uint16 `json:"port,omitempty"`
	AgeMs     int64  `json:"age_ms"`
	BytesUp   int64  `json:"bytes_up"`
	BytesDown int64  `json:"bytes_down"`
}

// register adds f under a new id; false once Stop has begun.
func (e *Engine) register(f *flow) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.state != StateRunning && e.state != StateFailed {
		return false
	}
	e.nextID++
	f.id = e.nextID
	f.opened = time.Now()
	e.flows[f.id] = f
	return true
}

func (e *Engine) unregister(f *flow) {
	e.mu.Lock()
	delete(e.flows, f.id)
	e.mu.Unlock()
}

// flowList copies the tracked flows; callers hold e.mu.
func (e *Engine) flowList() []*flow {
	out := make([]*flow, 0, len(e.flows))
	for _, f := range e.flows {
		out = append(out, f)
	}
	return out
}

// Flows returns the tracked flows, oldest first.
func (e *Engine) Flows() []FlowInfo {
	e.mu.Lock()
	flows := e.flowList()
	e.mu.Unlock()
	now := time.Now()
	out := make([]FlowInfo, 0, len(flows))
	for _, f := range flows {
		st := FlowConnecting
		if f.open.Load() {
			st = FlowOpen
		}
		out = append(out, FlowInfo{
			ID: f.id, Kind: f.kind, State: st, DstKind: f.dstKind, Dst: f.dst, Port: f.port,
			AgeMs: now.Sub(f.opened).Milliseconds(), BytesUp: f.up.Load(), BytesDown: f.down.Load(),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}
