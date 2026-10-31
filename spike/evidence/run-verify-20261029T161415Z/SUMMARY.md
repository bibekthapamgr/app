# Feasibility spike evidence — run-verify-20261029T161415Z

- Kernel: `6.18.44-fc-v185`  ·  engine binary: `pfspike`
- End-to-end: **20/20 passed**, 1 skipped, 144/144 individual checks passed
- Go unit/hermetic tests: **50/50 passed** (proxyflow/spike/engine/adapters, proxyflow/spike/engine/engine, proxyflow/spike/engine/fakedns)

| Test | Verdict | Checks | What it proves |
|---|---|---|---|
| `test_E01_socks5_tcp_through_controlled_proxy` | PASS | 11/11 | Core M1 spike: one supported TCP request through a controlled SOCKS5 proxy. |
| `test_E02_socks5_auth_real_dante` | PASS | 7/7 | SOCKS5 username/password (RFC 1929) against a real Dante server; wrong password fails closed. |
| `test_E03_http_connect_real_squid` | PASS | 11/11 | HTTP CONNECT with Basic auth against a real Squid; HTTPS end to end; per-destination refusal. |
| `test_E04_tls_to_proxy[expired-cert]` | PASS | 7/7 | TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed. |
| `test_E04_tls_to_proxy[hostname-mismatch]` | PASS | 7/7 | TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed. |
| `test_E04_tls_to_proxy[tls-to-plaintext-port]` | PASS | 7/7 | TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed. |
| `test_E04_tls_to_proxy[untrusted-issuer]` | PASS | 7/7 | TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed. |
| `test_E04_tls_to_proxy[valid-http-tls-auth]` | PASS | 9/9 | TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed. |
| `test_E04_tls_to_proxy[valid-socks5-tls]` | PASS | 7/7 | TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed. |
| `test_E05_plaintext_exposure_evidence` | PASS | 6/6 | Evidence for the Play-policy finding: plain SOCKS5/HTTP expose credentials, hostnames and payload. |
| `test_E07_dns_never_leaves_device` | PASS | 10/10 | DNS: fake-IP answers, interception of any resolver, no direct DNS on the uplink. |
| `test_E08_udp_blocked_not_leaked` | PASS | 9/9 | Non-DNS UDP (incl. QUIC-style UDP/443) is rejected inside the tunnel and never leaves the device. |
| `test_E09_icmp_not_faked` | PASS | 6/6 | ICMP echo is not proxied and never answered with a fake reply. |
| `test_E10_fail_closed_when_proxy_down` | PASS | 10/10 | Proxy outage: connections are refused fast, nothing bypasses, recovery is automatic. |
| `test_E11_kill_switch_semantics` | PASS | 8/8 | What happens on engine crash vs. VPN interface teardown, with and without OS lockdown. |
| `test_E12_concurrency_and_cleanup` | PASS | 6/6 | 200 concurrent flows, then verify every flow and goroutine is released on stop. |
| `test_E13_throughput_comparison` | PASS | 1/1 | Indicative throughput / CPU / memory: spike engine vs hev-socks5-tunnel (same VM, same Dante proxy). |
| `test_E14_log_hygiene` | PASS | 3/3 | Credentials, payload and (by default) destinations never appear in engine logs, stats or events. |
| `test_E15_hev_baseline_behaviour` | PASS | 6/6 | Same harness against hev-socks5-tunnel (comparison candidate): SOCKS5 only. |
| `test_E16_proxy_hostname_resolved_on_underlying_network` | PASS | 6/6 | The proxy's own hostname is resolved via the underlying network with a protected socket; nothing else is. |
| `test_E06_ipv6` | SKIPPED | – | kernel booted with ipv6.disable=1: kernel-level IPv6 cannot be exercised on this host; engine IPv6 is covered by hermetic Go tests (engine/engine_test.go TestIPv6*) |

## Checks

### test_E01_socks5_tcp_through_controlled_proxy — PASS
_Core M1 spike: one supported TCP request through a controlled SOCKS5 proxy._  
Requirement: M1 task 9 / acceptance 'actual proxy-routed TCP traffic'.

- ✅ HTTP 200 received by the app
- ✅ end-to-end payload intact (nonce echoed)
- ✅ destination saw the PROXY's address, not the phone's
- ✅ proxy received CONNECT web.test:80 as a HOSTNAME (remote DNS)
- ✅ app DNS query entered the tunnel (UDP/53 on tun0)
- ✅ app TCP SYN to a fake IP (198.18.0.0/15) entered the tunnel
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device
- ✅ every destination request came from the proxy
- ✅ engine counted the flow and the fake-DNS mapping

