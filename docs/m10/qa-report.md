# M10 — QA report

**Milestone:** M10 · **Verified commit:** `d48f4f8` · **CI:** run 42344467318, attempts 1–3 · **Decisions:** ADR-0015 · **Defects:** `docs/m10/defect-register.md` · **Performance:** `docs/m10/performance-baseline.md` · **Devices:** `docs/m10/compatibility-matrix.md` · **Phone checks:** `docs/m10/physical-phone-checklist.md`

M10 proves reliability and routing correctness before release. This report says what was tested, how, where and with what result, task by task of the brief; which defects were found and what was done; what the app does not carry and where it says so; and what is left for a physical phone.

## 1. Summary

**Every release-blocking test passes, on every declared Android version, in three attempts of one commit.** The verified commit's run (`d48f4f8`, run 42344467318) ran 651 JVM tests (Robolectric screens, the 123 screenshots with their accessibility checks, the engine's JNI layer), the engine's 120 Go tests with the race detector, 157 tests of the evidence scripts, Lint and ktlint, and on each of the eight emulators (Android 10 to 16) the 75 instrumented tests — the keystore, the engine on the device, the VPN, routing, TLS, logs, notifications, the tile, M10's performance tests — then M8's failure injection and M10's scenarios (a restart with Always-on VPN, power saving, no notification permission), with the uplink captured and the lab's logs read back by M5's, M6's, M7's and M8's evidence and M10's.

**No direct bypass in the supported-TCP matrix (§3.5):** on every level, every connection that reached the destination came through a proxy's tunnel, the bulk server's 431 connections were all tunnels, no line held by fail-closed got out, no datagram reached a sink, no lab name was looked up on the uplink, and the uplink's capture while connected held no connection from the phone to anything but its proxy, its resolver and Android's own checks.

**No critical or high security defect remains.** The reviews found no critical defect and one high, M10-89 (an uplink the evidence could not have captured), fixed; every finding is in `docs/m10/defect-register.md`, and the mitigation checklist was re-checked line by line (`docs/m10/mitigation-recheck.md`).

**Performance is recorded against the targets (§3.6, `docs/m10/performance-baseline.md`):** connecting (≤ 3 s at p90) and fail-closed (< 1 s) are within on every level; throughput down is within on every mobile-data level (120 to 601 Mbit/s) and past only on Wi-Fi levels (one to three of the three, a different set each attempt), where the emulator's own Wi-Fi path is below the bar without ProxyFlow; CPU at 50 Mbit/s is within on seven levels and past on API 29's emulator; the engine's memory with 200 connections and data in flight is past the 50 MB bar on every level (54.9 to 71.0 MB), accepted for V1 as M10-63 (idle, it holds 26 to 31 MiB).

**Known limitations are documented and said in the app (§4).** The owner's phone checks (`docs/m10/physical-phone-checklist.md`) and the wider device spread (M11's closed testing) are what no emulator can give.

## 2. What ran

