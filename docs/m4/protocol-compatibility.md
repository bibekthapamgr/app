# M4 — Protocol compatibility and engine API

**Milestone:** M4 · **Code:** `engine/` (Go), `core/engine/` (Android) · **Decisions:** ADR-0001 to ADR-0005

This is what the packet engine speaks, to whom, and how the app drives it. Where a behaviour is a choice, the reason is given; where a feature is missing, it says so. Traffic that does not go through the proxy at all (UDP, ICMP, Android system traffic) is defined in `docs/m1/02-traffic-support-matrix.md` and ADR-0004; §8 below repeats the engine's part.

## 1. In one table

| | Supported in V1 | Not supported |
|---|---|---|
| Proxy protocols | SOCKS5 (RFC 1928), HTTP CONNECT (RFC 9110 §9.3.6) | SOCKS4/4a, HTTP forward proxying of plain requests, HTTP/2 or HTTP/3 CONNECT, chained proxies, PAC files |
| Sign-in | none; SOCKS5 username/password (RFC 1929); HTTP Basic (RFC 7617), sent with the first request | GSSAPI, HTTP Digest, NTLM, Negotiate/Kerberos (a Digest-only proxy gets `AUTH_FAILED` with an explanation) |
| SOCKS5 commands | `CONNECT` | `BIND`, `UDP ASSOCIATE` (ADR-0004) |
| Destinations | hostnames (sent to the proxy unresolved), IPv4, IPv6 | — |
| Transport to the proxy | TCP; TLS 1.2 or 1.3 on top (per profile), certificate chain and hostname verified | plaintext fallback after a TLS failure; self-signed or private-CA proxies (ADR-0005 decision 4); client certificates |
| App traffic carried | TCP over IPv4 and IPv6 | UDP (refused), ICMP (never answered); DNS is answered on the phone (fake-IP, ADR-0003) |

## 2. Engine API

Three layers, each a thin wrapper of the one below:

| Layer | Where | Used by |
|---|---|---|
| `tunnel.Engine` (Go) | `engine/tunnel` | `pfengine`, Go tests |
| `pfengine` (Go, exported with gomobile as `app.proxyflow.engine.go.pfengine.Pfengine`) | `engine/pfengine` | `core/engine` |
| `ProxyEngine` (Kotlin) | `core/engine` | the VPN service (M5), the connection test (M9) |

### Lifecycle

`New → Start → (running; ResetFlows any number of times) → Stop`. An engine is never restarted: `Start` after `Stop` fails, and the app builds a new one (the `pfengine` layer does that by itself).

| State | Meaning | Entered when |
|---|---|---|
| `new` | built, not reading packets | `New` |
| `running` | processing packets | `Start` succeeded |
| `failed` | the TUN read loop ended without `Stop` (the descriptor was closed or the interface went away); reported once as `ENGINE_STOPPED` | the loop ends on its own |
| `stopping` | `Stop` is ending flows and waiting for goroutines | `Stop` called |
| `stopped` | everything released | `Stop` finished, or `Start` failed: then `pfengine`'s `Status` carries `last_error` (code `INTERNAL`, the same detail `Start` returned) until the next `Start` or `Stop` |

`Stop` cancels every tunnel still being set up (code `CANCELED`), resets every open flow towards the app and closes it towards the proxy, closes the netstack, waits for every engine goroutine, lets the event queue drain for at most 2 s — after that the remaining events are dropped and the call in progress gets 0.5 s more to return — and wipes the credentials. No call to the event receiver begins after `Stop` returns; only a receiver stuck in one call for longer than that can still be inside it. It is safe to call at any time, more than once and from several threads; every call returns after the engine has stopped. It **never closes the TUN descriptor**: the VPN service owns it.

`ResetFlows` aborts every flow (apps see a reset and reconnect), forgets the proxy's resolved addresses (the next connection looks the proxy host up on the new network) and keeps the engine running; the VPN service calls it when the underlying network changes (`docs/m1/06-architecture.md` §6).

### pfengine functions (Kotlin names in brackets)

