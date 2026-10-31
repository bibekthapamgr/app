# M4 — HTTP CONNECT, SOCKS5 and the engine: acceptance report

**Status: every M4 acceptance criterion passes in CI, in three runs of the same commit, after three independent reviews whose findings were all fixed. Awaiting the product owner's review of pull request #3; ADR-0001 to ADR-0005 were accepted under the owner's delegation and can be reopened, and decisions D2 and D7 stay with the owner.**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m4-protocol-engine`, pull request #3 |
| Verified commit | [`051c9dd`](https://github.com/bibekthapamgr/app/commit/051c9dda02f95d20fcc196f6a2baf910aee34c99) — engine, Android module, tests, CI and documents. Later commits on the branch change only this report |
| CI runs of that commit | `android` [run 41183070387](https://github.com/bibekthapamgr/app/actions/runs/41183070387), attempts [1](https://github.com/bibekthapamgr/app/actions/runs/41183070387/attempts/1), [2](https://github.com/bibekthapamgr/app/actions/runs/41183070387/attempts/2) and [3](https://github.com/bibekthapamgr/app/actions/runs/41183070387/attempts/3), all green in every job: `engine` (module checks, 103 Go tests with the race detector, fuzzing, govulncheck, the AAR), `build` (182 JVM tests, ktlint, Lint including `:core:engine`, builds, release manifest, every screenshot), `device` on API 29 and API 35 (12 tests each), `screenshots` · `secrets` runs [41183066380](https://github.com/bibekthapamgr/app/actions/runs/41183066380) (push) and [41183070308](https://github.com/bibekthapamgr/app/actions/runs/41183070308) (pull request), green |
| Build environment | ubuntu-24.04 runners · Go 1.27.3 (`actions/setup-go` v7.0.0) · gomobile `golang.org/x/mobile` `v0.0.0-20260908204917-8b95e45f8d3e` · Android NDK r28c (`28.2.13676358`) · Temurin JDK 21 · Gradle 9.8.2 · Android Gradle Plugin 9.5.0 with built-in Kotlin 2.4.21 · compileSdk/targetSdk 36 · minSdk 29 |
| Test environment | Go tests with the race detector on the runner (no root, no network: socketpairs, an in-process client network stack, mock proxies and certificates made by the tests); Android emulators API 29 (Android 10, the app's minimum) and API 35 (Android 15), `google_apis` x86_64, running the real `libgojni.so` from the AAR built in the same run |

The workspace this was written in has Go and its module mirror but cannot reach Google's Maven repository, the NDK or Actions logs, so the Go engine was built and tested here as well as in CI, and everything Android (the AAR, Gradle, emulators) ran in GitHub Actions. The Kotlin engine module and its tests were also compiled and run locally against stubs, and ktlint and the secrets scan ran before every push. CI reports every test result, the AAR's facts and every failure as annotations readable through the GitHub API.

The owner delegated the networking decisions ("Timi nai decide gara"). ADR-0001 to ADR-0004 were accepted on that basis before the engine work, and ADR-0005's technical decisions too; **D2 (plaintext proxies in the Play build) and D7 (TLS default in the form) stay open for the owner**: they are product and Play-policy choices, not engineering ones.

What the engine does **not** carry is unchanged from M1 and stated in `docs/m4/protocol-compatibility.md` §8 and the traffic support matrix: UDP other than DNS is refused, DNS is answered on the phone (fake-IP), ICMP is never answered, and only TCP — over IPv4 and IPv6 — goes through the proxy. M4 proves this on the engine; on a device with a VPN and packet captures it is M5 and M6 work.

## Acceptance criteria

| Criterion (brief) | Evidence | Result |
|---|---|---|
| HTTP CONNECT success, rejection, authentication failure and timeout tests pass | **Success:** `TestDialThroughProxiesToEcho` sends bytes through a mock HTTP CONNECT proxy to an echo server and back for hostname, IPv4, IPv6 and IPv4-mapped destinations, with and without sign-in, and checks what the proxy received (request line, decoded Basic credentials, none when sign-in is off); `TestBufferedTunnelBytesAfterConnectReply` (bytes sent with the 200 reach the app); on the emulators `socks5AndHttpConnectCarryTcpToTheDestination` and the full engine in `theWholePacketEngineCarriesTrafficThroughTheProxy`. **Rejection:** `TestTargetLevelFailures` (403 → `TARGET_NOT_ALLOWED`, 502/504 → `TARGET_UNREACHABLE`, 500 → `PROXY_GENERAL_FAILURE`, 405 → `PROTOCOL_ERROR`, unknown destination), `TestHTTPConnectStatusMapping` (14 responses incl. non-HTTP and malformed status lines, `101`, and interim `1xx` before the final answer), `TestHTTPConnectSkipsInterimResponses`, `TestHTTPConnectOversizedHeaders` (16 KiB / 100 lines), `TestHTTPConnectRejectsInjectedHostname`, fuzz targets for the response parser and the request builder. **Authentication failure:** `TestAuthenticationOutcomes` (407 without credentials → `AUTH_REQUIRED`; wrong password or username → `AUTH_FAILED`; Digest-only proxy → `AUTH_FAILED` naming Digest), on the emulators `signInFailuresAreReportedAsProxyLevelCodes`. **Timeout:** `TestHandshakeTimeout` (a proxy that accepts and never answers → `HANDSHAKE_TIMEOUT` after 300 ms, and its connection is closed), `TestContextDeadlineDuringHandshake`; cancellation in `TestCancellationStopsDialAtOnce` | **Pass** |
| SOCKS5 negotiation, authentication, domain, IPv4 and IPv6 destination tests pass where supported | **Negotiation and encoding, byte for byte:** `TestSOCKS5NoAuthDomainTarget` (offers only "no authentication" without credentials; ATYP 3 with the hostname), `TestSOCKS5UserPassSuccessIPv6Reply` (RFC 1929 sub-negotiation, IPv6 bound address in the reply), `TestSOCKS5RequestEncoding` (IPv4-mapped → ATYP 1, IPv6 → ATYP 4, injected or over-long names refused), `TestSOCKS5ReplyCodes` (every reply code, reported even when the proxy closes early), `TestSOCKS5NotASocksServer`, `TestSOCKS5TruncatedReply`, fuzz target for the reply parser. **Authentication:** `TestSOCKS5AuthFailures` and `TestAuthenticationOutcomes` (required, wrong password, wrong username, correct; a method that was not offered is refused without sending credentials). **Destinations through a proxy to a real echo server:** `TestDialThroughProxiesToEcho` — hostname (the proxy records ATYP `domain`), IPv4, IPv6 (an IPv6 documentation address the mock proxy maps to the echo server, so the IPv6 path runs on any runner; plus `[::1]` where the runner has IPv6 loopback) and IPv4-mapped; `TestDomainTargetIsResolvedByTheProxy` (a name only the proxy knows works, so it was not resolved locally); through the netstack `TestEngineThroughMockProxies` and `TestIPv6TCPIsForwardedByAddress`; on the emulators the hostname and an IPv6 destination reach the Kotlin mock proxy as ATYP `domain` and `ipv6` | **Pass** |
| Invalid TLS certificates and hostnames are rejected | `TestTLSToProxyValidation` (12 cases against a TLS mock proxy): valid chain, chain through an intermediate, case-insensitive name and wildcard pass; wrong name, a wildcard used for two labels, expired, not yet valid, untrusted issuer, self-signed, intermediate not sent and a certificate for client authentication only are rejected with `TLS_HOSTNAME`, `TLS_EXPIRED` or `TLS_UNTRUSTED`, and nothing is sent to the proxy after a failed check. `TestTLSToProxyByIPAddress` (an IP must be in the certificate), `TestTLSResumptionIsVerifiedAgain` (a resumed TLS 1.3 session goes through the verifier again and is refused when the verifier says no), `TestTLSMismatchedTransport` (TLS to a plaintext port → `TLS_HANDSHAKE`; no fallback), `TestTLSWithoutVerifierIsRefused`. On the emulators, with Android's own trust manager: `tlsToTheProxyIsVerifiedByThePlatformTrustManager` — a certificate from a CA the system does not trust → `TLS_UNTRUSTED`; with a test CA trusted the way a public CA is: valid → passes, wrong name → `TLS_HOSTNAME`, expired → `TLS_EXPIRED`, a certificate for another name → `TLS_HOSTNAME`, a plaintext proxy → `TLS_HANDSHAKE` | **Pass** |
| The engine stops cleanly and does not leave sockets or worker threads behind | Engine, adapter and `pfengine` tests start with a leak check that fails the test if any goroutine or file descriptor opened during it is still alive afterwards (`proxytest.CheckLeaks`; `TestLeakCheckDetectsLeaks` proves it catches both). Under it: `TestStopEndsOpenFlowsAndCancelsSetups` (Stop with 20 open flows and 3 tunnels being set up returns in under a second; every app connection ends; set-ups end as `CANCELED`, not as failures; no flow left), `TestLifecycle` (states, a second Start refused, three concurrent Stops), `TestStartFailureLeavesNothingRunning`, `TestPacketLoopFailureIsReported` (TUN failure → `failed` + `ENGINE_STOPPED`, then Stop cleans up), `TestResetFlowsKeepsEngineRunning`, `TestSlowEventReceiverNeverStallsTraffic` and `TestSlowEventReceiverGetsNothingAfterStop` (no event reaches the app after Stop returns), `TestCancellationStopsDialAtOnce`, `TestHandshakeTimeout`, `TestEngineThroughMockProxies`, `TestStartStopLifecycle` (the TUN descriptor stays open for its owner), `TestSelfTestThroughMockProxy`. On the emulators: `theEngineStartsAndStopsOnADescriptorItDoesNotOwn` and the self-test's leak check: after everything was released, no goroutine it started — itself or through the engine, the netstack or the dialer, all carrying an inherited profiler label — is still alive (`goroutines_left = 0`; goroutines the Go runtime starts for timer callbacks carry no label, and are covered by the Go tests' process-wide check instead) | **Pass** |
| No credentials appear in logs or crash reports | The engine never logs, and the netstack's own log lines, which gomobile would forward to logcat, are discarded when the library loads (`TestNetstackLoggingIsDiscarded`). Credentials are bytes that print as `<redacted>` under every format verb and encoder (`TestCredentialsNeverFormatted`), copied only into the buffers written to the proxy and zeroed after (`TestWipeZeroesSecrets`); error details are fixed phrases (`TestHTTPConnectSchemesNeverEchoProxyText`). `TestErrorsNeverContainCredentials` drives 17 failure paths with distinctive credentials and searches every error text and cause for them raw, in Base64 and in hex; `TestNoSecretsAnywhere` does the same for everything `pfengine` returns or emits (probe and self-test results, Start errors, status, stats, flows, events). On the emulators `credentialsNeverAppearInOutputsEventsOrLogcat` runs probes, self-tests and engine starts with distinctive credentials, then searches the results, the events and **the whole logcat** for them. Crash reports: the app has no crash-reporting SDK; a panic in a flow is recovered and reported as `INTERNAL` without its value; Go's crash traces print argument words (pointers), never the bytes they point to. On the Kotlin side the credential bytes are built without stray copies and wiped on every path (`SecretText`, `EngineSecret`) | **Pass** |
| Dependency and licence review is complete | `docs/m4/dependency-review.md`: every module linked into the library with version, licence and purpose (Go 1.27.3, gVisor, `x/net`, `x/sys`, `x/time`, `x/exp`, `btree`; gomobile's bind runtime), build tools, licence obligations for the in-app notices, update policy. CI rebuilds `go.sum` from `sum.golang.org` and compares it with the committed file, runs `govulncheck` over the engine and gomobile's runtime package `bind/seq` (the Android-only glue gomobile adds is not covered by a source scan), builds the AAR only from those verified, tested modules and prints the modules linked into it | **Pass** |

## The brief's tasks

| Task | Where |
|---|---|
| 1. Choose the audited TUN/TCP engine approved in M1; pin version and document licence | gVisor netstack (ADR-0001, accepted), pinned in `engine/go.mod`; licences and pins in `docs/m4/dependency-review.md` |
| 2. Engine interface: start, stop, state, active flows, errors, metrics | `tunnel.Engine` (`Start`, `Stop`, `ResetFlows`, `State`, `Flows`, `LastError`, `Status`, `Stats`, events) → `pfengine` (gomobile) → `ProxyEngine` (Kotlin); `docs/m4/protocol-compatibility.md` §2 |
| 3. HTTP CONNECT handshake and response validation | `engine/adapters/httpconnect.go` |
| 4. SOCKS5 negotiation, optional username/password, CONNECT, IPv4/IPv6/domain | `engine/adapters/socks5.go` |
| 5. Timeout, cancellation, bounded buffers, flow limits, back-pressure | `adapters/dialer.go` (connect and handshake timeouts, cancellation through a past deadline), `tunnel` (flow, in-flight and DNS limits, netstack TCP buffers of 64–128 KiB per direction with auto-tuning off, pooled relay buffers with blocking writes, idle timeout over both directions, bounded event queue), `fakedns` (bounded mapping table); table in `docs/m4/protocol-compatibility.md` §7 |
| 6. TLS-to-proxy for compatible endpoints; chain and hostname validated | `adapters/tls.go`, `core/engine/.../AndroidEnginePlatform.kt` (ADR-0005) |
| 7. Mock proxy fixtures for deterministic tests | `engine/internal/proxytest` (Go: SOCKS5, HTTP CONNECT, TLS, scripted misbehaviour, echo server, PKI, leak checks); `core/engine/src/androidTest` (Kotlin mock proxies, written separately from the Go client, and an on-device PKI) |
| 8. Sanitised errors; no credentials or proxy authorization headers in logs | stable codes with fixed-phrase details (`adapters/errors.go`); `Credentials` type; tests in the criteria table |
| 9. Engine lifecycle and cleanup after failure | `tunnel/engine.go`; tests in the criteria table |

## Deliverables

| Deliverable | Where |
|---|---|
| Protocol engine module | `engine/` (Go: `adapters`, `tunnel`, `fakedns`, `pfengine`, `internal/proxytest`, `build-aar.sh`) and `core/engine/` (Android library: `ProxyEngine`, `EngineSecret`, `AndroidEnginePlatform`, models) |
| Tests | 103 Go tests (race detector, 5 fuzz targets), 15 new JVM tests in `:core:engine` and 6 in `:core:model`, 8 instrumented tests on two emulators; the table below |
| Dependency audit | `docs/m4/dependency-review.md` |
| Protocol compatibility document | `docs/m4/protocol-compatibility.md` |
| Decisions | ADR-0001 to ADR-0004 accepted; ADR-0005 technical decisions accepted, D2 and D7 open (`docs/adr/README.md`) |

## Tests

From the CI runs of the verified commit (the same counts in all three attempts):

| Where | Tests | What they cover |
|---|---:|---|
| `engine/adapters` (Go) | 49 | SOCKS5 and HTTP CONNECT encoding and parsing byte for byte, every reply and status mapping (interim `1xx` included), sign-in outcomes, destinations through mock proxies to an echo server, TLS verification (12 certificate cases, IP SAN, resumption, wrong transport, half-close by TLS version), timeouts, cancellation, socket protection, proxy-host lookups (cached, shared, forgotten, abandoned, panicking), configuration and credential limits, redaction; 4 fuzz targets |
| `engine/tunnel` (Go) | 25 | the netstack with an in-process client stack: hostnames to the proxy, refusals, stale and pinned fake addresses, lifecycle, Start failure, Stop with open flows and pending set-ups, ResetFlows, limits, idle timeout in both directions, stalled-flow memory, flows and the privacy switch, TUN failure, slow and stuck event receivers, panic isolation, DNS limit, real adapters through mock proxies, proxy outage, IPv6, pings never answered |
| `engine/pfengine` (Go) | 14 | the gomobile API: Probe (SOCKS5, HTTP, TLS with a platform verifier, bad input), Start/Stop, the remembered Start error, ResetFlows forgetting proxy addresses, SelfTest (IPv4 name and IPv6 destinations, bad input, its labelled leak check), trust verdicts, netstack logging off, no secrets in any output |
| `engine/fakedns` (Go) | 13 | fake DNS answers; the pool: reuse delay, pinning by open connections, oldest-first reuse after a full cycle, random start, exhaustion → `SERVFAIL`, and a randomized model test of every invariant; fuzz target |
| `engine/internal/proxytest` (Go) | 2 | the leak checker catches leaks; the fixtures leave none |
| **Go total** | **103** | race detector on; every parser also fuzzed for 30 s per run |
| `core/engine` (JVM) | 15 | the credential encoding the engine reads and its zeroing, configuration JSON (exact keys, no credentials), JSON decoding, error codes, certificate-chain parsing and verdicts |
| `core/model` (JVM) | 25 | form validation (19, from M3) and `SecretText` (6: exact UTF-8 in every script, 500 random strings, broken text, length without encoding) |
| Rest of the app (JVM and Robolectric) | 142 | as in the M3 report (`CredentialCodecTest` now uses `SecretText`) |
| **JVM total** | **182** | |
| `core/engine` on emulators | 8 × 2 | `EngineOnDeviceTest` on API 29 and API 35 |
| `core/data` on emulators (M3) | 4 × 2 | `KeystoreOnDeviceTest` |

## The engine library (AAR)

Built in each CI run from the commit under test and handed to the Android jobs; from the verified commit's engine job (attempt 1; attempts 2 and 3 printed the same):

```
AAR build/pfengine.aar 11813019 bytes sha256 a0fecc4a500b7f53893c090ad9d22c0857c69f1d630f04cf799c304680a9bc45
BUILT WITH go version go1.27.3 linux/amd64, gomobile v0.0.0-20260908204917-8b95e45f8d3e, NDK 28.2.13676358, -androidapi 29
GOVULNCHECK v1.9.0 over ./... golang.org/x/mobile/bind/seq: No vulnerabilities found.
SO armeabi-v7a 9522388 bytes, LOAD alignment 0x4000
SO arm64-v8a 9732232 bytes, LOAD alignment 0x4000
SO x86_64 10420104 bytes, LOAD alignment 0x4000
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

