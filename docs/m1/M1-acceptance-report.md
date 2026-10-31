# M1 acceptance report — Requirements, Feasibility & UI/UX

**Project:** ProxyFlow Android · **Milestone:** M1 · **Report date:** 30 Oct 2026 · **Prepared by:** Claude (engineering) for the product owner

**Status: ready for owner review — not complete until the owner approves the networking approach (D1) and the screens.** Per the brief, no full implementation starts before that approval; the only code in the repository is the feasibility spike.

## 1. Verdict

| Acceptance criterion (brief) | Result | Evidence |
|---|---|---|
| All screens and error states are designed and reviewed | **Designed; self-reviewed and independently checked (§5); owner review pending** — 43 boards (36 clickable phone screens): all six required screen areas (Home, Profiles, Add/Edit, Settings, Logs, About/Privacy) plus onboarding, permission and blocking dialogs, notifications, the Quick Settings tile and dark theme; every engine and system error code (25) has a designed presentation | Design file (link in §6), `04-ux-screens-and-error-states.md` |
| A written protocol/traffic support matrix exists | **Met** — protocols, auth, TLS, TCP v4/v6, DNS record types, UDP, ICMP, LAN, uncaptured traffic, failure behaviour, allowed product claims; each row tagged lab-verified, unit-verified, device check or not supported | `02-traffic-support-matrix.md` |
| Engine selection and dependency licensing are documented | **Met** — eight options compared on licence, maintenance, CVEs, IPv6, resources, protocol fit; gVisor netstack proposed with hev-socks5-tunnel as fallback; every Go module and planned Android dependency licensed | `08-dependency-review.md`, ADR-0001 |
| Feasibility spike demonstrates actual proxy-routed TCP traffic or records a specific blocker and remediation plan | **Met in a Linux lab that emulates Android VPN routing**: a TCP request travelled TUN → engine → controlled SOCKS5 proxy → destination, with packet captures at four points showing nothing left the "phone" except to the proxy (E01). **On-device run blocked** (Android SDK/NDK not downloadable in the M1 environment) — recorded as blocker B2 with a remediation plan | `09-feasibility-report.md`, `spike/evidence/run-m1-20261030T003301Z/` |
| No full implementation begins until the networking approach is approved | **Met** — no app modules exist; ADR-0001…0004 are "Proposed" | Repository tree; `docs/adr/README.md` |

## 2. Tasks → outputs

| # | Task (brief) | Output |
|---|---|---|
| 1 | Confirm Android-only, free, no account/backend/admin, user-provided proxies | `01-scope.md` §1–5 |
| 2 | Protocols HTTP CONNECT and SOCKS5; optional TLS-to-proxy | `02-traffic-support-matrix.md` §1, ADR-0002, ADR-0005 |
| 3 | Limitations for UDP, ICMP, IPv6, DNS, unsupported traffic; no "all traffic" claim | `02-traffic-support-matrix.md` §2–5, ADR-0003, ADR-0004 |
| 4 | Journeys: first launch, add profile, test proxy, VPN permission, connect, reconnect, disconnect, error recovery | `03-user-journeys.md` J1–J11 with state model and acceptance checks |
| 5 | Screens: Home, Profiles, Add/Edit, Settings, Logs, About/Privacy | Design file: A1–A3, B1–B11 (with B6a–B6d), C1–C3, D1 and D3–D9, E1, E1b, E2, F1–F3, G1, H1–H3, error catalogue, 4 dark boards |
| 6 | Design tokens (navy, teal, light/dark backgrounds), Material 3, accessible text | `design/tokens/proxyflow.tokens.json`, `05-design-tokens.md`, contrast report: all required pairs pass WCAG 2.2 AA |
| 7 | Evaluate TUN/TCP engines and dependencies | `08-dependency-review.md` (+ measured comparison E13/E15) |
| 8 | Threat model and ADRs | `07-threat-model.md` (STRIDE, 30 threat entries, 6 residual risks), `docs/adr/0001…0007` |
| 9 | Spike: controlled SOCKS5 proxy, one supported TCP request | `spike/` + E01 (and 19 further end-to-end tests) |

## 3. Deliverables

| Deliverable (brief) | Where |
|---|---|
| Design file | "ProxyFlow V1 — Screens & Error States" (interactive canvas, §6) and `design/screens/` |
| Scope document | `docs/m1/01-scope.md` |
| Architecture diagram | `docs/m1/06-architecture.md` with `docs/diagrams/architecture-components.svg`, `packet-path.svg`, `connect-sequence.svg` |
| Threat model | `docs/m1/07-threat-model.md` with `docs/diagrams/threat-model-dfd.svg` |
| Dependency review | `docs/m1/08-dependency-review.md` |
| Feasibility report | `docs/m1/09-feasibility-report.md` |