```json
{
  "request_seconds": 0.016,
  "engine_rss_kib": 11620,
  "engine_stop_ms": 202
}
```

### test_E02_socks5_auth_real_dante — PASS
_SOCKS5 username/password (RFC 1929) against a real Dante server; wrong password fails closed._  
Requirement: HTTP CONNECT and SOCKS5 support with optional credentials.

- ✅ correct credentials: HTTP 200 via Dante
- ✅ wrong password: app connection fails
- ✅ wrong password categorised AUTH_FAILED (proxy-level)
- ✅ no destination request during the failing run
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1081)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

```json
{
  "fail_seconds": 0.028
}
```

### test_E03_http_connect_real_squid — PASS
_HTTP CONNECT with Basic auth against a real Squid; HTTPS end to end; per-destination refusal._  
Requirement: HTTP CONNECT support; target-level errors must not break the tunnel.

- ✅ HTTPS through CONNECT: 200, certificate verified by the app
- ✅ HTTP through CONNECT: 200 from the proxy address
- ✅ port 8081 refused by Squid policy -> app connection fails
- ✅ refusal categorised TARGET_NOT_ALLOWED
- ✅ target-level refusal did not set a global proxy error
- ✅ tunnel still works after a refused destination
- ✅ wrong password categorised AUTH_FAILED
- ✅ Squid access log shows CONNECT web.test:443 by hostname
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 3129)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

### test_E04_tls_to_proxy[expired-cert] — PASS
_TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed._  
Requirement: optional TLS with strict certificate validation; Play VpnService encryption policy.

- ✅ app connection fails
- ✅ failure categorised TLS_EXPIRED
- ✅ categorised as a proxy-level (global) error
- ✅ no request reached the destination
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1444)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

### test_E04_tls_to_proxy[hostname-mismatch] — PASS
_TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed._  
Requirement: optional TLS with strict certificate validation; Play VpnService encryption policy.

- ✅ app connection fails
- ✅ failure categorised TLS_HOSTNAME
- ✅ categorised as a proxy-level (global) error
- ✅ no request reached the destination
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1443)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

### test_E04_tls_to_proxy[tls-to-plaintext-port] — PASS
_TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed._  
Requirement: optional TLS with strict certificate validation; Play VpnService encryption policy.

- ✅ app connection fails
- ✅ failure categorised TLS_HANDSHAKE
- ✅ categorised as a proxy-level (global) error
- ✅ no request reached the destination
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

### test_E04_tls_to_proxy[untrusted-issuer] — PASS
_TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed._  
Requirement: optional TLS with strict certificate validation; Play VpnService encryption policy.

- ✅ app connection fails
- ✅ failure categorised TLS_UNTRUSTED
- ✅ categorised as a proxy-level (global) error
- ✅ no request reached the destination
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1445)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

### test_E04_tls_to_proxy[valid-http-tls-auth] — PASS
_TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed._  
Requirement: optional TLS with strict certificate validation; Play VpnService encryption policy.

- ✅ HTTP 200 through TLS-wrapped proxy
- ✅ uplink capture: destination hostname NOT visible
- ✅ uplink capture: app payload (nonce) NOT visible
- ✅ uplink capture: proxy password NOT visible
- ✅ uplink capture: no cleartext Proxy-Authorization header
- ✅ SNI of the proxy is the only name visible
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 3443)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device
> The TLS ClientHello SNI (proxy.test) is visible on the network by design.

### test_E04_tls_to_proxy[valid-socks5-tls] — PASS
_TLS to the proxy endpoint with strict certificate and hostname validation; failures fail closed._  
Requirement: optional TLS with strict certificate validation; Play VpnService encryption policy.

- ✅ HTTP 200 through TLS-wrapped proxy
- ✅ uplink capture: destination hostname NOT visible
- ✅ uplink capture: app payload (nonce) NOT visible
- ✅ SNI of the proxy is the only name visible
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1443)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device
> The TLS ClientHello SNI (proxy.test) is visible on the network by design.

### test_E05_plaintext_exposure_evidence — PASS
_Evidence for the Play-policy finding: plain SOCKS5/HTTP expose credentials, hostnames and payload._  
Requirement: Google Play VpnService policy (encrypt device-to-tunnel-endpoint traffic).

