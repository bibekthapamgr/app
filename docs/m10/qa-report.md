# M10 — QA report

**Milestone:** M10 · **Verified commit:** `{SHA}` · **CI:** run {RUN}, attempts 1–3 · **Decisions:** ADR-0015 · **Defects:** `docs/m10/defect-register.md` · **Performance:** `docs/m10/performance-baseline.md` · **Devices:** `docs/m10/compatibility-matrix.md` · **Phone checks:** `docs/m10/physical-phone-checklist.md`

M10 proves reliability and routing correctness before release. This report says what was tested, how, where and with what result, task by task of the brief; which defects were found and what was done; what the app does not carry and where it says so; and what is left for a physical phone.

## 1. Summary

{SUMMARY}

## 2. What ran

{RAN}

## 3. The brief's tasks

### 3.1 Unit, integration, instrumentation and end-to-end tests (task 1)

{TASK1}

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
| Restart | The phone restarts with ProxyFlow as Always-on VPN with blocking: Android starts it, it connects the chosen profile by itself, nothing gets out meanwhile | `scripts/m10_scenarios.sh restart`, R: waits for a new boot (its boot id); lockdown read back; ProxyFlow's Always-on start logged; the witness probed every second — a probe made while Android has no VPN network must be refused as its blocking refuses, none answered — until a line goes through the proxy by name. On API 34 and 35 no packet capture runs through the restart (the phone's tcpdump would need root): the witness is the check there |
| Battery optimization | Deep Doze (on battery, the screen off), App Standby's most restricted bucket, Battery Saver, each while connected | `scripts/m10_scenarios.sh restart`, P, after R: on the connection Android started for Always-on VPN, with no instrumentation (whose rank would keep any process on the network), once Android's allowance for a VPN it has just started is over: the state read back, Android's VPN still there and a line through the proxy in each, the same process throughout. Android binds a VPN service with `BIND_FOREGROUND_SERVICE`, which may by itself let it past all three, so what ties P to ProxyFlow's own foreground service is the rank check: its process ranked as a started foreground service (M10-59); idle CPU with the screen off in `PerformanceEndToEndTest.idleWhileConnected`; the phone maker's own battery management on the phone checklist |

### 3.5 Packet captures and controlled endpoints: the supported-TCP leak matrix (task 5)

{TASK5}

### 3.6 CPU, memory, battery, throughput and latency (task 6)

{TASK6}

### 3.7 Representative devices and every declared Android version (task 7)

{TASK7}

### 3.8 Accessibility and localization (task 8)

| Check | How | Result |
|---|---|---|
| Every control TalkBack reaches has a name it reads out and a 48 × 48 dp touch target | `Accessibility.kt`, in every screenshot test (M9), now including the M10 images: the sign-in banner (B5) and the six right-to-left screens | {A11Y} |
| Text that grows | M9's twelve screens on a 320 dp phone at 200 % font, each with its main action on screen | Unchanged screens pass; the sign-in banner is drawn at 100 % |
| Contrast | The design tokens' colour pairs, `design/tools/check_contrast.py`, in CI | Pass (no colour changed in M10) |
| Status never by colour alone | Icons and words with every status, the sign-in banner included (an Info banner with its title) | Pass |
| Right to left | ProxyFlow declares `supportsRtl`, so a phone set to Arabic, Hebrew, Persian or Urdu mirrors its screens, English words and all: six screens (Home disconnected, connected, a failed connect; the profile list; the proxy form; Settings) are screenshot tests laid out right to left as Compose lays out a right-to-left configuration (`screenshots/rtl`), with the accessibility checks | The back arrow and the list chevron did not turn round: fixed (M10-2). English sentences were laid out right to left, their full stops, units and number ranges out of place: fixed, ProxyFlow's own text is laid out left to right, as its English is written, its lines kept to the layout's start edge (M10-64, as re-reviewed: M10-75, M10-79). What the user wrote takes its own direction: a name shown alone, the same name inside a sentence as a first-strong isolate, and what is typed, except addresses, ports and credentials, typed left to right as Android's password fields are (M10-83, M10-90, M10-91, M10-94); the Quick Settings tile's line is isolated too, since Android 12 and later lay it out in the phone's language's direction (M10-106). Everything else mirrors as Android does |
| Words in code | Every word a screen shows comes from a string resource: a scan of the screens' code for literal text found it only in the design system's previews, which ship to no user | Pass |
| Languages | English only until decision D8 (first translations: Nepali, Japanese); amounts of data and seconds are SI symbols in code (M10-5) | Accepted for V1 |
| Pseudo-locales | Off: turned on and removed again on a wrong diagnosis of a broken build, whose cause was four strings with an apostrophe without its backslash (M10-6); they would show in a debug build what the right-to-left screenshots and the 200 % fonts show | — |
| Strings Android's resource compiler takes | `scripts/check_strings.py`, first in the build and device jobs: an apostrophe or a quote without its backslash, a bad escape (M10-6) | Pass |

### 3.9 Defects: filed, prioritized, resolved, regression-tested (task 9)

Every defect is in `docs/m10/defect-register.md` with its severity, where it was found, the fix and the test that now guards it. {DEFECTS_SUMMARY} Each fix has a regression test that fails without it (the reviews mutation-tested them), and every commit runs the whole suite again: the verified commit's three attempts are the regression run.

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
| App Bundle (`app-release.aab`), what Google Play takes | CI artifact `android-reports` of the verified run, `app/build/outputs/bundle/release/` | New in M10 (ADR-0015 §8): from the same R8-minified release build; {AAB_SIZE} |
| Release APK (`app-release-unsigned.apk`) | the same artifact, `app/build/outputs/apk/release/` | {APK_SIZE}; checked by `scripts/check_release_manifest.py` (permissions, no backups, not debuggable, no cleartext, the system trust store only, exported components, the tile's and the VPN's binding permissions, and — new in M10 — every name the engine's JNI layer finds still in its dex files after R8) and OSV against the libraries it ships |
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