| Function | Returns | Notes |
|---|---|---|
| `Start(tunFD, configJSON, secret, platform)` (`start`) | error `"CODE: detail"` | one engine per process; `secret` zeroed before it returns; a failure is also reported by `Status` (above) |
| `Stop()` (`stop`) | — | as above |
| `Status()` | JSON | state, start time, last engine error (or why the last `Start` failed), last proxy-level error, consecutive proxy failures, flow count — cheap, for frequent polling |
| `Stats()` | JSON | everything in Status plus byte, flow, failure-by-code, DNS-outcome, UDP and ICMP counters, events dropped, goroutines, memory |
| `Flows()` | JSON array | id, kind (`tcp`/`dns`), state (`connecting`/`open`), destination kind, destination **only with "record destinations"**, port, age, bytes each way |
| `ResetFlows()` | count | |
| `Probe(configJSON, secret, target, timeoutMs, platform)` | JSON | one tunnel to `target` ("host:port"), opened and closed, without a TUN: the connection test and the pre-flight check before the VPN starts. `{"ok":true,"latency_ms":…}` or `{"ok":false,"code":…,"detail":…,"proxy_level":…}` |
| `SelfTest(configJSON, secret, target, payload, expect, timeoutMs, platform)` | JSON | the whole engine on a socketpair with an in-process client network stack: fake DNS, TCP to `target` through the proxy, `payload` out, `expect` bytes back (at most 4 KiB; more is refused as `INTERNAL`), UDP to the target refused, engine stopped, and a leak check: every goroutine the self-test's goroutines start — itself, or through the engine, the netstack or the dialer — carries a profiler label that goroutines inherit, and `goroutines_left` counts the labelled ones still alive after everything was released (with the function each is in), so goroutines of a VPN engine running at the same time, of the Go runtime or of gomobile are never mistaken for leaks. Goroutines the Go runtime starts for timer callbacks (the netstack's timers, deadlines) carry no label and are not counted; they run briefly, and the Go tests' process-wide leak check covers them. Whole-process counts are reported for information. A diagnostic that needs no VPN permission; the M4 device tests use it |
| `NetworkPlan()`, `Version()` | JSON, text | the VPN builder's addresses (`198.18.0.1`, `fd00:66::1`, DNS `198.18.0.2`, fake range `198.18.0.0/15`); module versions and Go toolchain |

### Platform callbacks (implemented in Kotlin by `AndroidEnginePlatform`)

| Callback | Android implementation | Called |
|---|---|---|
| `Protect(fd) bool` | `VpnService.protect` while the VPN runs (M5). `AndroidEnginePlatform` has no default protector, so a VPN cannot be started with one that forgets to protect; `NO_VPN` (always true) is for probes and self-tests when no VPN exists. A protector that throws counts as `false` | on every socket to the proxy, before `connect()`; `false` fails the dial (`PROXY_UNREACHABLE`, "protect failed") and nothing is sent |
| `ResolveProxyHost(host) (string, error)` | `Network.getAllByName` on the network the VPN runs over; the default network before the VPN | only for the proxy's own hostname — the one DNS lookup that leaves the phone (ADR-0003); cached, see §6 |
| `VerifyChain(chain, host) string` | `X509TrustManagerExtensions.checkServerTrusted(chain, "GENERIC", host)` on the platform trust manager. Returns exactly `OK` when trusted; anything else — a code, an empty string (what a Java `null` becomes), any text — is a refusal, and every exception on the Kotlin side ends as `TLS_UNTRUSTED` | on every TLS handshake with the proxy, resumed ones included |
| `OnEvent(json)` | parsed into `EngineEvent` | from one engine goroutine, in order; a slow receiver loses events (counted), never traffic |

### Configuration JSON

Keys (snake_case; unknown keys are rejected so a typo cannot silently change behaviour): `protocol` (`"SOCKS5"` or `"HTTP"`), `host`, `port`, `tls`, `tls_server_name`, `mtu`, `log_destinations`, `dial_timeout_ms`, `handshake_timeout_ms`, `idle_timeout_ms`, `max_conns`. **No credential key exists**: the username and password travel only as `secret`, the bytes of the app's credential encoding (version byte 1, then username and password, each as a 2-byte big-endian length and UTF-8 — the layout of `CredentialCodec`, ADR-0006).