- ✅ both plaintext modes work (HTTP 200)
- ✅ SOCKS5 plain: password visible on the network (RFC 1929 is cleartext)
- ✅ SOCKS5 plain: destination hostname visible
- ✅ SOCKS5 plain: app payload of a non-TLS app visible
- ✅ HTTP plain: Proxy-Authorization (base64 credentials) visible
- ✅ HTTP plain: CONNECT line with hostname visible
> Contrast with test_E04 valid-* cases, where none of these appear on the wire.

### test_E07_dns_never_leaves_device — PASS
_DNS: fake-IP answers, interception of any resolver, no direct DNS on the uplink._  
Requirement: never fall back to direct DNS; M1 task 3 DNS behaviour.

- ✅ A query answered from the fake pool
- ✅ TXT refused (only A/AAAA/HTTPS handled in V1)
- ✅ query sent to 8.8.8.8 was intercepted and answered locally
- ✅ query aimed at the underlying network's resolver was intercepted too
- ✅ underlying-network resolver received NO app queries
- ✅ DNS-over-TCP to 8.8.8.8 is treated as a normal TCP flow via the proxy (fails here: lab has no 8.8.8.8)
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device
- ✅ zero UDP packets on the uplink

```json
{
  "dns_outcomes": {
    "mapped": 3,
    "refused": 1
  }
}
```

### test_E08_udp_blocked_not_leaked — PASS
_Non-DNS UDP (incl. QUIC-style UDP/443) is rejected inside the tunnel and never leaves the device._  
Requirement: M1 task 3 UDP behaviour; unsupported UDP verified before release.

- ✅ UDP echo got no reply from the destination
- ✅ UDP/443 (QUIC) got no reply
- ✅ destination never saw UDP
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device
- ✅ zero UDP packets on the uplink
- ✅ engine counted the rejected datagrams (>= 1 per probe)
- ✅ apps are told quickly (ICMP port unreachable -> ECONNREFUSED), enabling QUIC->TCP fallback

```json
{
  "icmp_unreachable_sent_to_apps": 2,
  "udp_rejected": 2,
  "app_saw": "ECONNREFUSED (ICMP port unreachable from the tunnel)"
}
```
> IPv6 UDP case skipped: kernel booted with ipv6.disable=1: kernel-level IPv6 cannot be exercised on this host; engine IPv6 is covered by hermetic Go tests (engine/engine_test.go TestIPv6*)

### test_E09_icmp_not_faked — PASS
_ICMP echo is not proxied and never answered with a fake reply._  
Requirement: M1 task 3 ICMP behaviour; diagnostics must not mislead users.

- ✅ IPv4 ping: 100% loss
- ✅ engine sent zero ICMP echo replies
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device
- ✅ zero ICMP on the uplink
> IPv6 ping skipped: kernel booted with ipv6.disable=1: kernel-level IPv6 cannot be exercised on this host; engine IPv6 is covered by hermetic Go tests (engine/engine_test.go TestIPv6*)

### test_E10_fail_closed_when_proxy_down — PASS
_Proxy outage: connections are refused fast, nothing bypasses, recovery is automatic._  
Requirement: do not silently bypass the configured proxy; kill-switch behaviour.

- ✅ baseline request works
- ✅ proxy down: hostname request fails
- ✅ proxy down: IP-literal request fails
- ✅ proxy down: failure is immediate (RST), not a hang
- ✅ no destination request during the outage
- ✅ outage categorised PROXY_UNREACHABLE
- ✅ traffic recovers when the proxy returns, without restarting the engine
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

```json
{
  "outage_fail_seconds": 0.01
}
```

### test_E11_kill_switch_semantics — PASS
_What happens on engine crash vs. VPN interface teardown, with and without OS lockdown._  
Requirement: kill-switch / fail-closed design input for M5/M8.

- ✅ A. baseline works
- ✅ A. engine crashed while the TUN stays up: traffic black-holed (fail closed)
- ✅ A. no destination request after the crash
- ✅ A uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ A uplink: no packet from the device reached a destination address directly
- ✅ A uplink: no DNS (port 53) left the device
- ✅ B. without OS lockdown, teardown LEAKS (expected; proves why lockdown is required)
- ✅ C. with lockdown emulation, teardown is fail-closed
> B is a deliberate negative control. On Android the equivalent protection is Always-on VPN + 'Block connections without VPN' (lockdown); the app must detect and guide it.

