# 08 — Dependency and engine review

**Milestone:** M1 · **Status:** Draft for approval · **Reviewed:** 29–30 Oct 2026 (sources checked out at the commits listed; web sources at the end)

## 1. TUN/TCP engine candidates

Criteria from the brief: licence, maintenance, CVE record, IPv6, resource limits, protocol compatibility. "Measured" values come from the M1 lab (`09-feasibility-report.md`, E13/E15), same VM and same proxy for both measured engines.

| Candidate | Language / stack | Licence | Maintenance (as of 29 Oct 2026) | Security record found | IPv6 | Proxy protocols it speaks | Android fit | Verdict |
|---|---|---|---|---|---|---|---|---|
| **gVisor netstack** (`gvisor.dev/gvisor/pkg/tcpip`) + our in-process adapters | Go, userspace TCP/IP from Google's gVisor sandbox | Apache-2.0 | Very active: ~2,900 commits in 12 months on master; release-20261026.0; Go-importable `go` branch pinned at `94fcb330700e` | CVE-2024-10603 (predictable TCP/UDP source ports and header values) and CVE-2024-10026 (weak hashing let a remote party derive a local IP / per-boot identifier) — both fixed in 2024 releases, long before the pinned commit. Network-packet crashes are in gVisor's own attacker model (`Remote / PeerDoS`) | Yes (v4 + v6) | None built in → ProxyFlow adapters: SOCKS5, HTTP CONNECT, TLS (tested against Dante, Squid, stunnel) | Pure Go; packaged with gomobile; the same netstack is embedded by other Go networking projects (e.g. Tailscale, sing-box's gVisor stack, xjasonlyu/tun2socks) | **Selected** (ADR-0001) |
| **hev-socks5-tunnel** | C on a modified lwIP fork (`heiher/lwip`, BSD-3-Clause) | MIT (lwIP: BSD-3-Clause) | Very active: 2.19.3 tagged 25 Oct 2026, fixes landing after it (27 Oct); ~135 commits in 12 months; Android AAR since 2.18.0 | No CVE found for hev itself. Release notes fix memory-safety bugs (2.17.0: DNS-mapping leak and out-of-bounds; 2.18.0: UDP/IPv6 validation; post-2.19.3: allocator mismatch, fd leaks). lwIP 2026 CVEs are in optional modules (SNMP, MQTT, mDNS, SMTP; one double-free whose module sources disagree on) — whether hev compiles any of them would need checking if chosen | Yes | **SOCKS5 only** (+ UDP via SOCKS5/UDP-in-TCP). HTTP CONNECT and TLS-to-proxy need a separate local SOCKS5 front-end → a loopback listener | Excellent: tiny (335 KiB stripped x86-64 binary vs 8.3 MiB for the stripped Go engine binary; 2.2–2.7 MiB RSS measured), JNI binding, widely used | Strong runner-up; rejected for V1 because HTTP CONNECT + TLS would require a local listener (ADR-0002) and C memory safety |
| **tun2proxy** (with `ipstack`) | Rust | MIT (ipstack: Apache-2.0 per its LICENSE file) | Active: v0.8.6, ~60 commits in 12 months | None found in a RustSec search; not audited by us | Yes | HTTP (Basic, Digest), SOCKS4/4a/5, SOCKS5 UDP, UdpGW, DNS over TCP; **no TLS-to-proxy** | Android JNI support; Rust toolchain + cargo-ndk | Viable alternative (memory-safe); would need TLS-to-proxy added; `ipstack` is a young TCP implementation compared with gVisor |
| **xjasonlyu/tun2socks** | Go on gVisor netstack | MIT | Active: v2.7.0, ~30 commits in 12 months | Inherits gVisor's | Yes | SOCKS4/5, HTTP, Shadowsocks, relay | CLI/engine with its own global configuration; we would use only its netstack wiring | Not needed: we use gVisor directly with a smaller surface; useful as a reference implementation |
| **sing-tun** (sing-box) | Go (gVisor or "system" stack) | **GPL-3.0-or-later** | Active | — | Yes | Via sing-box outbounds | Large framework | Rejected unless ProxyFlow itself is GPL (D8) |
| **go-tun2socks** (eycorsican) | Go + lwIP via cgo | MIT | **Unmaintained** — last commit Nov 2020 | Old lwIP copy | Partial | SOCKS5 (via handlers) | cgo build | Rejected |
| **Outline SDK** `network/lwip2transport` | Go, wraps go-tun2socks/lwIP | Apache-2.0 | SDK active, but this package depends on go-tun2socks v1.16.11 | as above | Partial | SOCKS5, Shadowsocks; transports are good references | — | Rejected for the TUN layer (depends on unmaintained go-tun2socks) |
| Write our own TCP stack | — | — | — | — | — | — | — | **Prohibited by the brief** |

Measured in the canonical lab run `run-m1-20261030T003301Z` (relative numbers only — one 2-vCPU VM, veth links, Dante as proxy):

| Engine (Linux offloads off, as on Android) | MTU | Upload Mbit/s | Download Mbit/s | CPU s per GB | RSS after run |
|---|---|---|---|---|---|
| gVisor engine (spike) | 1500 | 1,172 | 575 | 10.63 | 27.3 MiB |
| gVisor engine (spike) | 9000 | 3,803 | 2,749 | 2.31 | 23.1 MiB |
| hev-socks5-tunnel 2.19.3 | 1500 | 2,121 | 2,119 | 3.01 | 2.4 MiB |
| hev-socks5-tunnel 2.19.3 | 9000 | 5,319 | 4,379 | 1.16 | 2.4 MiB |

In this run hev moves 1.4–3.7× more data per second, needs 2.0–3.5× less CPU per GB and uses 9.5–11.3× less memory (ranges over both MTUs). Across all four committed runs the VM's speed varied: single cells moved by up to 58 % and the canonical run was the slowest, the direct path without a tunnel included. The memory ratio ranged 9.4–13.5×, and the order of the engines never changed (`09-feasibility-report.md` §4). These are virtual-machine numbers and phones are slower, so M10 measures the chosen engine on devices against the V1 targets (`01-scope.md` §7). The decision rests on protocol fit (HTTP CONNECT and TLS without a local listener), memory safety and extensibility; if devices miss the targets, ADR-0001 names hev as the fallback.

## 2. Go modules in the engine build (spike, `go list -deps`)

| Module | Version | Licence | Notes |
|---|---|---|---|
| `gvisor.dev/gvisor` | `v0.0.0-20261029062900-94fcb330700e` | Apache-2.0 (no NOTICE file) | `go` branch, generated for `go get`; bump monthly or on advisory |
| `golang.org/x/net` | v0.60.0 | BSD-3-Clause + PATENTS | `dns/dnsmessage` for the DNS responder |
| `golang.org/x/sys` | v0.48.0 | BSD-3-Clause + PATENTS | TUN ioctls, socket options |
| `golang.org/x/time` | v0.15.0 | BSD-3-Clause + PATENTS | indirect (gVisor) |
| `golang.org/x/exp` | `v0.0.0-20250711185948-6ae5c78190dc` | BSD-3-Clause + PATENTS | indirect (gVisor) |
| `github.com/google/btree` | v1.1.2 | Apache-2.0 | indirect (gVisor) |
| Go toolchain / standard library | 1.27.3 | BSD-3-Clause | `crypto/tls` for TLS-to-proxy |

All are compatible with any licence the owner picks for ProxyFlow (D8). Apache-2.0 requires reproducing the licence text in the app's notices (G1 "Open-source licences").

`go.sum` was generated from an offline module mirror in the M1 environment. `go mod verify` alone would only compare the module cache with `go.sum`, so CI removes `go.sum`, re-creates it from `proxy.golang.org` (each hash checked against `sum.golang.org`) and fails if it differs from the committed file (`.github/workflows/spike.yml`). Regenerating it the same way from the offline mirror gave an identical file; the public check has not run yet because those hosts are blocked here. `govulncheck` could not run offline in M1 and is added to CI from M4.

## 3. Planned Android dependencies (pinned in M2)

| Dependency | Licence | Purpose | Notes |
|---|---|---|---|
| Kotlin stdlib, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 | Language, concurrency, JSON for the engine boundary | |
| Jetpack Compose, Material 3, Navigation, Lifecycle | Apache-2.0 | UI | |
| Room, DataStore, WorkManager | Apache-2.0 | Profiles/logs, settings, log retention | |
| Android Keystore (platform) | — | Credential key (ADR-0006) | |
| Google Tink (only if D4 = Tink) | Apache-2.0 | AEAD alternative | |
| gomobile (build tool) | BSD-3-Clause | Generates the engine AAR | Not shipped as code beyond generated bindings |
| Testing: JUnit 4, AndroidX Test, Espresso/Compose test, Robolectric, Turbine | EPL-1.0 / Apache-2.0 / MIT | Tests only | Not shipped |

Explicitly **not** used: `androidx.security:security-crypto` (all APIs deprecated in 1.1.0), any analytics, advertising or crash-reporting SDK, any proxy/VPN SDK that phones home.

## 4. Lab tools (not shipped)

Squid 6.14 (GPL-2.0-or-later), Dante 1.4.3 (BSD-style), stunnel (GPL-2.0-or-later), tcpdump 4.99.4 (BSD-3-Clause), TShark 4.2.2 (GPL-2.0-or-later), iperf 3.16 (BSD-3-Clause), curl 8.5.0 (curl licence), Python 3.13, pytest (MIT), `cryptography` (Apache-2.0/BSD). hev-socks5-tunnel 2.19.3 is built only as a comparison target, with a test-only IPv4 fallback patch (`spike/hev/ipv4-only-test-patch.diff`) because the lab kernel has IPv6 disabled.

## 5. Ongoing process

- Monthly: bump gVisor's `go` branch pin, run the full lab suite (E01–E16) and fuzzers before merging.
- On every PR: the `go.sum` check against `sum.golang.org`, `govulncheck`, OSV-Scanner on the Gradle lockfile, Gradle dependency verification metadata.
- Before each release: licence inventory regenerated into the in-app notices; this review updated.