| Where | What | Result |
|---|---|---|
| CI, build job (`ubuntu-24.04`) | `scripts/check_strings.py`; `scripts/check_tokens.py`; ktlint; Android Lint with `warningsAsErrors`; 651 JVM tests (`testDebugUnitTest` of every module: Robolectric screen tests, `TypographyTest`, `QuickTileTest`, `BidiTextTest`, `CopyClaimsTest`, the engine's JNI tests, the lint rules' 11); the debug, release and App Bundle builds; `scripts/check_release_manifest.py` on the release APK; OSV on the 130 libraries of the release classpath; the third-party notices; the 157 evidence-script tests; the 123 screenshots verified against the committed images (Roborazzi, `verifyAndRecordRoborazziDebug`, light, dark, 200 % font, right to left) with their accessibility checks; the build facts and the digest | Pass: 651 run, 0 failed, 0 skipped; no Lint or ktlint finding; OSV knows no vulnerability; the release manifest as declared; every screenshot matches |
| CI, engine job | The Go engine's 120 tests with the race detector; the AAR for arm64-v8a, armeabi-v7a and x86_64 (16 KiB page alignment checked) | Pass |
| CI, device jobs, one per declared Android version (API 29 to 36, Google APIs x86_64 images, `-no-snapshot`) | The instrumented tests of `core:data`, `core:engine` and `app` (75 on each level, of them 6 skipped in the plain run and run by the scripts that drive them: M8's five kill-switch steps and scenario N's test), against the lab of `scripts/e2e_env.sh` (Dante, Squid, stunnel's TLS fronts, the gate, the bulk server, the destinations, the witness, two logging resolvers); then M8's failure injection (`scripts/m8_failure_injection.sh`), M10's scenarios N (no notification permission, API 33 and up), R (a restart with Always-on VPN) and P (power saving) (`scripts/m10_scenarios.sh`); the uplink captured by the emulator (`-tcpdump`) and, on Wi-Fi (API 33 to 35), by tcpdump inside the phone; then M5's, M6's, M7's and M8's evidence and M10's performance and scenarios facts | Pass on all eight: 75 started, 0 failed, 0 never finished on each; every evidence step ✓ (`docs/m10/compatibility-matrix.md` §1) |
| CI, secrets job | No credential or signing material in any commit; no raw bidi control in any file | Pass |
| Three attempts | Attempt 1 of run 42344467318 and two reruns of the same commit (`gh run rerun`), nothing pushed meanwhile | All three green: 11 jobs each (engine, build, screenshots, eight device jobs), every device test and evidence step passing on every level each time |
| Reviews | Independent reviews A, B and C of M10's work, and re-reviews F to P of each fix round, each reading the code, the runs' logs and captures, and mutation-testing the new tests | 138 findings filed (M10-1 to M10-138), each fixed or kept as a documented trade-off (§3.9) |
| Not run here | A physical phone, a real proxy provider, a real captive portal, a real move between Wi-Fi and mobile data, battery drain, manufacturers' battery management | `docs/m10/physical-phone-checklist.md` (the owner's phone); M11's closed testing |

## 3. The brief's tasks

### 3.1 Unit, integration, instrumentation and end-to-end tests (task 1)

