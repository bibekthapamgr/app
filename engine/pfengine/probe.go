package pfengine

import (
	"context"
	"errors"
	"time"

	"github.com/bibekthapamgr/app/engine/adapters"
)

const defaultProbeTimeout = 15 * time.Second

// probeResult is what Probe and SelfTest report about the proxy.
type probeResult struct {
	OK         bool   `json:"ok"`
	Code       string `json:"code,omitempty"`
	Detail     string `json:"detail,omitempty"`
	ProxyLevel bool   `json:"proxy_level,omitempty"` // the code concerns the proxy itself, not this destination
	LatencyMs  int64  `json:"latency_ms,omitempty"`
}

func failed(err error) probeResult {
	code := adapters.CodeOf(err)
	detail := adapters.DetailOf(err)
	if detail == "" && code == adapters.CodeInternal {
		detail = err.Error() // configuration errors: plain, log-safe messages
	}
	return probeResult{Code: string(code), Detail: detail, ProxyLevel: code.IsProxyLevel()}
}

// Probe opens one tunnel through the proxy to target ("host:port"), without a
// TUN, and closes it again: the profile's connection test and the pre-flight
// check before the VPN starts (docs/m1/06-architecture.md §4). It returns
// JSON: {"ok":true,"latency_ms":182} or {"ok":false,"code":"AUTH_FAILED",
// "detail":"…","proxy_level":true}. secret is zeroed before Probe returns.
func Probe(configJSON string, secret []byte, target string, timeoutMs int64, p Platform) string {
	defer wipe(secret)
	if p == nil {
		return toJSON(failed(errors.New("no platform callbacks")))
	}
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return toJSON(failed(err))
	}
	t, err := splitTarget(target)
	if err != nil {
		return toJSON(failed(err))
	}
	d, creds, err := newDialer(cfg, secret, p)
	if err != nil {
		return toJSON(failed(err))
	}
	defer creds.Wipe()
	timeout := defaultProbeTimeout
	if timeoutMs > 0 {
		timeout = ms(timeoutMs)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	start := time.Now()
	conn, err := d.Dial(ctx, t)
	if err != nil {
		return toJSON(failed(err))
	}
	latency := time.Since(start)
	conn.Close()
	return toJSON(probeResult{OK: true, LatencyMs: max(latency.Milliseconds(), 1)})
}
