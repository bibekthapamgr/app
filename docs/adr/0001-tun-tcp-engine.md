# ADR-0001 — TUN/TCP engine: gVisor netstack

**Status:** Accepted 2026-10-31 (decision D1, delegated by the owner to the tech lead) · **Date:** 2026-10-30 · **Deciders:** product owner, tech lead

## Context

`VpnService` hands the app a TUN file descriptor carrying raw IP packets from every app. To send those apps' TCP streams through an HTTP CONNECT or SOCKS5 proxy, ProxyFlow must terminate each TCP connection on the phone and re-originate it through the proxy. That needs a userspace TCP/IP stack. The brief forbids writing one from scratch and asks for a maintained, audited engine evaluated on licence, maintenance, CVEs, IPv6, resource limits and protocol compatibility.

## Decision

Use **gVisor's netstack** (`gvisor.dev/gvisor/pkg/tcpip`, Apache-2.0), pinned to a commit of gVisor's Go-importable `go` branch, wrapped in a small Go engine (forwarders, fake-IP DNS, policy, adapters) and shipped as an AAR built with `gomobile bind`.

## Alternatives considered

| Option | Why not chosen |
|---|---|
| hev-socks5-tunnel (C/lwIP, MIT) | Fastest and smallest in our lab, but speaks only SOCKS5: HTTP CONNECT and TLS-to-proxy would need a local SOCKS5 front-end listening on loopback (see ADR-0002), and it is C code with recent memory-safety fixes. **Kept as the fallback** if gVisor misses on-device performance bars. |
| tun2proxy (Rust, MIT) | Memory-safe and supports HTTP + SOCKS5, but no TLS-to-proxy, younger TCP stack (`ipstack`), Rust toolchain added to an otherwise Kotlin/Go project. |
| xjasonlyu/tun2socks (Go, MIT, gVisor) | Same stack; larger surface and global configuration model; we only need the netstack wiring. |
| sing-tun (GPL-3.0-or-later) | Licence incompatible unless ProxyFlow is GPL. |
| go-tun2socks (eycorsican); Outline SDK `lwip2transport` | go-tun2socks is unmaintained (last commit Nov 2020); Outline's TUN package depends on it. |
| Own TCP stack | Prohibited by the brief. |

Full comparison: `docs/m1/08-dependency-review.md` §1.

## Consequences

- Positive: memory-safe stack with a large production user base and an active security process; IPv4 + IPv6; full control over the dial path, so HTTP CONNECT, SOCKS5 and TLS share one code path; error codes and redaction implemented once.
- Negative: higher CPU and memory than hev (lab: 2.0–3.5× CPU per GB and 9.5–11.3× memory in the canonical run, 9.4–13.5× memory across all four runs; `08-dependency-review.md` §1); the Go runtime adds several MB per ABI to the app (an android/arm64 build of the spike is 8.6 MiB stripped); gVisor's `go` branch has no semantic versions, so we pin commits and update monthly.
- Mitigations: TUN MTU 9000 (lab: 3.2× upload, 4.8× download vs 1500); buffer tuning in M10; on-device quality bars in `01-scope.md` §7 with hev as the documented fallback.

## Evidence

Lab run `run-m1-20261030T003301Z` (canonical, from the current code; three earlier runs are kept as history): E01–E04, E07–E14, E16 pass with this engine; E13 throughput table; E12 cleanup (`Stop()` took 24 ms with 200 flows open, goroutines 608 → 2); 54/54 Go tests plus 20 race-detector repeats of the engine tests; fuzzing with no crashes. The M1 checks also found that gVisor's IPv6 layer answers pings by itself, large fragmented ones included; the engine now filters echo traffic (ADR-0004).

## Implementation (M4)

`engine/` (Go module `github.com/bibekthapamgr/app/engine`, Go 1.27.3): `tunnel` (the netstack engine), `adapters`, `fakedns`, `pfengine` (the gomobile API) and `internal/proxytest` (mock proxies for tests). gVisor stays pinned at `v0.0.0-20261029062900-94fcb330700e`; gomobile at `golang.org/x/mobile v0.0.0-20260908204917-8b95e45f8d3e`; the AAR is built by `engine/build-aar.sh` in CI. Licences and pins: `docs/m4/dependency-review.md`. Engine API and protocol behaviour: `docs/m4/protocol-compatibility.md`.