| Layer | Tests | Where they run | Count in the verified run |
|---|---|---|---|
| Unit (JVM) | The model, the validator, the profile store's logic, the connection controller's state machine, the engine's JNI layer against a fake engine, the design system's typography and bidi helpers, the tile's content and its visual order (Java's bidi algorithm), the lint rules | Build job, `testDebugUnitTest` | part of the 651 |
| Unit (Go) | The protocol adapters against mock SOCKS5 and HTTP proxies, TLS validation, the fake-IP resolver, the TCP engine's buffers and shutdown, with the race detector | Engine job | 120 passed |
| Integration (JVM, Robolectric) | Every screen with its ViewModel and a fake store: onboarding, Home in every state, the profile list and form, Settings, Logs, the dialogs; navigation and back; `VpnNotificationsTest`; the copy's claims | Build job | part of the 651 |
| Screenshots with accessibility checks | 123 images: every screen light and dark, twelve at 200 % font on a 320 dp phone, six right to left; each checked for names TalkBack reads and 48 dp targets | Build job, Roborazzi | 123 match |
| Instrumentation (on the emulator) | `KeystoreOnDeviceTest` (credentials encrypted, surviving a restart, in no file in clear), `EngineOnDeviceTest` (the real engine against the lab's proxies), `VpnEndToEndTest`, `RoutingEndToEndTest`, `TlsEndToEndTest`, `LogsEndToEndTest`, `VpnConsentTest`, `NotificationPermissionEndToEndTest`, `QuickTileEndToEndTest`, `PerformanceEndToEndTest` | Device jobs, API 29 to 36 | 75 on each level, 0 failed |
| End to end, scripted | M8's failure injection (the process killed, crashed, the VPN revoked, Always-on with blocking turned off), M10's N, R and P; the lab's logs and the captures read back by the evidence scripts | Device jobs | every step ✓ on each level |
| Evidence scripts' own tests | `scripts/tests` (the parsers, the bars, the string checker, the release manifest checker, the bidi check) | Build and secrets jobs | 157 passed |

### 3.2 Valid and invalid HTTP and SOCKS5 proxies, failed sign-ins and timeouts (task 2)

Every row runs against real proxies on each of the eight emulators (the lab's Dante and Squid, its TLS fronts, the gate and its silent and closed ports), and the engine's adapters are tested against mock proxies for what a lab proxy can't be made to answer.

| Proxy | What must happen | Device tests (every API level) | Engine tests (Go, mock proxies) |
|---|---|---|---|
| SOCKS5 with sign-in, working | Connected; TCP by name, IPv4 and IPv6 address through it | `VpnEndToEndTest.socks5TrafficReachesTheDestinationThroughTheProxyByName`, `RoutingEndToEndTest` (addresses, IPv6, LAN), `EngineOnDeviceTest.socks5AndHttpConnectCarryTcpToTheDestination` | `TestDialThroughProxiesToEcho`, `TestSOCKS5UserPassSuccessIPv6Reply`, `TestSOCKS5NoAuthDomainTarget`, `TestDomainTargetIsResolvedByTheProxy` |
| HTTP CONNECT with sign-in, working | The same | `VpnEndToEndTest.httpConnectTrafficReachesTheDestinationThroughTheProxyByName`, `RoutingEndToEndTest` | `TestHTTPConnectRequestAndBufferedTunnelBytes`, `TestHTTPConnectIPv6LiteralAndNoCredentials`, `TestHTTPConnectSkipsInterimResponses` |
| Either, over TLS (valid certificate) | Connected; the sign-in only inside TLS | `TlsEndToEndTest.socks5OverTlsCarriesTraffic`, `httpConnectOverTlsCarriesTraffic` | `TestTLSToProxyValidation`, `TestTLSToProxyByIPAddress`, `TestTLSResumptionIsVerifiedAgain` |
| Wrong password | Never connected; `AUTH_FAILED`, said as such; nothing changed on the phone | `VpnEndToEndTest.aWrongPasswordNeverConnects`, `VpnConsentTest.aWrongPasswordSaysWhyAndNeverShowsConnected`, `LogsEndToEndTest.aRefusedSignInIsLoggedWithItsStageAndNothingSecret`, `EngineOnDeviceTest.signInFailuresAreReportedAsProxyLevelCodes` | `TestAuthenticationOutcomes`, `TestSOCKS5AuthFailures`, `TestHTTPConnectStatusMapping` |
| No sign-in where one is required | `AUTH_REQUIRED` | `EngineOnDeviceTest.signInFailuresAreReportedAsProxyLevelCodes` | `TestAuthenticationOutcomes` |
| An HTTP proxy offering only Digest | `AUTH_FAILED`, said as unsupported | — (the lab's Squid offers Basic) | `TestHTTPConnectDigestOnlyIsExplained` |
| An HTTP proxy refusing CONNECT (405, 501) | `PROTOCOL_ERROR`, said as "doesn't allow tunnels" | — | `TestHTTPConnectStatusMapping` |
| A profile of the other kind (SOCKS5 at an HTTP proxy, HTTP at a SOCKS5 proxy) | Never connected; `PROTOCOL_ERROR` | `VpnEndToEndTest.aProfileOfTheOtherKindNeverConnects` (new in M10) | `TestSOCKS5NotASocksServer`, `TestSOCKS5TruncatedReply` |
| Closed port | `PROXY_UNREACHABLE`, at once | `VpnEndToEndTest.anUnreachableProxyNeverConnects`, `EngineOnDeviceTest.unreachableAndSilentProxiesFailClosedInTime` | `TestProxyUnreachable` |
| A proxy name that doesn't exist | `PROXY_DNS_FAILED`; nothing looked up but the proxy's name | `RoutingEndToEndTest.aProxyNameThatDoesNotExistNeverConnects`, `EngineOnDeviceTest.theProxyHostIsResolvedByTheAppNotTheEngine` | `TestProxyHostResolution` |
| A proxy that accepts and never answers | `HANDSHAKE_TIMEOUT` after 10 s; nothing changed | `VpnEndToEndTest.cancellingTheCheckChangesNothing`, `RoutingEndToEndTest.aProxyThatHangsIsReportedAsATimeout`, `PerformanceEndToEndTest.aDeadProxysConnectionsAreRefusedQuickly` | `TestHandshakeTimeout`, `TestTimeoutsAreTold`, `TestContextDeadlineDuringHandshake` |
| A proxy that stops answering while connected | Traffic held; reconnecting on its own; nothing around it | `RoutingEndToEndTest.whenTheProxyStopsAnsweringNothingGoesAroundIt`, `ReconnectEndToEndTest.aProxyOutageReconnectsOnItsOwnAndTheSessionGoesOn`, `reconnectingStopsAfterItsLastAttemptWithTrafficHeld` | `TestSOCKS5ConnectReplyNotInTimeIsTheSites` |
| TLS: wrong name, expired, untrusted CA, not TLS, certificate changed while connected | Never connected (or blocked at once); the alert sent; no sign-in sent | `TlsEndToEndTest` (9 tests) | `TestVerifyPeerChecksHostnameBeforeTrust`, `TestTLSMismatchedTransport`, `TestTLSWithoutVerifierIsRefused` |
| SOCKS5 replies: every error code, a truncated reply | Each told apart; the site's failure not taken for the proxy's | `RoutingEndToEndTest.aNameTheProxyCannotResolveFailsWithoutAFallback` (the proxy's own answer for a site it can't reach) | `TestSOCKS5ReplyCodes`, `TestTargetLevelFailures`, `TestSOCKS5TruncatedReply` |

### 3.3 DNS, TLS, IPv4/IPv6, Wi-Fi and mobile, captive portals, proxy failure (task 3)

| Area | What is tested | Tests and evidence (every API level) |
|---|---|---|
| DNS | Names answered on the phone with placeholders and resolved by the proxy; queries to any server (the uplink's, 8.8.8.8) and DNS over TCP answered locally; a name the proxy can't resolve fails without falling back to direct DNS; the proxy's own name looked up by the app, outside the VPN | `RoutingEndToEndTest.namesAreAnsweredOnThePhoneAndResolvedByTheProxy`, `aNameTheProxyCannotResolveFailsWithoutAFallback`, `theProxysOwnNameIsLookedUpByTheApp`; the M6 evidence: no lookup on the uplink while connected but Android's own checks, and the uplink resolver asked for no lab destination name all run |
| TLS | To the proxy: valid, wrong name, expired, untrusted CA, not TLS, changed while connected; HTTPS to a site end to end | `TlsEndToEndTest` (9); the M6 evidence: every connection to a TLS front opens with a TLS record, the app's alerts, the HTTPS destination's own sessions |
| IPv4 and IPv6 | TCP by IPv4 address, by IPv6 address, to an IPv6-only name, to a LAN address — all through the proxy | `RoutingEndToEndTest.ipv4AddressesGoThroughTheProxyByAddress`, `ipv6AddressesGoThroughTheProxy`, `ipv6OnlyNamesAreReachedThroughTheProxy`, `lanAddressesGoThroughTheProxyToo`; the destinations' logs against the proxies' tunnels |
| Wi-Fi and mobile data | A move to another network while connected and while reconnecting, losing the network and getting it back, rapid changes; the monitor against Android's own view of the phone's network | `ReconnectEndToEndTest.aNetworkChangeEndsTheEnginesConnectionsAndTrafficGoesOn`, `withoutANetworkTrafficIsHeldUntilOneIsBack`, `theNetworkMonitorKnowsThePhonesNetwork`; the session's unit tests (`ReconnectTest`). The session is told of the change as Android tells it: switching the emulator's own uplink mid-run is what the device script avoids (M10-4). A real move between Wi-Fi and mobile data is on the phone checklist |
| Captive portals | A network with a sign-in page, connected and not; the proxy failing behind it; the page going while reconnecting, while blocked (a failure that may pass, a certificate the page may have answered with, a refused sign-in, a deleted profile), during a first connect and an Always-on start's first check; a page that comes and goes; what Home, Help and the log say (defects M10-1, M10-7 to M10-15) | `ReconnectEndToEndTest.signingInToTheNetworkReconnectsAtOnce`, `theNetworkMonitorKnowsThePhonesNetwork` (Android's report of the phone's network); `NetworkMonitorTest` (Android's own flag read, Robolectric), `NetworkReportsTest`, `ReconnectTest` (11), `HomeScreenTest` (2), `LogsScreenTest.aSignInPageOnTheNetworkIsSaidInTheLog`, screenshot B5 with the banner; a real hotel or airport network on the phone checklist |
| Proxy failure | Refusing, silent and hanging proxies, before and while connected; reconnecting on its own, bounded, cancellable, Retry now; the time to refuse | `RoutingEndToEndTest.whenTheProxyStopsAnsweringNothingGoesAroundIt`, `aProxyThatHangsIsReportedAsATimeout`, `ReconnectEndToEndTest` (outage, limits, cancel, Retry now), `PerformanceEndToEndTest.aDeadProxysConnectionsAreRefusedQuickly`; the sink (held lines never arrive) and the gate's log |

### 3.4 Permission revocation, process death, reboot and battery optimization (task 4)

| Area | What is tested | Tests and evidence (every API level) |
|---|---|---|
| VPN permission revoked | The user withdraws the permission while connected: everything closes, the alert says Android disconnected it, connecting again asks again | `VpnEndToEndTest.revocationClosesEverythingAndSaysSo`, `VpnConsentTest` (Android's own dialog, allowed and refused) |
| Notification permission denied (Android 13+) | Connecting and carrying traffic without it; none of ProxyFlow's notifications shown | `NotificationPermissionEndToEndTest`, run alone after the permission is revoked (`scripts/m10_scenarios.sh notifications`, N; API 33–36), while the phone's captures run: the checks over whole captures cover it (no packet to or from a lab address, no lookup of a lab name), not M6's account of each connection while connected, which reads the device tests' logcat and their ProxyFlow's uid (N's is a new install: M10-60); a skipped test fails it |
| Process death | The process killed while connected, without and with Always-on VPN and "Block connections without VPN"; a crash with a planted sign-in | M8's failure injection (`scripts/m8_failure_injection.sh`, A–C) and its evidence: the witness reached only without Android's kill switch, the next start finding the interruption, the crash report without the secret |
| Restart | The phone restarts with ProxyFlow as Always-on VPN with blocking: Android starts it, it connects the chosen profile by itself, nothing gets out meanwhile | `scripts/m10_scenarios.sh restart`, R: waits for a new boot (its boot id); lockdown read back; ProxyFlow's Always-on start logged; the witness probed every second — a probe made while Android has no VPN network must be refused as its blocking refuses, none answered — until a line goes through the proxy by name. On API 33 to 35 no packet capture runs through the restart (the phone's tcpdump does not survive it): the witness is the check there |
| Battery optimization | Deep Doze (on battery, the screen off), App Standby's most restricted bucket, Battery Saver, each while connected | `scripts/m10_scenarios.sh restart`, P, after R: on the connection Android started for Always-on VPN, with no instrumentation (whose rank would keep any process on the network), once Android's allowance for a VPN it has just started is over: the state read back, Android's VPN still there and a line through the proxy in each, the same process throughout. Android binds a VPN service with `BIND_FOREGROUND_SERVICE`, which may by itself let it past all three, so what ties P to ProxyFlow's own foreground service is the rank check: its process ranked as a started foreground service (M10-59); idle CPU with the screen off in `PerformanceEndToEndTest.idleWhileConnected`; the phone maker's own battery management on the phone checklist |

### 3.5 Packet captures and controlled endpoints: the supported-TCP leak matrix (task 5)

The leak matrix of M1's traffic-support matrix (`docs/m1/traffic-support-matrix.md`), checked on every level by M5's, M6's and M8's evidence from the lab's logs and the uplink's capture, in the verified run's first attempt:

| Evidence (whole run, each level) | 29 | 30 | 31 | 32 | 33 | 34 | 35 | 36 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Every connection that reached the destination came through a proxy (count) | 94 | 94 | 94 | 94 | 95 | 95 | 95 | 95 |
| Every sent line arrived once where it was sent; no refused line arrived | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Held lines that got out (the sink's connections) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| The bulk server's connections, all through a proxy's tunnel (count) | 431 | 431 | 431 | 431 | 431 | 431 | 431 | 431 |
| The uplink's capture while connected (the emulator's, or the phone's own on Wi-Fi): new connections from the phone other than to its proxy, its resolver or Android's checks | none | none | none | none | none | none | none | none |
| Lookups on the uplink while connected, other than Android's own | none | none | none | none | none | none | none | none |
| Lab destination names asked of the phone's resolver | none | none | none | none | none | none | none | none |
| UDP datagrams that reached a sink | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

The emulator's own capture records the phone's mobile data; from API 30 on it does not record Wi-Fi, so on API 33 to 35, whose uplink is Wi-Fi, tcpdump runs inside the phone on every uplink interface (through `adb root` on 34 and 35, through `su` on 33: M10-89, M10-113, M10-120, M10-129), and the phone's capture is what the evidence reads there (138 to 143 of ProxyFlow's connection attempts, all of them, matched in each level's capture). Controlled endpoints: the destinations `proxyflow.test` (two IPv4 addresses and an IPv6 one), the sink for held lines, the UDP sinks, the witness that only a packet around the proxy reaches (refused or blocked in every probe but M8's deliberate checks of Android's own kill switch turned off), and the bulk server, whose every connection must be a proxy's tunnel.

### 3.6 CPU, memory, battery, throughput and latency (task 6)

Measured by `PerformanceEndToEndTest` on every level, the figures and the bars in `docs/m10/performance-baseline.md` (the tables: attempt 1 of the verified run). In short: connecting takes 101 to 759 ms at p90 (≤ 3000 within everywhere); a dead proxy's connections are refused in 1 to 5 ms while the session is connected (< 1000 within everywhere, timed from each connection's own start, M10-92, M10-99), a silent one after 10 s; throughput down through ProxyFlow is 160 to 601 Mbit/s on mobile data and 68 to 144 on the emulator's Wi-Fi path — on API 34 the same path straight to the proxy is as slow (62 to 66 Mbit/s), so the bar there measures the emulator; CPU at 50 Mbit/s is 8 to 40 % of one core (within) and 65 % on API 29's emulator (past, the slowest image on a shared runner); the engine holds 26 to 31 MiB with 200 idle connections and 54.9 to 71.0 MB with 256 KiB in flight on each (past the 50 MB bar on every level: M10-63, accepted for V1, the buffers being what carry throughput); idle and connected with the screen off, ProxyFlow's CPU is 0.06 to 0.31 % of one core over 5 minutes of the phone's clock. Battery is a phone's to measure (the checklist).

### 3.7 Representative devices and every declared Android version (task 7)

Every declared Android version — 10, 11, 12, 12L, 13, 14, 15, 16 — ran the whole device suite in each attempt of the verified run, on Google's emulator images (`docs/m10/compatibility-matrix.md` §1), with the version-specific behaviours listed in its §2 (the notification permission, the notification actions' unlock, foreground service types, `ApplicationExitInfo`, App Standby's restricted bucket, Doze and Battery Saver, Always-on VPN, the tile, edge-to-edge, predictive back). No physical phone ran: the owner's phone runs `docs/m10/physical-phone-checklist.md`, and the representative devices for M11's closed testing are named in the matrix's §4.

### 3.8 Accessibility and localization (task 8)

| Check | How | Result |
|---|---|---|
| Every control TalkBack reaches has a name it reads out and a 48 × 48 dp touch target | `Accessibility.kt`, in every screenshot test (M9), now including the M10 images: the sign-in banner (B5) and the six right-to-left screens | Pass on all 123 images in the verified run (the checks run with the screenshot comparison) |
| Text that grows | M9's twelve screens on a 320 dp phone at 200 % font, each with its main action on screen | Unchanged screens pass; the sign-in banner is drawn at 100 % |
| Contrast | The design tokens' colour pairs, `design/tools/check_contrast.py`, in CI | Pass (no colour changed in M10) |
| Status never by colour alone | Icons and words with every status, the sign-in banner included (an Info banner with its title) | Pass |
| Right to left | ProxyFlow declares `supportsRtl`, so a phone set to Arabic, Hebrew, Persian or Urdu mirrors its screens, English words and all: six screens (Home disconnected, connected, a failed connect; the profile list; the proxy form; Settings) are screenshot tests laid out right to left as Compose lays out a right-to-left configuration (`screenshots/rtl`), with the accessibility checks | The back arrow and the list chevron did not turn round: fixed (M10-2). English sentences were laid out right to left, their full stops, units and number ranges out of place: fixed, ProxyFlow's own text is laid out left to right, as its English is written, its lines kept to the layout's start edge (M10-64, as re-reviewed: M10-75, M10-79). What the user wrote takes its own direction: a name shown alone, the same name inside a sentence as a first-strong isolate, and what is typed, except addresses, ports and credentials, typed left to right as Android's password fields are (M10-83, M10-90, M10-91, M10-94); the Quick Settings tile's line is isolated too, since Android 12 and later lay it out in the phone's language's direction (M10-106). Everything else mirrors as Android does |
| Words in code | Every word a screen shows comes from a string resource: a scan of the screens' code for literal text found it only in the design system's previews, which ship to no user | Pass |
| Languages | English only until decision D8 (first translations: Nepali, Japanese); amounts of data and seconds are SI symbols in code (M10-5) | Accepted for V1 |
| Pseudo-locales | Off: turned on and removed again on a wrong diagnosis of a broken build, whose cause was four strings with an apostrophe without its backslash (M10-6); they would show in a debug build what the right-to-left screenshots and the 200 % fonts show | — |
| Strings Android's resource compiler takes | `scripts/check_strings.py`, first in the build and device jobs: an apostrophe or a quote without its backslash, a bad escape (M10-6) | Pass |

### 3.9 Defects: filed, prioritized, resolved, regression-tested (task 9)

Every defect is in `docs/m10/defect-register.md` with its severity, where it was found, the fix and the test that now guards it. 138 were filed in M10 (M10-1 to M10-138): no critical, 1 high (M10-89, fixed), 42 medium and 95 low. 128 are fixed (M10-112 as M10-121), 9 are kept as documented trade-offs (M10-5 and M10-63 accepted for V1 — SI symbols in code, the engine's memory with data in flight; M10-93, M10-102, M10-111, M10-125, M10-126 and M10-137 on the paced retry, the form, the tile and JUnit's counts of a stopped run; M10-97, an emulator that went away at a restart), and 1 is open with its diagnostics in place (M10-131, two moments in runs before the verified one in which ProxyFlow could not reach its proxy on API 33's emulator Wi-Fi, nothing getting around it; not seen in the verified run's three attempts). Among the 131, the emulator's and the runner's own faults (broken downloads, a full disk, dropped mobile data, a lost emulator, a phone asleep) are recorded with their runs and fixed in the CI scripts. Each fix has a regression test that fails without it (the reviews mutation-tested them), and every commit runs the whole suite again: the verified commit's three attempts are the regression run.

### 3.10 The security mitigations, re-checked for the release candidate

M8's mitigation checklist is re-checked before every release candidate (`docs/m8/mitigation-checklist.md`): an independent review went through it line by line against the candidate's code, and each line again against the verified run (`docs/m10/mitigation-recheck.md`). It found one gap left since M5 — the connection notification's Disconnect worked from a locked phone's shade, and the lock screen could show the profile's name (M10-17, medium) — now fixed and tested: the actions need the phone unlocked (Android's flag from Android 12, a not-exported activity before it) and the notification names no profile. No line of M8's mitigations had stopped holding; the checklist gained lines for what is new since (the sign-in network, the notification on a locked phone, the engine's names after R8, M10's log entries). No critical or high security defect is open.

## 4. Known limitations, and where the app says them

Each limitation of V1, where the documents describe it, and what the app says of it. `CopyClaimsTest` requires the key facts in the app's strings and refuses any claim of coverage of all traffic, anonymity or protection; the screenshot tests show each screen.

| Limitation | Documented | Said in the app |
|---|---|---|
| Not anonymous: the proxy provider sees the sites and anything not encrypted end to end | `docs/m6/routing-and-limitations.md` §4 | Onboarding A2 (`how_provider`), About → How ProxyFlow works (`about_privacy_provider`), the privacy policy |
| Without TLS to the proxy, the network between phone and proxy sees the sign-in, the site names and plain-HTTP pages | ADR-0005; routing §3–§4 | The proxy form's warning, D4 (`edit_plaintext_body`); Help, certificates |
| UDP is not carried (calls, some games, QUIC fall back to TCP or fail); ping and traceroute get no answer | ADR-0004; routing §1, §4 | A2 (`how_udp`, `how_never_around`), About (`about_carry_blocked`), Help (`help_carry_blocked`), a passed test (D6), Home's diagnostics (UDP packets blocked) |
| Local devices (printers, casting, the router) are sent to the proxy, so usually unreachable | routing §4 | A2 (`how_local`), About, Help |
| IPv6 works only if the proxy has it; names are answered with IPv4 placeholders | ADR-0003; routing §4 | About (`about_carry_ipv6`) |
| Not through the VPN: ProxyFlow's own connection to the proxy and the lookup of its name, some of Android's own traffic and privileged apps' (Google Play services), work profiles, Private space, other users, tethered devices | routing §4 | A2 (`how_outside`), About (`about_carry_outside`), Help (`help_carry_outside`), the privacy policy |
| Before connecting, after disconnecting, and whenever the VPN goes away (another VPN, Android stopping ProxyFlow), apps use the normal network unless Android's own kill switch is on | ADR-0013; routing §4; `docs/m8/failure-injection.md` | Home's notes (`home_not_connected_note`, B6d), the VPN explainer (`home_vpn_explainer_one_vpn`), the kill-switch guide (E2, `guide_intro`), Help (`help_kill_switch_system`), the interruption notice after a process death |
| After a restart ProxyFlow connects by itself only as Android's Always-on VPN | ADR-0013; ADR-0015 §6 | Home's interruption notice (`home_interrupted_restart_leak`), Help (`help_background_body`) |
| A network with a sign-in page may carry nothing until the user signs in, the proxy included, or answer in the proxy's place with a certificate of its own; Android's sign-in page may not load while ProxyFlow holds traffic, or with "Block connections without VPN" (new in M10) | ADR-0015 §3 | Home's banner beside a failure to reach the proxy (`home_sign_in_network_*`), Help (`help_errors_network_sign_in`), the log (`logs_event_sign_in_page`) |
| Some phones stop background apps more than Android does; Android may stop ProxyFlow to free memory (new wording in M10) | ADR-0013; ADR-0015 §4 | Help (`help_background_body`), Home's interruption notice |
| Private DNS strict and apps' own encrypted DNS go through the proxy, which then sees addresses, not names | routing §4 | About (`about_carry_tcp`) |
| Self-signed and user-installed certificates are not trusted for TLS to the proxy | ADR-0005 | Help, certificates (`help_certificates_trusted`), Home's certificate errors |
| A proxy that takes more than 10 s to open a connection fails it as a timeout | routing §4; ADR-0012 | The timeout's title and message (`proxy_error_timeout_*`) |
| English only (V1, decision D8) | ADR-0015 §7 | — (the store listing's language, M11) |

## 5. The release candidate

The verified commit's release build, version **0.10.0 (10)**, application ID `app.proxyflow.android`, minSdk 29, targetSdk 36:

| Artifact | Where | Notes |
|---|---|---|
| App Bundle (`app-release.aab`), what Google Play takes | CI artifact `android-reports` of the verified run, `app/build/outputs/bundle/release/` | New in M10 (ADR-0015 §8): from the same R8-minified release build; 15,890,271 bytes (15.2 MiB) |
| Release APK (`app-release-unsigned.apk`) | the same artifact, `app/build/outputs/apk/release/` | 33,344,902 bytes (31.8 MiB; the three engine libraries, 9.5 to 10.4 MB each, are most of it, and the bundle serves one per phone); checked by `scripts/check_release_manifest.py` (permissions, no backups, not debuggable, no cleartext, the system trust store only, exported components, the tile's and the VPN's binding permissions, and — new in M10 — every name the engine's JNI layer finds still in its dex files after R8) and OSV against the libraries it ships |
| The release APK signed for testing (`app-release-test-signed.apk`) | the same artifact, `app/build/outputs/test-signed/` | The same release APK, signed with a key made for that CI run alone, so the owner's phone checks install what ships (`docs/m10/physical-phone-checklist.md`); not for distribution |

The bundle and the release APK are unsigned: signing — the upload key and Play App Signing — is M11's, with the store listing (decisions D8 and D9). The candidate is what M11 signs, unchanged, unless M11's own work or the owner's phone checks change the code.

## 6. Open for the owner

| # | For the owner | Why it matters | Blocks |
|---|---|---|---|
| 1 | Run `docs/m10/physical-phone-checklist.md` on your phone with your proxy, and send the filled-in results (§5) | M1's condition 1 (E01, E07–E10 on a physical phone with packet capture) is the GO recommendation's last open condition; the battery bar and a mid-range phone's throughput can only be measured there; M1's condition 4 (switch to the hev engine if a phone misses the bars) is decided on these figures | M11's production release (not closed testing) |
| 2 | D2: plaintext proxies in the Play build | Google Play's policy on apps that send credentials (ADR-0005) | M11's store listing |
| 3 | D6: the pre-flight and Test connection target | What the privacy policy names as contacted on every connect and test | — (one constant) |
| 4 | D7: TLS on or off by default in the form | — | — |
| 5 | D8: the app's licence and the first translations (Nepali, Japanese) | The store listing's licence and languages; translations move the units to resources (M10-5) | M11's store listing |
| 6 | D9: the public privacy policy's address and the support contact | Google Play requires both (`docs/m9/privacy-policy.md` is ready) | M11's store listing |
