# M10 — QA, performance and compatibility: acceptance report

**Status: every M10 acceptance criterion passes in CI on every Android version ProxyFlow supports — API 29 to 36, Android 10 to 16 — in all three attempts of one CI run of the same commit, after three independent reviews of the work (A, B, C) and eleven rounds of review of their fixes (F to P), and a fact-check of this report; every finding was fixed or, where the behaviour is a deliberate trade-off, documented. What only a physical phone shows is the owner's to check, with their own proxy (`docs/m10/physical-phone-checklist.md`): M1's condition 1 (the lab checks E01 and E07–E10 on a physical phone with packet capture), the battery bar, a real move between Wi-Fi and mobile data, a network with a sign-in page, and the phone maker's own battery management — none of which an emulator can give.**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m10-qa-performance`, pull request #9 (base: `m9-ui-integration`, stacked on #8, M9) |
| Verified commit | `d48f4f8` — the M10 code, tests, scripts and screenshots; the commits after it change documents only |
| CI runs of that commit | `android` run 42344467318: attempts 1, 2 and 3 green · `secrets` runs 42344463951 and 42344467430, green |
| Build environment | as in M9: ubuntu-24.04 runners · Go 1.27.3 · gomobile · Android NDK r28c · Temurin JDK 21 · Gradle 9.8.2 · Android Gradle Plugin 9.5.0 with built-in Kotlin 2.4.21 · compileSdk/targetSdk 36 · minSdk 29 · Room 2.8.5 · DataStore 1.3.0 · Robolectric 4.16 with Roborazzi for the screenshots |
| Test environment | JVM and Robolectric tests on the runner; emulators API 29, 30, 31, 32, 33, 34, 35 and 36, `google_apis` x86_64, booted fresh (`-no-snapshot`), with the engine built in the same run and the lab of M5–M8 (`scripts/e2e_env.sh`): Dante and Squid with sign-in, stunnel's TLS fronts (valid, wrong name, expired, untrusted CA), a gate in front of Dante, silent and closed ports, the destinations reachable only through a proxy, the bulk server, the UDP sinks, the witness, two logging resolvers; the uplink captured by the emulator and, on Wi-Fi, by tcpdump inside the phone |
| Release candidate | version 0.10.0 (10): the verified commit's R8-minified release APK and App Bundle (unsigned until M11), and the same APK signed with a key of that run alone for the owner's phone checks (`docs/m10/qa-report.md` §5) |

