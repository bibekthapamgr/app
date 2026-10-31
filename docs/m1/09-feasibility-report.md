# 09 — Feasibility report (M1 spike)

**Milestone:** M1 · **Date:** 30 Oct 2026 · **Canonical evidence:** `spike/evidence/run-m1-20261030T003301Z/`, produced by the code now in the repository (SUMMARY.md, JUnit XML, Go test JSON, 20× repeat log, fuzz log, binary sizes, per-test packet captures and engine logs). Three earlier runs are kept as history (§3.2). · **How to reproduce:** §8

## 1. Question and answer

**Question (M1 task 9):** can a maintained userspace TCP/IP engine take TUN traffic, as Android's `VpnService` delivers it, and carry real TCP requests through a controlled proxy — without DNS, UDP, ICMP or direct leaks, and failing closed?

**Answer: yes, demonstrated end to end in a Linux lab that emulates Android's VPN routing; not yet demonstrated on an Android device.** The Android SDK/NDK could not be downloaded in the M1 environment (blocker B2, §6), so the on-device part moves to M4/M5 with a concrete plan.

Recommendation: **GO** — approve the networking approach (ADR-0001 to ADR-0004) so M2 can start, with the conditions in §7.

## 2. What was built

| Part | Path | Size / notes |
|---|---|---|
| Engine prototype (Go) | `spike/engine/` | gVisor netstack + TCP forwarder (dial-before-accept), fake-IP DNS, UDP policy, ICMP echo filter, SOCKS5 / HTTP CONNECT / TLS adapters, stats and events; Linux harness binary `pfspike` |
| Engine tests | `spike/engine/**/_test.go` | 36 test functions with 8 subtests, plus 3 fuzz targets whose 7 seed inputs also run as tests (54 passing results in `go test -json`) |
| Lab harness (Python) | `spike/harness/` | Network namespaces, TUN, policy routing, test PKI, instrumented proxy and destinations, raw ICMP injection (small and fragmented), packet capture and analysis |
| End-to-end tests | `spike/tests/test_e2e.py` | E01–E16, each with explicit checks and a verdict file |
| Runner and CI | `spike/run_spike.sh`, `.github/workflows/spike.yml` | One command. The CI workflow (Ubuntu 24.04 with IPv6, `go.sum` re-checked against `sum.golang.org`, evidence upload) is written but has **not run yet**: no repository host is connected |

### Lab topology

```
pf-dev  "the phone"   dev-up 10.10.0.2  + tun0 198.18.0.1 (the "VPN")
   |                  policy routing: unmarked traffic -> tun0
   |                  SO_MARK 438 (= VpnService.protect / app excluded) -> dev-up
pf-rtr  "the internet" routes between namespaces; underlying DNS 10.10.0.1:53
   |          \
pf-prx "proxy" 10.20.0.2              pf-web "destinations" 10.30.0.2
  test SOCKS5 :1080 (+auth :1082)       HTTP :80 :8081, HTTPS :443
  test HTTP CONNECT+auth :3128          UDP echo :5000, iperf3 :5201
  Dante 1.4.3 (SOCKS5, user auth) :1081
  Squid 6.14 (HTTP, Basic) :3129
  stunnel TLS fronts :1443 :1444(expired) :1445(untrusted) :3443 :3444
```

Captures are taken at four points: `tun0` (what apps sent), `dev-up` (everything that left the phone), the proxy host, and the destination. The core leak check — "nothing left the phone except to the proxy endpoint" — is evaluated on `dev-up` in every test that captures traffic (all except E12–E14). A missing capture fails the check, and so does an empty one unless the test expects no uplink traffic at all (E08, E09).

How Android is emulated: the TUN device and default route into it stand in for `VpnService.Builder.establish()`; socket mark 438 plus a policy rule stands in for `protect()` / `addDisallowedApplication(self)`; an `iptables` rule rejecting unmarked traffic on the uplink stands in for "Block connections without VPN".

## 3. Results

Run `run-m1-20261030T003301Z` (kernel 6.18.44, Go 1.27.3, Python 3.13):

- **End-to-end: 20/20 passed, 1 skipped (E06 IPv6), 149/149 checks passed.**
- **Go tests: 54/54 passing results** with the race detector; then the engine package ran **20 more times** under the race detector, and each of its 9 tests passed all 20 times (`go-test-repeat.log`).
- **Fuzzing:** no crashers. The SOCKS5 reply parser and the DNS responder were fuzzed for the full 30 s (about 0.58 M and 1.18 M inputs). The CONNECT response parser stopped making progress after about 22 s (about 0.51 M inputs; B7 in §6).