## 3. SOCKS5 client

| Step | Behaviour |
|---|---|
| Greeting | Offers `00` (no authentication) only, or `00 02` when the profile signs in, so credentials are never sent to a proxy that did not ask for them |
| Method `02` | RFC 1929 sub-negotiation from one buffer of exactly the right size, zeroed after writing. Username 1–255 bytes, password 0–255 bytes (the app's form requires at least one byte). A status reply with version `01` (RFC) or `05` (seen in the wild) is accepted |
| Method `FF` | `AUTH_REQUIRED` without credentials configured, `AUTH_FAILED` with them |
| A method that was not offered | `PROTOCOL_ERROR` (credentials are not sent) |
| Request | `CONNECT` with ATYP `03` (hostname, ≤ 255 bytes, validated: lower-case letters, digits, `-`, `_`, dots), `01` (IPv4; IPv4-mapped IPv6 addresses are sent as IPv4) or `04` (IPv6) |
| Reply | `00` success; failures map to codes at once, even if the proxy closes before sending the bound address: `01` → `PROXY_GENERAL_FAILURE`, `02` → `TARGET_NOT_ALLOWED`, `03`/`04`/`06` → `TARGET_UNREACHABLE`, `05` → `TARGET_REFUSED`, `07`/`08` → `TARGET_UNSUPPORTED`, unassigned → `PROXY_GENERAL_FAILURE`. A reply that is not version 5 → `PROTOCOL_ERROR` |

## 4. HTTP CONNECT client

| Step | Behaviour |
|---|---|
| Request | `CONNECT host:port HTTP/1.1`, `Host: host:port`, IPv6 in brackets. Hostnames are validated first (no spaces, CR/LF or `:` can reach the request line — header injection through crafted DNS names is impossible) |
| Sign-in | `Proxy-Authorization: Basic base64(user:pass)` on the first request. The header is built in one exactly-sized byte buffer and zeroed after writing; usernames with `:` are refused (RFC 7617); each field ≤ 1 024 bytes |
| Response | Status line `HTTP/1.x NNN …`; headers read up to 16 KiB and 100 lines (a hostile proxy cannot make the engine buffer more); `Proxy-Authenticate` schemes are noted. Interim `1xx` responses before the final one (`100`, `102`, `103`: RFC 9110 §15.2 says a client must accept them) are skipped, and all responses together share the one 16 KiB / 100-line budget |
| Status mapping | 2xx → tunnel open; 407 → `AUTH_REQUIRED` (nothing sent) or `AUTH_FAILED` (with an explanation when the proxy offers only Digest/NTLM/Negotiate); 403 → `TARGET_NOT_ALLOWED`; 405/501 → `PROTOCOL_ERROR` ("this proxy doesn't allow tunnels"); 502/503/504 → `TARGET_UNREACHABLE`; 101, 3xx and 400 → `PROTOCOL_ERROR`; others → `PROXY_GENERAL_FAILURE`; anything that is not HTTP → `PROTOCOL_ERROR` |
| Bytes after the header block | delivered to the app as the first tunnel bytes (proxies may send the 200 and data in one segment) |

Each app connection gets its own proxy connection; nothing is pooled or reused.

## 5. TLS to the proxy

| Aspect | Behaviour |
|---|---|
| Versions | TLS 1.2 minimum, TLS 1.3 preferred (Go `crypto/tls`, Go 1.27.3 defaults for cipher suites and curves) |
| Server name | the profile's TLS server name, else the proxy host. A hostname is sent as SNI and checked against the certificate; an IP address is not sent as SNI (RFC 6066) and must appear as an IP address in the certificate |
| Verification | Go's built-in verification is replaced, never skipped: on every handshake (resumed ones included) the engine checks the hostname on the leaf with Go's RFC 6125 matching (`TLS_HOSTNAME`) and the leaf's validity dates (`TLS_EXPIRED`), then hands the chain to the verifier. On Android that is the platform trust manager: system CAs only, Android's distrust rules, the app's network security config; it checks every certificate of the path it builds (`TLS_UNTRUSTED`, `TLS_EXPIRED`). The engine's own date check only adds a rejection; it is there because Android's trust manager reports an expired leaf as "trust anchor not found" when the server also sends its root, and `TLS_EXPIRED` tells the user to check the phone's clock. Intermediates are left to the platform, which may build a path without an expired one the server sent. Without a verifier TLS cannot be configured at all |
| Failure | the dial fails with the TLS code; there is no plaintext fallback and no "trust anyway". A proxy that does not speak TLS on that port → `TLS_HANDSHAKE` |
| Sessions | an LRU cache of 64 sessions per proxy saves round trips; a resumed session is verified again like a full handshake |
| Half-close | over TLS 1.3 an app's half-close is passed on as `close_notify`, which closes one direction only (RFC 8446 §6.1). TLS 1.2 has no half-close — `close_notify` ends the whole connection (RFC 5246 §7.2.1) and the proxy would drop the rest of the reply — so over TLS 1.2 it is not passed on: the upload direction just stops, and the connection closes when the reply ends or the flow idles out |

## 6. Destinations and the proxy endpoint

- **Hostnames.** Apps resolve through the engine's fake DNS, then connect to a fake address; the engine maps it back and sends the **hostname** to the proxy, which resolves it (ADR-0003). An address from the fake range with no mapping (left over in an app's cache) is refused with `STALE_DNS_MAPPING`; the app resolves again.
- **The fake-address pool** (`198.18.0.0/15` minus 256 reserved addresses: 130 815). Apps keep addresses much longer than the 5 s TTL, so an address goes to another name only after its mapping has been **unused for 10 minutes**: no answer for the name, no new connection to the address, and **no connection to it still open** — an open connection pins its mapping, and the 10 minutes start when the last one closes (threat model D-2). Freed addresses then queue behind every address never handed out and every address freed before them, so a freed address returns only after a full cycle of the pool. At most **16 384** mappings exist (measured: about 3.9 MiB when full of 35-character names and 7.2 MiB with names of the maximum length, plus 0.44 MiB for the queue of freed addresses once names are being replaced); when all of them are in use or were used in the last 10 minutes, a new name gets `SERVFAIL` (counted as `exhausted`) rather than an address another app may still hold. The pool lives as long as the app process, so mappings survive a reconnect; each process starts allocating at a random address, so an address an app cached before the app restarted is very unlikely to map to anything (it is refused) — unlikely, not impossible: it then reaches whichever name the new process gave that address.
- **IP literals** are sent as IPv4 or IPv6 addresses. Port 0 is refused (`TARGET_UNSUPPORTED`).
- **The proxy endpoint.** An IP address is dialled directly. A hostname is resolved by the app (`ResolveProxyHost`); the answer is cached for 60 s and a failure for 5 s, concurrent connections share one lookup (a connection that gives up does not take the others down with it), and `ResetFlows` forgets it — a connection made after that looks the host up afresh rather than joining a lookup started on the old network. The engine tries up to four addresses within the connect timeout, sharing the time between them with a 2 s floor so a dead first address cannot starve the others; the first that answers moves to the front for later connections. Every socket is protected before `connect()`; TCP keep-alive is on (30 s).

