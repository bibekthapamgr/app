# M4 — HTTP CONNECT, SOCKS5 and the engine: acceptance report

**Status: DRAFT — evidence runs in progress.**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m4-protocol-engine`, pull request #3 |
| Verified commit | Pending |
| CI runs of that commit | Pending |
| Build environment | ubuntu-24.04 runners · Go 1.27.3 (`actions/setup-go` v7.0.0) · gomobile `golang.org/x/mobile` `v0.0.0-20260908204917-8b95e45f8d3e` · Android NDK r28c (`28.2.13676358`) · Temurin JDK 21 · Gradle 9.8.2 · Android Gradle Plugin 9.5.0 with built-in Kotlin 2.4.21 · compileSdk/targetSdk 36 · minSdk 29 |
| Test environment | Go tests with the race detector on the runner (no root, no network: socketpairs, an in-process client network stack, mock proxies and certificates made by the tests); Android emulators API 29 (Android 10, the app's minimum) and API 35 (Android 15), `google_apis` x86_64, running the real `libgojni.so` from the AAR |

The workspace this was written in has Go and its module mirror but cannot reach Google's Maven repository, the NDK or Actions logs, so the Go engine was built and tested here and in CI, and everything Android (the AAR, Gradle, emulators) runs in GitHub Actions. CI reports test results, the AAR's facts and every failure as annotations readable through the GitHub API.

The owner delegated the networking decisions ("Timi nai decide gara"). ADR-0001 to ADR-0004 were accepted on that basis before the engine work, and ADR-0005's technical decisions too; **D2 (plaintext proxies in the Play build) and D7 (TLS default in the form) stay open for the owner**: they are product and Play-policy choices, not engineering ones.

## Acceptance criteria

| Criterion (brief) | Evidence | Result |
|---|---|---|
| HTTP CONNECT success, rejection, authentication failure and timeout tests pass | **Success:** `TestDialThroughProxiesToEcho` sends bytes through a mock HTTP CONNECT proxy to an echo server and back for hostname, IPv4, IPv6 and IPv4-mapped destinations, with and without sign-in, and checks what the proxy received (request line, decoded Basic credentials, none when sign-in is off); `TestBufferedTunnelBytesAfterConnectReply` (bytes sent with the 200 reach the app); on the emulators `socks5AndHttpConnectCarryTcpToTheDestination` and the full engine in `theWholePacketEngineCarriesTrafficThroughTheProxy`. **Rejection:** `TestTargetLevelFailures` (403 → `TARGET_NOT_ALLOWED`, 502/504 → `TARGET_UNREACHABLE`, 500 → `PROXY_GENERAL_FAILURE`, 405 → `PROTOCOL_ERROR`, unknown destination), `TestHTTPConnectStatusMapping` (11 responses incl. non-HTTP and malformed status lines), `TestHTTPConnectOversizedHeaders` (16 KiB / 100 lines), `TestHTTPConnectRejectsInjectedHostname`, fuzz targets for the response parser and the request builder. **Authentication failure:** `TestAuthenticationOutcomes` (407 without credentials → `AUTH_REQUIRED`; wrong password or username → `AUTH_FAILED`; Digest-only proxy → `AUTH_FAILED` naming Digest), on the emulators `signInFailuresAreReportedAsProxyLevelCodes`. **Timeout:** `TestHandshakeTimeout` (a proxy that accepts and never answers → `HANDSHAKE_TIMEOUT` after 300 ms, and its connection is closed), `TestContextDeadlineDuringHandshake`; cancellation in `TestCancellationStopsDialAtOnce` | Pending |
| SOCKS5 negotiation, authentication, domain, IPv4 and IPv6 destination tests pass where supported | **Negotiation and encoding, byte for byte:** `TestSOCKS5NoAuthDomainTarget` (offers only "no authentication" without credentials; ATYP 3 with the hostname), `TestSOCKS5UserPassSuccessIPv6Reply` (RFC 1929 sub-negotiation, IPv6 bound address in the reply), `TestSOCKS5RequestEncoding` (IPv4-mapped → ATYP 1, IPv6 → ATYP 4, injected or over-long names refused), `TestSOCKS5ReplyCodes` (every reply code, reported even when the proxy closes early), `TestSOCKS5NotASocksServer`, `TestSOCKS5TruncatedReply`, fuzz target for the reply parser. **Authentication:** `TestSOCKS5AuthFailures` and `TestAuthenticationOutcomes` (required, wrong password, wrong username, correct; a method that was not offered is refused without sending credentials). **Destinations through a proxy to a real echo server:** `TestDialThroughProxiesToEcho` — hostname (the proxy records ATYP `domain`), IPv4, IPv6 (an IPv6 documentation address the mock proxy maps to the echo server, so the IPv6 path runs on any runner; plus `[::1]` where the runner has IPv6 loopback) and IPv4-mapped; `TestDomainTargetIsResolvedByTheProxy` (a name only the proxy knows works, so it was not resolved locally); through the netstack `TestEngineThroughMockProxies` and `TestIPv6TCPIsForwardedByAddress`; on the emulators the hostname and an IPv6 destination reach the Kotlin mock proxy as ATYP `domain` and `ipv6` | Pending |
| Invalid TLS certificates and hostnames are rejected | `TestTLSToProxyValidation` (12 cases against a TLS mock proxy): valid chain, chain through an intermediate, case-insensitive name and wildcard pass; wrong name, a wildcard used for two labels, expired, not yet valid, untrusted issuer, self-signed, intermediate not sent and a certificate for client authentication only are rejected with `TLS_HOSTNAME`, `TLS_EXPIRED` or `TLS_UNTRUSTED`, and nothing is sent to the proxy after a failed check. `TestTLSToProxyByIPAddress` (an IP must be in the certificate), `TestTLSResumptionIsVerifiedAgain` (a resumed TLS 1.3 session goes through the verifier again and is refused when the verifier says no), `TestTLSMismatchedTransport` (TLS to a plaintext port → `TLS_HANDSHAKE`; no fallback), `TestTLSWithoutVerifierIsRefused`. On the emulators, with Android's own trust manager: `tlsToTheProxyIsVerifiedByThePlatformTrustManager` — a certificate from a CA the system does not trust → `TLS_UNTRUSTED`; with a test CA trusted the way a public CA is: valid → passes, wrong name → `TLS_HOSTNAME`, expired → `TLS_EXPIRED`, a certificate for another name → `TLS_HOSTNAME`, a plaintext proxy → `TLS_HANDSHAKE` | Pending |
| The engine stops cleanly and does not leave sockets or worker threads behind | Engine, adapter and `pfengine` tests start with a leak check that fails the test if any goroutine or file descriptor opened during it is still alive afterwards (`proxytest.CheckLeaks`; `TestLeakCheckDetectsLeaks` proves it catches both). Under it: `TestStopEndsOpenFlowsAndCancelsSetups` (Stop with 20 open flows and 3 tunnels being set up returns in under a second; every app connection ends; set-ups end as `CANCELED`, not as failures; no flow left), `TestLifecycle` (states, a second Start refused, three concurrent Stops), `TestStartFailureLeavesNothingRunning`, `TestPacketLoopFailureIsReported` (TUN failure → `failed` + `ENGINE_STOPPED`, then Stop cleans up), `TestResetFlowsKeepsEngineRunning`, `TestSlowEventReceiverNeverStallsTraffic`, `TestCancellationStopsDialAtOnce`, `TestHandshakeTimeout`, `TestEngineThroughMockProxies`, `TestStartStopLifecycle` (the TUN descriptor stays open for its owner), `TestSelfTestThroughMockProxy`. On the emulators: `theEngineStartsAndStopsOnADescriptorItDoesNotOwn` and the self-test's check, after everything was released, that no goroutine running engine or netstack code is left (`engine_goroutines_left = 0`) | Pending |
| No credentials appear in logs or crash reports | The engine never logs. Credentials are bytes that print as `<redacted>` under every format verb and encoder (`TestCredentialsNeverFormatted`), copied only into the buffers written to the proxy and zeroed after (`TestWipeZeroesSecrets`); error details are fixed phrases (`TestHTTPConnectSchemesNeverEchoProxyText`). `TestErrorsNeverContainCredentials` drives 17 failure paths with distinctive credentials and searches every error text and cause for them raw, in Base64 and in hex; `TestNoSecretsAnywhere` does the same for everything `pfengine` returns or emits (probe and self-test results, Start errors, status, stats, flows, events). On the emulators `credentialsNeverAppearInOutputsEventsOrLogcat` runs probes, self-tests and engine starts with distinctive credentials, then searches the results, the events and **the whole logcat** for them. Crash reports: the app has no crash-reporting SDK; a panic in a flow is recovered and reported as `INTERNAL` without its value; Go's crash traces print argument words (pointers), never the bytes they point to | Pending |
| Dependency and licence review is complete | `docs/m4/dependency-review.md`: every module linked into the library with version, licence and purpose (Go 1.27.3, gVisor, `x/net`, `x/sys`, `x/time`, `x/exp`, `btree`; gomobile's bind runtime), build tools, licence obligations for the in-app notices, update policy. CI rebuilds `go.sum` from `sum.golang.org` and compares it with the committed file, runs `govulncheck` over everything the library links (gomobile's bind runtime included), builds the AAR only from those verified, tested modules and prints the modules linked into it | Pending |

## The brief's tasks

| Task | Where |
|---|---|
| 1. Choose the audited TUN/TCP engine approved in M1; pin version and document licence | gVisor netstack (ADR-0001, accepted), pinned in `engine/go.mod`; licences and pins in `docs/m4/dependency-review.md` |
| 2. Engine interface: start, stop, state, active flows, errors, metrics | `tunnel.Engine` (`Start`, `Stop`, `ResetFlows`, `State`, `Flows`, `LastError`, `Status`, `Stats`, events) → `pfengine` (gomobile) → `ProxyEngine` (Kotlin); `docs/m4/protocol-compatibility.md` §2 |
| 3. HTTP CONNECT handshake and response validation | `engine/adapters/httpconnect.go` |
| 4. SOCKS5 negotiation, optional username/password, CONNECT, IPv4/IPv6/domain | `engine/adapters/socks5.go` |
| 5. Timeout, cancellation, bounded buffers, flow limits, back-pressure | `adapters/dialer.go` (connect and handshake timeouts, cancellation through a past deadline), `tunnel` (flow, in-flight and DNS limits, pooled relay buffers with blocking writes, bounded event queue); table in `docs/m4/protocol-compatibility.md` §7 |
| 6. TLS-to-proxy for compatible endpoints; chain and hostname validated | `adapters/tls.go`, `core/engine/.../AndroidEnginePlatform.kt` (ADR-0005) |
| 7. Mock proxy fixtures for deterministic tests | `engine/internal/proxytest` (Go: SOCKS5, HTTP CONNECT, TLS, scripted misbehaviour, echo server, PKI, leak checks); `core/engine/src/androidTest` (Kotlin mock proxies, written separately from the Go client, and an on-device PKI) |
| 8. Sanitised errors; no credentials or proxy authorization headers in logs | stable codes with fixed-phrase details (`adapters/errors.go`); `Credentials` type; tests in the criteria table |
| 9. Engine lifecycle and cleanup after failure | `tunnel/engine.go`; tests in the criteria table |

## Deliverables

| Deliverable | Where |
|---|---|
| Protocol engine module | `engine/` (Go: `adapters`, `tunnel`, `fakedns`, `pfengine`, `internal/proxytest`, `build-aar.sh`) and `core/engine/` (Android library: `ProxyEngine`, `EngineSecret`, `AndroidEnginePlatform`, models) |
| Tests | Pending (counts from CI) |
| Dependency audit | `docs/m4/dependency-review.md` |
| Protocol compatibility document | `docs/m4/protocol-compatibility.md` |
| Decisions | ADR-0001 to ADR-0004 accepted; ADR-0005 technical decisions accepted, D2 and D7 open (`docs/adr/README.md`) |

## Tests

| Where | Tests | What they cover |
|---|---:|---|
| `engine/adapters` (Go) | 45 | SOCKS5 and HTTP CONNECT byte-level encoding and parsing, every reply and status mapping, sign-in outcomes, destinations through mock proxies to an echo server, TLS verification (12 certificate cases, IP SAN, resumption, wrong transport), timeouts, cancellation, protect and proxy-host resolution, configuration and credential limits, redaction; 4 fuzz targets |
| `engine/tunnel` (Go) | 21 | the netstack with an in-process client stack: hostnames to the proxy, refusals, stale fake addresses, lifecycle, Start failure, Stop with open flows and pending set-ups, ResetFlows, limits, flows and the privacy switch, TUN failure, a stuck event receiver, panic isolation, DNS limit, real adapters through mock proxies, proxy outage, IPv6, pings never answered |
| `engine/pfengine` (Go) | 8 | the gomobile API: Probe (SOCKS5, HTTP, TLS with a platform verifier, bad input), Start/Stop, SelfTest (IPv4 name and IPv6 destinations), no secrets in any output |
| `engine/fakedns`, `engine/internal/proxytest` (Go) | 6 + 2 | fake DNS (and its fuzz target); the leak checker catches leaks, and the fixtures leave none |
| **Go total** | **82** | race detector on, 0 failed (CI) |
| `core/engine` (JVM) | 15 | the credential encoding the engine reads, zeroing, config JSON (exact keys, no credentials), JSON decoding, error codes, certificate-chain parsing and verdicts |
| Rest of the app (JVM, unchanged from M3) | 161 | as in the M3 report |
| `core/engine` on emulators | 8 × 2 | `EngineOnDeviceTest` on API 29 and API 35 |
| `core/data` on emulators (M3) | 4 × 2 | `KeystoreOnDeviceTest` |

## The engine library (AAR)

Built in CI from the commit under test and handed to the Android jobs; from the engine job's notices:

```
AAR build/pfengine.aar 11288112 bytes sha256 6ba08dd13abddce3663d684ad83360779646c9dbbd857496c751f3b6fa5b10a5
BUILT WITH go version go1.27.3 linux/amd64, gomobile v0.0.0-20260908204917-8b95e45f8d3e, NDK 28.2.13676358, -androidapi 29
SO armeabi-v7a 9130572 bytes, LOAD alignment 0x4000
SO arm64-v8a 9348008 bytes, LOAD alignment 0x4000
SO x86_64 9992584 bytes, LOAD alignment 0x4000
CLASSES 15 in classes.jar (Pfengine.class Platform.class )
MODULES linked into libgojni.so (go version -m):
  github.com/google/btree v1.1.2
  golang.org/x/exp v0.0.0-20250711185948-6ae5c78190dc
  golang.org/x/mobile v0.0.0-20260908204917-8b95e45f8d3e
  golang.org/x/net v0.60.0
  golang.org/x/sys v0.48.0
  golang.org/x/time v0.15.0
  gvisor.dev/gvisor v0.0.0-20261029062900-94fcb330700e
```

Two runs built from different commits with the same engine sources produced the same SHA-256: the build is reproducible (`-trimpath`, empty build id). Every 64-bit library is aligned to 16 KB, as Google Play requires for apps targeting Android 15 and later. The app does not include the library before M5, so its APKs and its 129 release libraries are unchanged by M4.