All three attempts built the AAR from the verified commit byte for byte the same (SHA-256 `a0fecc4a…` in each attempt's notice): the build is reproducible (`-trimpath`, empty build id). Earlier, `b7de1c7` and `f990ead`, whose engine sources are identical, had also produced identical AARs (`d28304a2…`). Every 64-bit library is aligned to 16 KB, as Google Play requires for apps targeting Android 15 and later. The self-test's leak check links Go's `runtime/pprof`, which adds about 0.35 MB to each ABI's library (the arm64 library grew from 9.38 MB to 9.73 MB in `d99c55d`). The app does not include the library before M5, so its APKs (release 2,799,277 bytes) and its 129 release libraries are unchanged by M4.


## Independent reviews

Three reviews by reviewers who had not written the code: one of the whole milestone, then one of each round of fixes.

### First review

A separate reviewer read the whole M4 change (engine, Android module, CI, documents) against the brief. Every finding was fixed in `b7de1c7` (the "impact" column is this report's summary, not the reviewer's wording):

| Finding | Impact | Fix | Test |
|---|---|---|---|
| The fake-address pool recycled the least recently used address as soon as it was full, and every `Start` began a new pool | after a reconnect, or under name churn, an app holding an earlier answer could send its connection to a different host | a mapping is dropped only after 10 min without use; at most 16 384 names; `SERVFAIL` when every mapping is recent; one pool per process, random start (protocol compatibility §6, threat model D-2). The second review found two gaps in this fix, closed below | `TestPoolNeverReusesARecentAddress`, `TestPoolFreedAddressesWaitForAFullCycle`, `TestPoolStartsAtARandomAddress`, `TestResponderAnswersServfailWhenExhausted` |
| gVisor's forwarder gave every accepted connection a 1 MiB receive window, overriding the configured buffers, and receive buffers auto-tuned up to 4 MiB | a destination that stops reading could make each flow hold megabytes | 64 KiB buffers, at most 128 KiB, auto-tuning off, the forwarder's window set from the same value | `TestStalledFlowsHoldLittleMemory` (a stalled upload stops after 93 KiB) |
| gVisor's connections report deadlines with their own error type, which the relay did not recognise as a timeout | a one-way stream (a long download, say) was cut at the idle timeout although data was flowing | timeouts recognised through `net.Error` | `TestIdleTimeoutCountsBothDirections` |
| Events could still reach the app after `Stop` returned, when the receiver was slow | callbacks into a component that believes the engine stopped | after the 2 s drain the remaining events are dropped | `TestSlowEventReceiverNeverStallsTraffic` |
| `VerifyChain` treated an empty answer as "trusted", and a Java `null` arrives in Go as an empty string | a broken or null-returning trust callback would have accepted any certificate (fail open) | only the exact answer `OK` means trusted; Kotlin catches every `Throwable` as a refusal | `TestChainEncodingAndVerdicts`, emulator TLS test |
| The proxy's hostname was resolved again for every app connection | a burst of connections became a burst of lookups through the platform | answers cached 60 s (failures 5 s), concurrent dials share one lookup, `ResetFlows` forgets it | `TestProxyAddressesAreCachedAndShared`, `TestResetFlowsForgetsProxyAddresses` |
| HTTP CONNECT did not accept interim `1xx` responses | a proxy sending `100 Continue` or `103` before its `200` failed with `PROXY_GENERAL_FAILURE` | interim responses skipped within the same 16 KiB / 100-line budget; `101` is a protocol error | `TestHTTPConnectSkipsInterimResponses`, `TestHTTPConnectInterimResponsesShareTheBudget`, status mapping |
| An app's half-close was passed on as TLS `close_notify` also over TLS 1.2 | under TLS 1.2 that ends the whole connection, so the proxy could drop the rest of a reply | not passed on over TLS 1.2 (TLS 1.3 keeps it) | `TestTLSHalfCloseFollowsTheVersion` (fails without the fix) |
| `AndroidEnginePlatform`'s protector defaulted to "always protected" | a VPN started with the default would route the engine's own proxy sockets into the tunnel | no default; `NO_VPN` for use without a VPN; a throwing protector counts as a refusal | emulator: `unreachableAndSilentProxiesFailClosedInTime` (refused and throwing protectors fail closed, nothing reaches the proxy) |
| The JDK's convenience UTF-8 encoder, used for credentials in `EngineSecret` and `CredentialCodec`, grows its buffer for non-ASCII text and drops the old one unwiped | copies of part of a password left in the heap | `SecretText`: exact-size encoding and decoding, scratch arrays wiped | `SecretTextTest` (incl. 500 random strings), `CredentialCodecTest` |
| gVisor's own log lines (some with packet addresses) go to stderr, which gomobile forwards to logcat | destinations in logcat although "record destinations" is off | discarded when the library loads | `TestNetstackLoggingIsDiscarded` |
| `Status` after a failed `Start` said only "stopped", although the compatibility document promised the reason | the UI could not show why the tunnel did not start | the reason is kept until the next `Start` or `Stop` | `TestStatusRemembersWhyStartFailed` |
| `SelfTest` accepted an expected reply longer than the 4 KiB it reads | a misleading failure | refused as `INTERNAL` | `TestSelfTestRejectsBadInput` |
| CI never compared `go.sum` with the checksum database (with a hash present the go command does not ask it), although the dependency review said it did; the AAR was built even when the module checks failed; govulncheck did not cover gomobile's bind runtime; when one module's instrumented tests failed to build, no device test ran | weaker evidence than claimed | `go.sum` rebuilt from `sum.golang.org` and compared; AAR only after the module checks; govulncheck inside `build-aar.sh`, also over gomobile's runtime (its scope was corrected after the second review); emulator step runs regardless | the CI runs above |
| Several document statements went beyond the code (event delivery on `Stop`, the `Start` failure status, buffer sizes, the checksum check) | — | corrected with the code | — |


### Second review

A second reviewer checked the fixes (`3c5540a..b7de1c7`) and found no blocker or major problem; every finding was fixed in `d99c55d`:

| Finding | Fix | Test |
|---|---|---|
| A freed address did not really wait for a full cycle: the allocation cursor skipped an address while its mapping was in use and could hand it to the very next name once it was freed | freed addresses queue behind every address never handed out and every address freed earlier (FIFO) | `TestPoolFreedAddressesQueueOldestFirst` (the reviewer's scenario) |
| A connection counted as use only when it opened, so the mapping of a long-lived connection could be reassigned and the app's next connection to the cached address would reach another host | an open connection pins its mapping; the 10 minutes start when the last one closes | `TestPoolNeverReassignsAnAddressWithAConnectionOpen`, `TestOpenConnectionPinsItsFakeAddress` (through the engine) |
| A full pool refuses every app's new names — a denial-of-service risk the threat model did not mention; the memory figure ignored long names | documented as a residual risk (chosen over misrouting, counted as `exhausted`); memory measured for the longest names too (final figures in the compatibility document, §6) | — |
| The self-test's leak check counted stacks, so goroutines of a VPN engine running at the same time could cause false alarms or hide leaks | every goroutine the self-test starts carries an inherited profiler label; only those are counted, with the function each is in | `TestSelfTestCountsOnlyItsOwnGoroutines` |
| govulncheck also scanned gobind's code generator, which is not linked, and the documents claimed it scanned "exactly what the library links" | scope `./...` and `bind/seq`; the documents say what a source scan cannot cover (Android-only glue, findings only for reachable code) | CI notice |
| The report's test counts were from an earlier commit; two reproducibility claims disagreed | counts and facts taken from the verified commit's runs; one precise reproducibility statement | this report |
| `ForgetProxyAddrs` left a lookup on the old network for new dials to join | it detaches the lookup in progress | new case in `TestProxyAddressesAreCachedAndShared` |
| An event could still begin delivery after `Stop` returned (a tiny window) | delivery and abandonment are one atomic state; a slow receiver's call in progress gets 0.5 s more | `TestSlowEventReceiverGetsNothingAfterStop` |
| The terminating blank line had started to count towards the 100-header limit | exactly 100 header lines are accepted again, the budget still shared across interim responses | `TestHTTPConnectOversizedHeaders`, `TestHTTPConnectInterimResponsesShareTheBudget` |
| Credential buffers were wiped only for one exception type; the form validator measured the password's length by encoding it (an unwiped copy) | `try/finally` everywhere; `SecretText.utf8Length` counts without encoding | `SecretTextTest.lengthIsCountedLikeTheEncoder` |
| A stale comment | removed | — |

### Third review

A third reviewer verified the second round of fixes (`f990ead..d99c55d`) and found them correct (a randomized model test of the pool found no violation in 900 000 steps), with five smaller points, all fixed in `051c9dd`:

| Finding | Fix | Test |
|---|---|---|
| The queue of freed addresses could grow to about 2.4 times its largest content (1 MiB in the app) and never shrank | a fixed ring of `capacity − max + 1` slots (0.44 MiB in the app), allocated at the first eviction; the documents give the measured totals | `TestPoolModel` checks the bound after every step |
| Goroutines the Go runtime starts for timer callbacks carry no profiler label, so the self-test's leak check does not see them, although the documents said "every goroutine" | the documents say so; the Go tests' process-wide leak check covers them | — |
| `goroutines_left_in` could name lock and sleep frames instead of the code | those frames are skipped too | `TestWaitFramesAreSkipped` |
| `TestOpenConnectionPinsItsFakeAddress` could fail if the flow's last step had not run yet | it waits for the release instead | — |
| A CI comment still said govulncheck covered everything linked | corrected | — |

The reviewer's model test is kept as `TestPoolModel`: 150 random pools × 2 000 random lookups, connection openings and closings and clock steps, with every invariant checked after each step.

## What CI found

| Run | Problem | Fix |
|---|---|---|
| first M4 run | the AAR build failed without a readable log (Actions logs are not reachable from here) | the script prints step markers and the build log's tail as an annotation; the NDK install used `yes \| sdkmanager`, which fails under `pipefail` when `yes` gets SIGPIPE |
| `d43e9ea` | `pfengine` probe test read the mock proxy's record before it was written (timing) | the fixtures record what a client asked before answering it (Go and Kotlin) |
| `d43e9ea` | the instrumented tests did not compile: `ParcelFileDescriptor.createSocketPair(int)` is hidden API | a datagram socketpair from `Os.socketpair`, wrapped with `ParcelFileDescriptor.dup` |
| `3e4ac25` | on Android an expired proxy certificate came back as `TLS_UNTRUSTED`: when the server also sends its root, Android's trust manager reports "trust anchor not found" | the engine checks the leaf's validity dates itself before asking the platform (`TLS_EXPIRED`) |
| `3c5540a` | API 35: the self-test's whole-process goroutine count went from 4 to 5 | the count includes the Go runtime's and gomobile's own goroutines (finalizers releasing Java references run whenever the collector decides); the check now counts only the goroutines the self-test started (in the end by an inherited profiler label, see the reviews) |
| `b7de1c7` | `StateRestorationTest.chosenThemeIsSavedAndShownAfterRecreation` (an M2 test) failed once with `CalledFromWrongThreadException` from a background worker, in the screenshot task's second run of the unit tests | `MainActivity` was the only composable collecting a DataStore flow directly; DataStore emits on its I/O threads, and the v1 Compose test rules run composition on an `UnconfinedTestDispatcher`, so an emission could drive composition from an I/O thread. The flow is now collected on the main dispatcher (no change in production). CI's error digest now prints the frames that show where a failure came from |

## Decisions, deviations and open items

- **Owner decisions still open:** D2 (whether the Play build allows plaintext proxies) and D7 (TLS default in the form), both ADR-0005. Neither blocks M5.
- **The connection test is not wired to the engine yet.** `ProxyEngine.probe` does what the profile screen's "Test connection" needs, but the app's `ConnectionTester` still reports that testing is not available: what a test should contact is open decision D6 (handshake only, or a request to a documented URL), and the app gets the `INTERNET` permission with the VPN service in M5. Both are planned for M5/M9; nothing in the app pretends to test.
- **The app does not run the engine yet.** `:core:engine` is built and tested in CI but is not a dependency of `:app` until the VPN service exists (M5).
- **Real proxy software.** The engine was tested against mock proxies written for it in Go and, separately, in Kotlin. The M1 spike's adapters met Dante, Squid and stunnel; the M4 adapters have not yet, and M6 repeats those checks, with packet captures, on devices.
- **Memory under load** is bounded per flow (`docs/m4/protocol-compatibility.md` §7) but not yet measured on phones (M10).
- **Vulnerability scan coverage.** govulncheck scans the engine and gomobile's runtime package for the build host's platform; the small Android-only glue gomobile compiles in is not covered by a source scan (`docs/m4/dependency-review.md` §6).
- **Fake addresses.** A full pool refuses new names (`SERVFAIL`) rather than reuse an address an app may still hold; one app looking up 16 384 different names within 10 minutes can therefore stop name resolution for every app while it does (threat model D-2).
- **Outside the engine,** two app-level changes came out of CI and the reviews: `MainActivity` collects its settings on the main dispatcher, and `ProfileValidator` measures a password's UTF-8 length without encoding it.

## How to check

- CI evidence: `GET /repos/bibekthapamgr/app/actions/runs/41183070387/attempts/{1,2,3}/jobs`, then `GET /repos/bibekthapamgr/app/check-runs/{job_id}/annotations` for each job (test counts, the AAR's facts, the govulncheck verdict, the `go.sum` check, device test lists).
- Locally: `cd engine && go test -race ./...` needs only Go 1.27.3; `engine/build-aar.sh` builds the AAR with the Android SDK (it installs the pinned NDK when missing); `./gradlew :core:engine:testDebugUnitTest` and `:core:engine:connectedDebugAndroidTest` need the AAR.