| Test | What it proves | Result |
|---|---|---|
| **E01** | **Core M1 requirement:** one TCP request from an "app" through the TUN, the engine and a controlled SOCKS5 proxy to a destination | PASS 11/11 |
| E02 | SOCKS5 username/password against real Dante; wrong password fails closed (`AUTH_FAILED`) | PASS 7/7 |
| E03 | HTTP CONNECT with Basic auth against real Squid; HTTPS end-to-end; per-destination refusal keeps the tunnel up | PASS 11/11 |
| E04 ×6 | TLS to proxy: valid SOCKS5+TLS hides hostname and payload; valid HTTP+TLS with sign-in also hides the password; expired / wrong host / untrusted / plaintext-port fail closed with specific codes | PASS 44/44 |
| E05 | Plaintext SOCKS5 shows password, hostname and payload; plaintext HTTP shows the Base64 credentials and the CONNECT hostname (Play-policy evidence) | PASS 6/6 |
| E07 | DNS: fake-IP answers; queries to 8.8.8.8 and to the underlying resolver intercepted; zero DNS on the uplink | PASS 10/10 |
| E08 | Non-DNS UDP incl. UDP/443 rejected inside the tunnel, apps get ICMP unreachable, zero UDP on the uplink | PASS 9/9 |
| E09 | No ping is ever answered: kernel pings (small and 2,000-byte fragmented) and raw IPv4/IPv6 echo requests injected at the TUN (small, 2 KB fragmented, behind an extension header, atomic fragment) get no reply and no ICMP fragment back; the engine dropped 12 requests and 4 later fragments before its stack; zero ICMP on the uplink | PASS 9/9 |
| E10 | Proxy outage (proxy process stopped, port closed): hostname and IP-literal requests refused in 0.011 s, nothing direct, automatic recovery | PASS 10/10 |
| E11 | Engine crash with TUN up → blocked; TUN removed without lockdown → **leak** (negative control); with lockdown emulation → blocked | PASS 8/8 |
| E12 | 200 concurrent requests complete and every flow is released; then 200 flows are held open and the engine is stopped under them: `Stop()` 24 ms, goroutines 608 → 2, and the last app saw its connection end 17 ms after the stop signal (measured inside the apps) | PASS 8/8 |
| E13 | Throughput / CPU / memory vs hev-socks5-tunnel (same VM, same proxy) | PASS 1/1 (data) |
| E14 | No credentials or payload in engine output; destinations only with the privacy switch on | PASS 3/3 |
| E15 | Same harness against hev-socks5-tunnel 2.19.3 (SOCKS5 only) | PASS 6/6 |
| E16 | Proxy hostname resolved on the underlying network only (at least one lookup seen, and only of proxy names); nothing else | PASS 6/6 |
| E06 | IPv6 end-to-end | **SKIPPED** — lab kernel booted with `ipv6.disable=1`; IPv6 TCP covered by hermetic tests and IPv6 ping by E09's raw injection; set up to run in CI, which has not run yet |

### 3.1 E01 in packets

The same request seen at the four capture points (`test_E01_…/pcap/`):

| Capture | What it shows |
|---|---|
| `1-app-tun0` | App asks DNS `A web.test` → engine answers `198.18.1.0` (fake); app opens TCP to `198.18.1.0:80` and sends `GET /nonce/715d6e82d14778ea` |
| `2-device-uplink` | Only `10.10.0.2 → 10.20.0.2:1080`: SOCKS5 greeting, `CONNECT` request/response, then the same `GET` inside the tunnel. No DNS, no packet to `10.30.0.2` |
| `3-proxy-host` | Proxy receives `CONNECT` with the **hostname** `web.test` and only then opens TCP from `10.20.0.2` to `10.30.0.2:80` |
| `4-destination` | Destination sees the request from the **proxy's** address `10.20.0.2`; the response echoes the nonce |

Engine stats for the run: 1 DNS mapping, 1 TCP flow, 93 bytes up / 237 down, 0 failures, RSS 12.0 MiB; the request took 0.027 s.

### 3.2 Reproducibility and history

| Run | Code | Result | Key figures |
|---|---|---|---|
| `run-20261029T151316Z` | First complete spike | 20/20, 144/144 checks, Go 50/50 | E01 0.015 s · E10 0.009 s · gVisor MTU 1500 download 687 Mbit/s |
| `run-verify-20261029T161415Z` | Fresh copy of the repository, after the harness portability fix | 20/20, 144/144, Go 50/50 | E01 0.016 s · E10 0.010 s · 836 Mbit/s |
| `run-final-20261029T164118Z` | After the first review: IPv6 ping filter, E12 rework, redacted lab configs | 20/20, 148/148, Go 51/51 + 20× | E01 0.018 s · E10 0.008 s · 613 Mbit/s |
| **`run-m1-20261030T003301Z`** | **Current code**, after the second check: fragment-proof ping filter, app-side E12 timing, stricter checks | **20/20, 149/149, Go 54/54 + 20×** | E01 0.027 s · E10 0.011 s · 575 Mbit/s |