### test_E12_concurrency_and_cleanup — PASS
_200 concurrent flows, then verify every flow and goroutine is released on stop._  
Requirement: resource cleanup verified before release; resource limits.

- ✅ 200/200 concurrent requests succeeded
- ✅ engine saw ~200 simultaneous flows
- ✅ all flows released after completion (tcp_active == 0)
- ✅ graceful stop completed
- ✅ goroutines released on stop (<= 12 remain)
- ✅ stop took < 2 s

```json
{
  "idle_rss_kib": 10716,
  "peak_rss_kib": 21116,
  "peak_active": 200,
  "goroutines_before_stop": 7,
  "goroutines_after_stop": 2,
  "stop_ms": 202,
  "wall_seconds": 3.18
}
```

### test_E13_throughput_comparison — PASS
_Indicative throughput / CPU / memory: spike engine vs hev-socks5-tunnel (same VM, same Dante proxy)._  
Requirement: M1 task 7 engine evaluation (resource limits).

- ✅ spike engine moved data in both directions

```json
{
  "no_tunnel_proxy_host_to_dest": {
    "upload_mbps": 16396.3
  },
  "spike_gvisor_mtu1500": {
    "tun_mtu": 1500,
    "upload_mbps": 1721.6,
    "download_mbps": 836.2,
    "engine_cpu_seconds": 8.98,
    "cpu_seconds_per_gb": 7.02,
    "rss_kib_after": 23292
  },
  "hev_no_offload_mtu1500": {
    "tun_mtu": 1500,
    "upload_mbps": 3224.7,
    "download_mbps": 2744.8,
    "engine_cpu_seconds": 6.57,
    "cpu_seconds_per_gb": 2.2,
    "rss_kib_after": 2472
  },
  "spike_gvisor_mtu9000": {
    "tun_mtu": 9000,
    "upload_mbps": 4534.6,
    "download_mbps": 3364.4,
    "engine_cpu_seconds": 7.57,
    "cpu_seconds_per_gb": 1.92,
    "rss_kib_after": 24684
  },
  "hev_no_offload_mtu9000": {
    "tun_mtu": 9000,
    "upload_mbps": 6049.5,
    "download_mbps": 5751.1,
    "engine_cpu_seconds": 5.68,
    "cpu_seconds_per_gb": 0.96,
    "rss_kib_after": 2492
  },
  "hev_linux_offloads_mtu1500 (not available on Android)": {
    "tun_mtu": 1500,
    "upload_mbps": 6891.6,
    "download_mbps": 6761.4,
    "engine_cpu_seconds": 4.42,
    "cpu_seconds_per_gb": 0.65,
    "rss_kib_after": 2720
  }
}
```
> Single 2-vCPU VM, veth links, Dante as proxy: numbers are relative, not phone performance.

### test_E14_log_hygiene — PASS
_Credentials, payload and (by default) destinations never appear in engine logs, stats or events._  
Requirement: never log credentials or application payloads.

- ✅ no username, password or payload in any engine output
- ✅ destinations omitted by default
- ✅ destinations included only when the privacy switch is on

### test_E15_hev_baseline_behaviour — PASS
_Same harness against hev-socks5-tunnel (comparison candidate): SOCKS5 only._  
Requirement: M1 task 7 engine evaluation.

- ✅ hev: HTTP 200 through SOCKS5
- ✅ hev: hostname sent to proxy (mapdns)
- ✅ hev: ping not answered (icmp: off)
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1080)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device

```json
{
  "hev_idle_rss_kib": 2252,
  "hev_udp_probe": {
    "sent": 3,
    "error": "timeout"
  },
  "hev_udp_associate_attempts": [
    "cmd_3_unsupported",
    "cmd_3_unsupported",
    "cmd_3_unsupported"
  ]
}
```
> hev speaks SOCKS5 only: HTTP CONNECT and TLS-to-proxy would need a local SOCKS5 front-end (loopback listener).

### test_E16_proxy_hostname_resolved_on_underlying_network — PASS
_The proxy's own hostname is resolved via the underlying network with a protected socket; nothing else is._  
Requirement: routing loop prevention; DNS handling.

- ✅ TLS proxy addressed by hostname works
- ✅ underlay resolver saw only the proxy hostnames
- ✅ unknown proxy hostname categorised PROXY_DNS_FAILED
- ✅ uplink: nothing left the device except to the proxy endpoint(s) [('10.20.0.2', 1443)]
- ✅ uplink: no packet from the device reached a destination address directly
- ✅ uplink: no DNS (port 53) left the device
