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
| SOCKS5 replies: every error code, a truncated reply | Each told apart; the site's failure not taken for the proxy's | `RoutingEndToEndTest` (unreachable targets) | `TestSOCKS5ReplyCodes`, `TestTargetLevelFailures`, `TestSOCKS5TruncatedReply` |

### 3.3 DNS, TLS, IPv4/IPv6, Wi-Fi and mobile, captive portals, proxy failure (task 3)

{TASK3}

### 3.4 Permission revocation, process death, reboot and battery optimization (task 4)

{TASK4}

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

{LIMITS}

## 5. The release candidate

{RC}

## 6. Open for the owner

{OPEN}