Definition-of-done items for every milestone: source code (`spike/engine`, `spike/harness`), automated tests (36 Go test functions — 54 results with subtests and fuzz seeds — 3 fuzz targets, 21 end-to-end test cases), demonstration evidence (four committed runs with packet captures and engine logs; the canonical one was produced by the code now in the repository), documentation (this folder, ADRs, READMEs), and this written report.

## 4. Test evidence summary

Run `run-m1-20261030T003301Z` (Linux 6.18, Go 1.27.3), produced by the code now in the repository:

- End-to-end **20/20 passed, 1 skipped** (E06 IPv6: lab kernel has IPv6 disabled), **149/149 checks**.
- Go unit and hermetic tests **54/54** with the race detector; the engine package then ran **20 more times** under the race detector and each of its 9 tests passed every time; fuzzing without crashes (one of the three parsers got about 22 s of its 30 s, B7).
- Highlights: the E01 request took 0.027 s, with the hostname delivered to the proxy (remote DNS) and zero DNS, UDP or direct packets on the uplink; SOCKS5 auth against Dante and HTTP CONNECT auth against Squid; six TLS-to-proxy cases (two valid, four failing closed with specific codes); with the proxy's port closed, connections were refused in 0.011 s and traffic recovered automatically; no ping got an answer, large fragmented IPv6 pings included; `Stop()` with 200 connections open took 24 ms (goroutines 608 → 2) and the last app saw its connection end 17 ms after the stop signal.
- Negative control E11-B shows Android-style teardown **without** lockdown leaks — which is why the app guides users to Always-on + "Block connections without VPN".
- Three earlier runs passed their own, smaller test suites as well, one of them from a clean copy of the repository. Their engines still answered IPv6 pings their tests did not probe (§5). Performance varied between runs by up to 58 % on one throughput cell and 44 % on the gVisor engine's memory; the canonical run was the slowest across the board (`09-feasibility-report.md` §3.2 and §4).

## 5. Independent checks and what changed

Two separate agents that had not seen the work being produced checked it against the brief, the primary sources, the code and the evidence, and reproduced their main findings by experiment. All findings below are fixed.

**First review** — one blocker, five should-fix items and smaller accuracy points:

