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
| Captive portals | A network with a sign-in page, connected and not; the proxy failing behind it; signing in (defect M10-1) | `ReconnectEndToEndTest.signingInToTheNetworkReconnectsAtOnce`, `theNetworkMonitorKnowsThePhonesNetwork` (the sign-in flag as Android says); `NetworkReportsTest`, `ReconnectTest` (4), `HomeScreenTest.aSignInPageOnTheNetworkIsSaidBesideTheFailure`; a real hotel or airport network on the phone checklist |
| Proxy failure | Refusing, silent and hanging proxies, before and while connected; reconnecting on its own, bounded, cancellable, Retry now; the time to refuse | `RoutingEndToEndTest.whenTheProxyStopsAnsweringNothingGoesAroundIt`, `aProxyThatHangsIsReportedAsATimeout`, `ReconnectEndToEndTest` (outage, limits, cancel, Retry now), `PerformanceEndToEndTest.aDeadProxysConnectionsAreRefusedQuickly`; the sink (held lines never arrive) and the gate's log |

### 3.4 Permission revocation, process death, reboot and battery optimization (task 4)

| Area | What is tested | Tests and evidence (every API level) |
|---|---|---|
| VPN permission revoked | The user withdraws the permission while connected: everything closes, the alert says Android disconnected it, connecting again asks again | `VpnEndToEndTest.revocationClosesEverythingAndSaysSo`, `VpnConsentTest` (Android's own dialog, allowed and refused) |
| Notification permission denied (Android 13+) | Connecting and carrying traffic without it | `NotificationPermissionEndToEndTest`, run alone after the permission is revoked (`scripts/m10_scenarios.sh`, N; API 33–36) |
| Process death | The process killed while connected, without and with Always-on VPN and "Block connections without VPN"; a crash with a planted sign-in | M8's failure injection (`scripts/m8_failure_injection.sh`, A–C) and its evidence: the witness reached only without Android's kill switch, the next start finding the interruption, the crash report without the secret |
| Restart | The phone restarts with ProxyFlow as Always-on VPN with blocking: Android starts it, it connects the chosen profile by itself, nothing gets out meanwhile | `scripts/m10_scenarios.sh`, R: the witness probed every second until a line goes through the proxy |
| Battery optimization | Deep Doze, App Standby's most restricted bucket, Battery Saver, each while connected | `PowerSavingEndToEndTest` (3): connected, the VPN service running, a line through the proxy; idle CPU with the screen off in `PerformanceEndToEndTest.idleWhileConnected`; the phone maker's own battery management on the phone checklist |

### 3.5 Packet captures and controlled endpoints: the supported-TCP leak matrix (task 5)

{TASK5}

### 3.6 CPU, memory, battery, throughput and latency (task 6)

{TASK6}

### 3.7 Representative devices and every declared Android version (task 7)

{TASK7}

### 3.8 Accessibility and localization (task 8)

{TASK8}

### 3.9 Defects: filed, prioritized, resolved, regression-tested (task 9)

{TASK9}

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
| A network with a sign-in page carries nothing until the user signs in, the proxy included; Android's sign-in page may not load while ProxyFlow holds traffic, or with "Block connections without VPN" (new in M10) | ADR-0015 §3 | Home's banner in each state (`home_sign_in_network_*`), Help (`help_errors_network_sign_in`) |
| Some phones stop background apps more than Android does; Android may stop ProxyFlow to free memory (new wording in M10) | ADR-0013; ADR-0015 §4 | Help (`help_background_body`), Home's interruption notice |
| Private DNS strict and apps' own encrypted DNS go through the proxy, which then sees addresses, not names | routing §4 | About (`about_carry_tcp`) |
| Self-signed and user-installed certificates are not trusted for TLS to the proxy | ADR-0005 | Help, certificates (`help_certificates_trusted`), Home's certificate errors |
| A proxy that takes more than 10 s to open a connection fails it as a timeout | routing §4; ADR-0012 | The timeout's title and message (`proxy_error_timeout_*`) |
| English only (V1, decision D8) | ADR-0015 §7 | — (the store listing's language, M11) |

## 5. The release candidate

{RC}

## 6. Open for the owner

{OPEN}