Every run passed its own test suite, so the harness is stable. That did not make the earlier engines correct: the first two answered plain IPv6 pings and the first three answered large, fragmented IPv6 pings, which their tests did not probe (§5). The first attempt at the fresh-copy run failed during lab setup and exposed a harness defect (§5).

## 4. Performance and resources (indicative)

Single 2-vCPU VM, veth links, Dante as proxy; numbers are relative, not phone performance. Canonical run:

| Engine | MTU | Upload Mbit/s | Download Mbit/s | CPU s/GB | RSS after run |
|---|---|---|---|---|---|
| gVisor engine (spike) | 1500 | 1,172 | 575 | 10.63 | 27.3 MiB |
| gVisor engine (spike) | 9000 | 3,803 | 2,749 | 2.31 | 23.1 MiB |
| hev-socks5-tunnel, offloads off | 1500 | 2,121 | 2,119 | 3.01 | 2.4 MiB |
| hev-socks5-tunnel, offloads off | 9000 | 5,319 | 4,379 | 1.16 | 2.4 MiB |
| hev with Linux TSO/USO offloads (not available on Android) | 1500 | 5,793 | 5,592 | 0.87 | 2.6 MiB |

- MTU 9000 is the biggest lever for the gVisor engine: 3.2× upload, 4.8× download, 4.6× less CPU per GB. At MTU 9000 hev stays 1.4–1.6× faster and 2.0× cheaper per GB.
- Across the four runs every engine moved together: the canonical run was slower across the board, including the direct path without any tunnel (14.9 Gbit/s against 15.7–17.0). Single cells varied by up to 58 % (hev at MTU 1500, upload) and the gVisor engine's memory after the run by 44 % (22.7–32.7 MiB); the order of the engines and the effect of MTU 9000 never changed.
- Memory under load: idle 10.6 MiB, peak 20.6 MiB with 200 concurrent flows (E12).
- Binary size (`binary-sizes.txt`): the engine cross-compiles for `android/arm64` without cgo to 8.6 MiB stripped; the stripped Linux x86-64 build is 8.3 MiB against 335 KiB for hev (other ABIs and the gomobile AAR need the NDK).
- Conclusion: the gVisor engine costs more CPU and memory than hev. Whether that matters on phones is measured in M10 against `01-scope.md` §7, with hev as the documented fallback.

## 5. Defects found during M1 (all fixed)

Running real proxies, a clean-copy rerun and two independent checks found real bugs, which is the point of the "compiles ≠ works" rule:

| Defect | Found by | Fix |
|---|---|---|
| **The engine answered IPv6 pings for any address** (gVisor's IPv6 layer replies to echo requests in promiscuous mode; the IPv4 layer does not). Docs claimed pings were never faked; E09 had only tested IPv4 | First independent review (raw ICMPv6 injection) | `echoFilter` link wrapper between the TUN and the stack |
| **Large IPv6 pings still got a fake reply** (`ping -6 -s 2048`: the request arrives in fragments, gVisor reassembles and answers, and the reply leaves in fragments the first filter did not recognise) | Second independent check | The filter walks IPv6 extension headers, drops every fragment of an ICMP packet in both directions and counts once per reply; `TestPingIsNeverAnswered` adds fragmented, extension-header and atomic-fragment pings (the engine answers 7 reply packets with the filter removed, none with it); `TestEchoFilterBlocksOutboundReplies` tests the outbound line alone; E09 adds the same raw probes and 2,000-byte kernel pings; the checker's own probe script, rerun against the fixed engine (MTU 1500 and 9000, up to 12 KB pings), gets no reply (`spike/evidence/second-check-ping-probes-20261030/`) |
| Engine panic (nil connection) when Squid refused a `CONNECT` (403) | E03 | Adapter returns the original connection on every path; regression test `TestDialHTTPConnectRefusalNoPanic`; per-flow panic isolation added |
| `stop_ms` included a fixed 200 ms settle delay and E12 stopped the engine only after all flows had closed, so "202 ms with 200 open flows" was not what was measured | First independent review | `stop_ms` times `Stop()` alone; E12 holds 200 flows open and stops under them; the hermetic stop test is timed too |
| "Apps' connections ended 0.88 s after the stop" was mostly the harness's own waiting (a fixed delay after the engine exits) | Second independent check | Each app records when its connection ended; E12 now reports the last of those (17 ms) |
| Some check labels said more than they tested: E04's "only name visible" checked only that the proxy name was present; E16 passed with no lookup at all; leak checks passed on a missing capture | Second independent check | E04 also requires the destination name to be absent; E16 requires at least one lookup; a missing capture fails, an empty one fails unless no traffic is expected |
| Lab could not start from a directory below a private parent (e.g. a 0750 home): Squid and tcpdump drop root privileges before writing; a failed setup also left proxies and namespaces running | Clean-copy rerun | Squid runs from a world-traversable temp dir and its logs are copied into evidence; tcpdump keeps root (`-Z root`); the lab fixture always tears down (`try/finally`) |
| Lab secrets in evidence: hev's config held the Dante password in plain text, and the harness left its `pfdante` host account behind | First independent review | hev's real config lives in a private temp dir with a redacted copy in evidence (earlier runs redacted after the fact); the account is deleted on teardown; key and bundle files are git-ignored |
| `go vet` rejection of an `errors.As` target type | build | Refusal detection rewritten |
| Stale stats read in the outage test | E10 | Wait for the next snapshot |
| Packet analysis counted ICMP errors (which quote UDP headers) as UDP leaks | E08 | ICMP classified first |
| hev cannot open its dual-stack socket on an IPv6-disabled kernel | E15 | Test-only patch (`spike/hev/`), not relevant on Android |
| Unfair first benchmark (hev using Linux-only offloads) | E13 | Offloads off by default; offload row kept for reference only |

## 6. Limitations, blockers and remediation

| ID | Limitation / blocker | Impact | Remediation (owner, milestone) |
|---|---|---|---|
| B1 | Lab kernel has IPv6 disabled → E06 skipped | IPv6 TCP proven by hermetic tests only; IPv6 ping by raw injection | The first CI run (Ubuntu 24.04, IPv6 enabled) runs E06 and the kernel IPv6 pings in E09 (dev, M2); device IPv6 tests in M6 |
| B2 | Android SDK, NDK and Gradle downloads blocked in the M1 environment (no `dl.google.com`, `maven.google.com`) → no APK, no `VpnService` on a device | The Android integration (gomobile AAR, `VpnService.Builder`, FGS type, lockdown) is designed but untested | Allow the Android/Gradle/Go hosts listed in the acceptance report, or run M2–M5 on a developer machine. First M4/M5 task: run E01-equivalent on a device with PCAPdroid/tcpdump on a rooted test phone or emulator |
| B3 | Performance measured on a VM, whose speed varied by up to 58 % between runs | Not representative of phones | M10 device benchmarks against `01-scope.md` §7 |
| B4 | Lockdown emulated with `iptables` | Android's real lockdown may differ (e.g. exemptions) | M8 device tests with packet capture |
| B5 | Two hermetic Go tests flaked under extreme race-detector load earlier in M1 (root cause: the gVisor *test client* stack sometimes resets a valid SYN-ACK; the engine correctly reports `APP_HANDSHAKE_FAILED`) | Test reliability only; product path unaffected | Client retries added; in the canonical run every engine test then passed 20 times in a row under the race detector (`go-test-repeat.log`); replace the test client with a kernel-socket client in M4 |
| B6 | `go.sum` generated from an offline module mirror | Unverified against the public checksum database until CI runs | CI regenerates it with `sum.golang.org` verification and fails on any difference (regeneration from the mirror already matches) |
| B7 | The CONNECT-parser fuzzer stops making progress before its 30 s end in 3 of the 4 runs (after about 18–24 s; about 22 s in the canonical run) | That parser got less fuzzing than its time budget. It reads a finite buffer under byte and line caps, so it cannot hang on any input; the stall looks like Go's fuzzing engine and is not yet explained | CI fuzzes 5 min per target with `GODEBUG=fuzzdebug=1` from M4; treat as open until explained |

## 7. Conditions attached to the GO recommendation

1. M4/M5 must reproduce E01, E07, E08, E09 and E10 on at least one physical Android device with packet capture before any UI integration work is called done.
2. The engine API stays as in `06-architecture.md` §5 (TLS verification via the platform trust manager; proxy host resolved by Android; credentials held as bytes and cleared on stop).
3. Owner decisions D2 (plaintext proxies in the Play build) and D3 (minSdk) are taken before M5.
4. If device measurements miss the quality bars, switch to the hev fallback in ADR-0001 rather than tuning indefinitely.

## 8. Reproduce

```bash
# Linux host, root; see spike/README.md for packages
cd spike
sudo -E HEV_TAG=2.19.3 FUZZTIME=30s GO_TEST_REPEAT=20 ./run_spike.sh
# → spike/evidence/run-<UTC timestamp>/SUMMARY.md
```