| Finding | Severity | Fix | Proof now |
|---|---|---|---|
| The engine answered IPv6 pings for any address (gVisor's IPv6 layer replies on its own), while the docs said pings are never faked; E09 had only tested IPv4 | **Blocker** | Echo filter between the TUN and the stack; every affected statement corrected | Superseded by the second check's fix below |
| "Stop in 202 ms with 200 open flows" was a fixed 200 ms wait, measured after the flows had already closed | Should fix | `stop_ms` now times `Stop()` alone; E12 stops the engine with 200 connections held open | E12: 24 ms, goroutines 608 → 2 |
| Docs said the decrypted secret lives only during the handshake, but every app connection signs in to the proxy | Should fix | Real lifetime documented: held by the engine as bytes for the session, zeroed on stop | ADR-0006, `06-architecture.md` §5, threat I-12 |
| Lab password in evidence (hev config), key bundles not ignored, a lab system account left on the host | Should fix | Redaction at source, ignore rules, account deleted on teardown | `spike/README.md`, `.gitignore` |
| Docs disagreed on whether usernames are encrypted | Should fix | Username and password encrypted together | ADR-0006, `06-architecture.md` §7 |
| Error levels in the UX docs disagreed with the engine (`INTERNAL`, `PROXY_GENERAL_FAILURE`, which B6 states block) | Should fix | Levels aligned with `IsProxyLevel()`; B6 split into B6a–B6d | `04-ux-screens-and-error-states.md` §3–4 |
| Smaller points: evidence cited the wrong test or claimed more than was captured; no evidence for the Android binary size or the flake fix; an unfair binary-size comparison; `go mod verify` described as an online check; four journey states without a board; two off-token colours; an unverifiable source | Minor | Each corrected; binary sizes and the 20× repeat recorded in the run; CI regenerates `go.sum` against the public checksum database; boards E1b, D9 and B11 added | Feasibility report §5–6 |

**Second check**, after those fixes — one blocker and several smaller points:

| Finding | Severity | Fix | Proof now |
|---|---|---|---|
| Large IPv6 pings still got a fake reply: `ping -6 -s 2048` arrives in fragments, gVisor reassembles and answers, and the fragmented reply passed the filter | **Blocker** | The filter walks IPv6 extension headers and drops every fragment of an ICMP packet in both directions; replies are counted once per reply | `TestPingIsNeverAnswered` (fragmented, extension-header and atomic-fragment pings: 7 reply packets with the filter removed, none with it); `TestEchoFilterBlocksOutboundReplies`; E09 with 2,000-byte kernel pings and raw fragmented probes: no reply; the checker's own probe script, rerun against the fixed engine at MTU 1500 and 9000, gets no reply either (`spike/evidence/second-check-ping-probes-20261030/`) |
| "Apps' connections ended 0.88 s after the stop" was mostly the harness's own waiting | Should fix | Measured inside the apps | E12: 17 ms |
| HTTP CONNECT without sign-in was marked lab-verified, but every lab HTTP proxy requires sign-in | Should fix | Marked unit-verified | Traffic matrix §1 |
| "Immediate refusal" was claimed for proxies that time out; E10 only covers a closed port | Should fix | Split into refused (at once) and silent (waits for the 10 s connect timeout) | Traffic matrix §4 |
| Traceroute described as never answered, but UDP traceroute gets ProxyFlow's own port-unreachable | Should fix | Wording aligned with the traffic matrix | Scope §4 |
| Smaller points: the largest cross-run spread was memory, not throughput; the repeat log did not show the count; CI was described as running although it never has; the `MaxInFlight` effect; a DNS claim not limited to UDP/53; some check labels said more than they tested (E04, E16, empty captures); an untested `AUTH_FAILED` path | Minor | Each corrected; checks tightened and re-run; the repeat log lists every test's 20 passes; unit test added for the `AUTH_FAILED` path | Feasibility report §5–6 |

Re-running the spike from a clean copy also exposed a lab-harness defect (the lab could not start below a private home directory and left processes behind on failure); it is fixed and listed in the feasibility report §5.

## 6. Links

- Design canvas (private until shared): https://claude.ai/artifact/XGu7kBhfPpKJFKo8JUgEWB
- M1 report document (private until shared): https://claude.ai/artifact/UTHTqnki1hTA515bNugJtp
- Repository package: `proxyflow-android-m1.zip` (all docs, design files, spike source and evidence; binaries and lab private keys excluded)

## 7. Open items and risks

| ID | Item | Plan |
|---|---|---|
| B1 | IPv6 end-to-end (E06) and kernel IPv6 pings not run in the M1 lab | First CI run on Ubuntu 24.04 (the workflow exists but has not run); device tests in M6 |
| B2 | No Android build or on-device `VpnService` test (SDK/NDK hosts unreachable) | Allow the hosts below or use a developer machine; reproduce E01/E07/E08/E09/E10 on a device first thing in M4/M5 |
| B3 | Performance known only from a VM whose speed varied by up to 58 % between runs | Device benchmarks in M10 against `01-scope.md` §7 |
| B4 | Lockdown behaviour emulated | Device verification in M8 |
| B5 | Rare hermetic-test flakes under extreme race-detector load (test client, not engine) | Retries added and 20 clean repeats per test recorded; replace the test client in M4 |
| B6 | `go.sum` generated from an offline module mirror | CI regenerates it with checksum-database verification and fails on any difference |
| B7 | The CONNECT-parser fuzzer stalls before its 30 s end in 3 of 4 runs (about 22 s in the canonical run); the parser itself cannot hang | Longer CI fuzzing with debug output from M4; open until explained |
| R-Play | Plaintext proxies vs Play's "encrypt to the tunnel endpoint" rule | Owner decision D2 before M5; both builds possible |

Hosts to allow for M2+ (Android toolchain, Gradle, Go): `dl.google.com`, `maven.google.com`, `repo1.maven.org`, `plugins.gradle.org`, `services.gradle.org`, `proxy.golang.org`, `sum.golang.org`.

## 8. Decisions requested

| # | Decision | Recommendation |
|---|---|---|
| D1 | Approve the networking approach (ADR-0001…0004): gVisor netstack, in-process adapters with dial-before-accept, fake-IP DNS resolved at the proxy, UDP/ICMP blocked and never faked | Approve |
| D2 | Plaintext SOCKS5/HTTP profiles in the Play build | Ask Play policy support, then choose; keep a `requireTlsToProxy` build flag |
| D3 | minSdk | 29 |
| D4 | Credential encryption implementation | Keystore AES-GCM (Tink acceptable) |
| D5 | DNS record types beyond A | Keep AAAA/HTTPS/SVCB → no data, others refused |
| D6 | "Test proxy" target | Handshake + one request to a documented URL |
| D7 | TLS default in the Add-proxy form | Off, with the warning shown (unless D2 requires TLS) |
| D8 | App licence; first translations | Owner choice |

## 9. Sign-off

| Role | Name | Decision | Date |
|---|---|---|---|
| Product owner | | ☐ Approve networking approach ☐ Approve screens ☐ Changes requested | |
| Tech lead | | ☐ Approve ADR-0001…0007 | |
| Security reviewer | | ☐ Threat model reviewed | |