## 7. Timeouts, cancellation, limits and back-pressure

| Setting | Default | What happens at the limit |
|---|---|---|
| Connect to the proxy (all its addresses) | 10 s | `PROXY_UNREACHABLE` |
| TLS and proxy handshake | 10 s | `HANDSHAKE_TIMEOUT` |
| Whole tunnel set-up per app connection | 15 s | the earlier of the two above applies |
| Cancellation (Stop, ResetFlows) | at once | blocked reads and writes are interrupted by a past deadline; `CANCELED` (counted, never shown, never a proxy failure) |
| Concurrent TCP flows, set-ups included | 1 024 | new connections refused with a RST, `CONNECTION_LIMIT` |
| Pending app handshakes held by the netstack | 512 | further SYNs dropped; apps retransmit (back-pressure instead of memory growth) |
| Concurrent DNS sockets | 256 | further ones dropped (counted); the app retries |
| Idle timeout per flow (both directions together) | 5 min | flow closed, counted |
| DNS socket idle | 2 s | closed |
| Relay buffer | 32 KiB per direction per open flow, from a pool | a slow side blocks the copy, which stops reading the fast side, so TCP flow control throttles it instead of queueing data |
| Netstack TCP buffers (towards the apps) | 64 KiB, at most 128 KiB per direction, never auto-tuned upwards | the app's TCP window closes. In `TestStalledFlowsHoldLittleMemory` an upload to a destination that never reads stopped after 93 KiB (engine window, one relay buffer and the app's own send buffer). Worst case for a flow stalled both ways with every buffer full: about 320 KiB (two netstack buffers, two relay buffers), so about 320 MiB if all 1 024 flows stalled at once — M10 measures real memory use on devices. (gVisor's defaults would have been 1 MiB per direction growing to 4 MiB) |
| Fake-DNS mappings | 16 384, each kept while a connection to it is open and until 10 min after its last use | new names answered `SERVFAIL` until one ages out (§6) |
| Proxy host lookups | cached 60 s (failures 5 s); concurrent connections share one lookup | — |
| Proxy response headers | 16 KiB / 100 lines | `PROTOCOL_ERROR` |
| DNS message | 4 KiB | — |
| Event queue | 1 024 events | new events dropped and counted |

A panic in one flow is recovered, counted and reported as `INTERNAL`; that flow's sockets are closed and the rest of the engine keeps running.

## 8. Traffic the proxy does not carry (engine part)

| Traffic | Engine behaviour |
|---|---|
| UDP to port 53, any address, IPv4 or IPv6 | answered on the phone by fake DNS: `A` → fake address (TTL 5 s), or `SERVFAIL` when the pool is exhausted (§6); `AAAA`, `HTTPS`, `SVCB` → no data; other types and malformed names → refused |
| Other UDP (QUIC, games, calls) | refused with ICMP port unreachable, never forwarded; counted per port |
| ICMP echo (ping), IPv4 and IPv6, fragmented or not | never answered (the echo filter drops requests and any reply the stack makes) |
| IPv6 TCP | carried like IPv4 |

## 9. Error codes

Stable strings shared with the UI catalogue (`docs/m1/04-ux-screens-and-error-states.md` §4); codes are only ever added.

| Code | Level | Raised when |
|---|---|---|
| `PROXY_UNREACHABLE` | proxy | TCP to the proxy refused, timed out or unroutable; socket protection failed |
| `PROXY_DNS_FAILED` | proxy | the proxy's hostname has no usable address |
| `AUTH_REQUIRED` / `AUTH_FAILED` | proxy | see §3 and §4 |
| `TLS_UNTRUSTED` / `TLS_HOSTNAME` / `TLS_EXPIRED` / `TLS_HANDSHAKE` | proxy | see §5 |
| `PROTOCOL_ERROR` | proxy | not a SOCKS5/HTTP proxy, malformed or oversized answer, proxy closed or reset during the handshake |
| `HANDSHAKE_TIMEOUT` | proxy | the proxy accepted TCP but did not finish in time |
| `TARGET_NOT_ALLOWED` / `TARGET_UNREACHABLE` / `TARGET_REFUSED` / `TARGET_UNSUPPORTED` / `PROXY_GENERAL_FAILURE` | target | the proxy works but refused or could not reach one destination |
| `STALE_DNS_MAPPING` / `CONNECTION_LIMIT` / `APP_HANDSHAKE_FAILED` / `INTERNAL` | target | engine-local, per connection |
| `CANCELED` | internal | a set-up interrupted by Stop or ResetFlows; never shown |
| `ENGINE_STOPPED` | system | the TUN read loop ended without Stop (engine state `failed`) |

Every error carries a log-safe detail. Details are fixed phrases or numbers (a status code, a reply byte); they never contain credentials, payload bytes or text the proxy sent. The underlying I/O error is kept for debugging inside the engine but is not part of any message.

## 10. Credentials and logs

- The engine keeps the username and password as bytes in `adapters.Credentials` (a Go string cannot be wiped), copies them only into the short buffers that are written to the proxy and zeroes those right after, and wipes its copy in `Stop`. Kotlin's `EngineSecret` zeroes the bytes it handed over. Because every new app connection signs in again, the bytes live for the whole session (threat model I-12).
- `Credentials` prints as `<redacted>` under every `fmt` verb and every encoder; the dialer's log form says only "sign-in on/off".
- The engine writes nothing to logcat. That includes the netstack: gVisor's own log lines (a few carry packet addresses) would go to stderr, which gomobile forwards to logcat, so the library discards them when it loads (`TestNetstackLoggingIsDiscarded`). Events, status, stats and flows contain no credentials, and destinations only when the user turns on "record destinations".
- Checked by: `TestCredentialsNeverFormatted`, `TestErrorsNeverContainCredentials` (every failure path, raw, Base64 and hex forms), `TestNoSecretsAnywhere` (all `pfengine` outputs and events), and on the emulators `credentialsNeverAppearInOutputsEventsOrLogcat` (outputs, events and the whole logcat).

## 11. What it was tested against

| Counterpart | Where | What |
|---|---|---|
| Mock proxies in Go (`engine/internal/proxytest`) | `go test -race` in CI | SOCKS5 and HTTP CONNECT with and without sign-in, TLS (valid, intermediate, wildcard, IP SAN, expired, not yet valid, wrong name, untrusted, self-signed, client-auth-only, intermediate missing, plaintext port, TLS-only port; half-close over TLS 1.2 and 1.3), scripted replies (every SOCKS5 reply code, HTTP 100/102/103 before the final answer, 101, 403/405/407/500/502/504, Digest-only, non-HTTP answers, extra bytes after the reply), silent and closing proxies, IPv4/IPv6/hostname destinations, resumed TLS sessions, proxy host lookups (shared, cached, forgotten, abandoned) |
| The whole netstack through those proxies | `go test -race` | an in-process client stack on a socketpair: DNS, TCP, data, refusals, proxy outage, limits, Stop and ResetFlows with flows open, leak checks |
| Mock proxies in Kotlin, written separately from the Go client | API 29 and API 35 emulators | the same protocols through the real `libgojni.so`, TLS with a PKI generated on the device and the platform trust manager, `SelfTest` |
| Dante 1.4.3, Squid 6.14, stunnel | M1 lab (`docs/m1/09-feasibility-report.md`, E01–E05) | the spike's adapters, from which these were developed; the M4 adapters are a rewrite and have not met real proxy software yet — M6 repeats the protocol and capture checks against real servers on devices |

## 12. Known limits

- HTTP proxies that require Digest, NTLM or Kerberos cannot be used (V1 scope).
- A proxy with a self-signed or private-CA certificate cannot be used over TLS (ADR-0005 decision 4).
- New app connections pay the proxy handshake before their SYN-ACK; a proxy that stops answering makes new connections wait up to 15 s before they are refused.
- The engine trusts the app's `Protect` and `ResolveProxyHost` callbacks; M5 supplies the real VPN-side ones.
- Over TLS 1.2 an app's half-close is not passed to the destination (§5); a protocol that waits for the client's half-close before answering would wait until the idle timeout. TLS 1.3 proxies are not affected.
- An address an app cached before the app process restarted may, rarely, map to another name in the new process (§6).
- One app can keep the fake-address pool full — 16 384 different names looked up within 10 minutes, about 27 a second — and while it does, new names fail with `SERVFAIL` for every app. The alternative, reusing addresses still in use, would send apps to the wrong host; the `exhausted` counter shows it happening.
- Memory under load is bounded per flow (§7) but has not been measured on phones yet (M10).