The workspace this was written in cannot reach Google's Maven repository or run an emulator, so everything Android ran in GitHub Actions. Locally ran: the core modules' JVM tests under a kotlinc harness (the model, the VPN session and tile, the design system's typography), the evidence scripts' 157 tests, ktlint, shellcheck and actionlint, the fake-adb harnesses of the device scripts, and Java's Unicode bidi algorithm for the text-direction checks; every figure in the M10 documents comes from the CI runs' annotations and logs, read back with `gh`.

## Acceptance criteria

| Criterion | Evidence | Result |
|---|---|---|
| All release-blocking tests pass | The build job: 651 JVM tests, Lint with warnings as errors, ktlint, the 123 screenshots with their accessibility checks, the release manifest and OSV checks, the 157 evidence-script tests; the engine job: 120 Go tests with the race detector; each of the eight device jobs: 75 instrumented tests (0 failed, 0 never finished), M8's failure injection, scenarios N, R and P, and M5's, M6's, M7's, M8's and M10's evidence, every step ✓ — in each of the three attempts (`docs/m10/qa-report.md` §2, `docs/m10/compatibility-matrix.md` §1) | **Pass** |
| No direct bypass occurs in the defined supported-TCP test matrix | On every level and in every attempt: every connection that reached the destination came through a proxy's tunnel; the bulk server's 431 connections were all tunnels (15 Squid, 416 Dante); no line held by fail-closed got out; no datagram reached a sink; no lab name was looked up on the uplink; the uplink's capture while connected (the emulator's on mobile data, the phone's own on Wi-Fi) held no connection to anything but the proxy, the resolver and Android's own checks; the witness was reached only by M8's deliberate checks of Android's kill switch turned off (`docs/m10/qa-report.md` §3.5) | **Pass** |
| No critical/high security defects remain | The reviews found no critical defect and one high (M10-89, an uplink the evidence could not have captured), fixed; M8's mitigation checklist re-checked line by line on the candidate (`docs/m10/mitigation-recheck.md`); every finding in `docs/m10/defect-register.md` | **Pass** |
| Memory/CPU/throughput results are recorded against agreed targets | `docs/m10/performance-baseline.md`: each bar measured on every level beside what the emulator's own path gives; connecting and fail-closed within everywhere; throughput within on every mobile-data level and limited by the emulator's Wi-Fi path on one to three of the Wi-Fi levels, a different set each attempt; CPU within on seven and past on API 29's emulator; the engine's memory with data in flight past the 50 MB bar on every level (accepted for V1, M10-63) and within idle | **Pass** (recorded; the bars' phone figures are the owner's) |
| Device/OS compatibility matrix is complete | `docs/m10/compatibility-matrix.md`: every declared Android version (10 to 16) ran the whole suite in each attempt; the version-specific behaviours and how each is covered; the representative devices for M11's closed testing | **Pass** for every declared Android version; physical phones open (the owner's checklist, M11's closed testing) |
| Known limitations are documented and reflected in user-facing copy | `docs/m10/qa-report.md` §4: each limitation, where it is documented, and the string that says it; `CopyClaimsTest` refuses any claim of coverage of all traffic, anonymity or protection | **Pass** |

## The brief's tasks

| Task | Where |
|---|---|
| 1. Run unit, integration, instrumentation and end-to-end tests | `docs/m10/qa-report.md` §2 and §3.1 |
| 2. Test valid/invalid HTTP and SOCKS5 proxies, authentication failures and timeouts | `docs/m10/qa-report.md` §3.2 |
| 3. Test DNS, TLS, IPv4/IPv6, Wi-Fi/mobile switching, captive portals and proxy failure | `docs/m10/qa-report.md` §3.3; ADR-0015 §3 |
| 4. Test permission revocation, process death, reboot and battery optimization effects | `docs/m10/qa-report.md` §3.4; ADR-0015 §4–§6 |
| 5. Use packet captures and controlled endpoints to check direct traffic leakage | `docs/m10/qa-report.md` §3.5 |
| 6. Profile CPU, memory, battery, throughput and latency | `docs/m10/performance-baseline.md`; ADR-0015 §2 |
| 7. Test representative devices and all declared supported Android versions | `docs/m10/compatibility-matrix.md`; ADR-0015 §1 |
| 8. Perform accessibility and localization review | `docs/m10/qa-report.md` §3.8; ADR-0015 §7 |
| 9. File, prioritize and resolve defects; run regression tests | `docs/m10/defect-register.md`; `docs/m10/qa-report.md` §3.9 |

## Deliverables

| Deliverable | Where |
|---|---|
| QA report | `docs/m10/qa-report.md` |
| Performance baseline | `docs/m10/performance-baseline.md` |
| Compatibility matrix | `docs/m10/compatibility-matrix.md` |
| Test evidence | The verified run's annotations (each job's facts and notices, read by `gh api`), its artifacts `android-reports` (reports, screenshots, the APKs and the bundle) and `device-tests-api-NN` (logcat, the captures, the lab's logs, the evidence's inputs); the defect register's run sections; the evidence scripts under `scripts/` with their tests |
| Release candidate | `docs/m10/qa-report.md` §5 |
| Decisions | ADR-0015 (`docs/adr/0015-qa-performance-and-compatibility.md`) |
| The owner's phone | `docs/m10/physical-phone-checklist.md` |
| The security re-check | `docs/m10/mitigation-recheck.md` |

## Reviews

Each review is an independent reading of the code, the runs' logs, annotations and captures, with its own fakes and mutants; each finding is in `docs/m10/defect-register.md` with its fix and the test that guards it.

| Review | Of | Found |
|---|---|---|
| A | The sign-in network code (captive portals) | Fixed |
| B | The QA harness: the performance tests, the scenarios, the evidence | Fixed |
| C | M8's mitigation checklist re-checked on the candidate, and what is new since M8 | One gap since M5 (the notification's Disconnect from a locked shade, M10-17), fixed |
| F | The fixes of A and C | Fixed |
| G | The fixes of B | Fixed |
| H, I, J | Each round's fixes: the fail-closed timing, the memory measurement, the paced upload, the right-to-left layout, the string checker, the restart scenario | Fixed (M10-63, the engine's memory with data in flight, was the CI runs' finding of that time, accepted for V1) |
| K | J's fixes: found API 33's uplink would have had no capture (M10-89, high) | Fixed |
| L, M, N | The text direction of the user's own text, the tile's subtitle on every Android version, the CI scripts' resilience | Fixed, or documented (M10-102, M10-111) |
| O, P | API 33's capture through `su`, the pulls of the phone's captures, API 29's stall | Fixed, or documented (M10-125, M10-126, M10-137) |

138 findings were filed in M10 (M10-1 to M10-138): no critical, 1 high, 42 medium, 95 low; 128 fixed, 9 kept as documented trade-offs (M10-5 and M10-63 accepted for V1; M10-93, M10-102, M10-111, M10-125, M10-126, M10-137; M10-97, an emulator's), 1 open with its diagnostics in place (M10-131).

## Open

- **M10-131:** on API 33's emulator, whose uplink is its Wi-Fi, ProxyFlow twice could not reach its proxy for a moment in runs before the verified one — one line through the proxy refused under Battery Saver, as fail-closed should refuse, and one connect of `ReconnectEndToEndTest` timed out — and in neither did anything get around the proxy. Not seen in the verified run's three attempts. The scenario now records ProxyFlow's session, the phone's addresses and Android's power-save state when it happens again; a real phone on a real network is the checklist's.
- **The owner's phone:** `docs/m10/physical-phone-checklist.md` — the lab checks with a capture at a laptop's hotspot, the battery bar overnight, a real move between networks, a network with a sign-in page, Private DNS strict, a restart with Always-on VPN, the maker's battery management, the tile with TalkBack. Its table in the compatibility matrix (§3) is filled when run.
- **M11:** signing (the upload key, Play App Signing), the store listing and closed testing, where the crash-free bar (≥ 99.8 %) and the real-device spread (`docs/m10/compatibility-matrix.md` §4) are measured; the first translations (decision D8).
- **Accepted for V1:** M10-5 (SI symbols in code until D8), M10-63 (the engine's memory with 200 connections and 256 KiB in flight on each: 54.9 to 71.0 MB against 50, the buffers that carry the throughput bar).

## How to check

- CI evidence: run 42344467318's attempts — the build job's annotations (the test counts, the release manifest, OSV, the sizes), the engine job's, and the device jobs' (the test counts per level, the M5–M8 evidence, M10's performance facts and bars, the scenarios); the artifacts `android-reports` and `device-tests-api-NN`.
- Locally: `./gradlew testDebugUnitTest :core:model:test :lint-rules:test` (the JVM tests, the accessibility checks), `./gradlew :app:verifyRoborazziDebug` (the screenshots), `python3 -m unittest discover -s scripts/tests` (the evidence scripts), `./gradlew :core:data:connectedDebugAndroidTest :core:engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest` on an emulator with the lab (`scripts/e2e_env.sh`), then `scripts/m8_failure_injection.sh`, `scripts/m10_scenarios.sh` and the evidence scripts as `scripts/e2e_device.sh` runs them.
- On a phone: `docs/m10/physical-phone-checklist.md` with the run's test-signed release APK.
