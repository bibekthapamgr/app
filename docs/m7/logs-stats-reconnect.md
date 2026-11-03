# M7 — Diagnostic log, session statistics and reconnecting

**Milestone:** M7 · **Decision:** ADR-0012 (with ADR-0010 and ADR-0011) · **Evidence:** JVM tests; device tests on API 29, 34, 35 and 36 (`ReconnectEndToEndTest`, `LogsEndToEndTest`, the updated `RoutingEndToEndTest` and `TlsEndToEndTest`); `scripts/m7_evidence.py`; `docs/m7/M7-acceptance-report.md`

This page says what the diagnostic log holds and never holds, what the session's counters count, how ProxyFlow reconnects on its own and what it does when the phone's network changes. It is the reference for the Logs screen, support and the M8 hardening review.

## 1. The diagnostic log

### What an entry holds

| Field | Content | Example |
|---|---|---|
| Time | When it happened (the phone's clock) | `2026-11-03 10:15:30.250 +09:00` |
| Level | info, warning (something failed, ProxyFlow is handling it) or error (something failed, the user acts) | `ERROR` |
| Event | What happened, a fixed key (table below) | `connect_failed` |
| Stage | Where the connection was (§2) | `proxy_handshake` |
| Code | The error catalogue's code when something failed (docs/m1/04 §4) | `AUTH_FAILED` |
| Profile | The profile's random id; on screen its name while it exists, in exports its first 8 characters | `4f6c2a1e` |
| Details | Values under fixed keys only: `protocol`, `tls_to_proxy`, `sign_in`, `switch`, `setup_ms`, `attempt`, `max_attempts`, `next_try_ms`, `reason`, `count`, `window_s`, `duration_ms`, `bytes_up`, `bytes_down`, `connections_opened`, `connections_failed`, `udp_blocked`, `pings_dropped`, `reconnects`, `network` (`wifi`, `cellular`, `ethernet`, `other`), `connections_reset`, `consecutive_failures`, `outage_ms`, `by`, `detail` (the engine's fixed phrase for a failure) | `detail="proxy rejected the username or password (HTTP 407)"` |

**Never in the log, the export or logcat:** a username or password, the proxy's host name or address, a site's name or address, a URL, anything apps send or receive, or the profile's name. For logcat this is a build rule: the Lint check `LogSecret` fails CI on a log statement that passes a value named like a credential, a server or a destination, bytes, the engine's secret, a whole profile or an exception's message (ADR-0012 §9). Every value passes `LogSanitizer` when written, when read and when exported: it is cut to 160 characters, control characters are removed, and anything that looks like an IPv4 or IPv6 address, a host or domain name, a URL, an e-mail address or `user:password@`, or a token of 24 or more characters is replaced (`[address]`, `[host]`, `[url]`, `[redacted]`); a code that does not look like a catalogue code becomes `INTERNAL`; a detail under any other key is dropped.

### What the session logs

| Event | Level | Stage | When | Details |
|---|---|---|---|---|
| `connect_requested` | info | starting | Connect, Try again, a restart, a switch to another profile | protocol, TLS to proxy, sign-in; `switch=restart` or `other_profile` |
| `stage_reached` | info | the stage | Each stage of an attempt the user started (§2) | — |
| `connected` | info | connected | Android routes apps into the VPN | `setup_ms` |
| `connect_failed` | error | the stage it failed at | An attempt failed before connecting: nothing changed on the phone | code, `detail` |
| `blocked` | error | the stage it happened at | Traffic held after a failure while connected (a refused sign-in or certificate, an engine failure, a deleted profile, or a failed restart) | code, `detail` |
| `reconnect_started` | warning | reconnecting | ProxyFlow starts reconnecting on its own (§4) | `reason` (`proxy_failures`, `network_back`, `network_changed`), code, `consecutive_failures`, `max_attempts` |
| `reconnect_attempt_failed` | warning | reconnecting | One attempt failed; another follows | code, `attempt`, `max_attempts`, `next_try_ms`, `detail` |
| `reconnected` | info | connected | An attempt passed: connected again on the same interface | `reason`, `attempt`, `outage_ms` |
| `reconnect_gave_up` | error | reconnecting | The last attempt failed: traffic stays held | code, `attempt`, `detail` |
| `offline` | warning | reconnecting | The phone has no network: traffic held (§5) | code `OFFLINE` |
| `network_changed` | info | the current stage | The phone moved to another network, or one is back | `network`, `connections_reset` |
| `stopped` | info | stopped | Disconnected | `by` (`user`, `profile_deleted`) |
| `revoked` | warning | stopped | Android removed the VPN | code `VPN_REVOKED` |
| `session_summary` | info | stopped | A connected session ends (a disconnect, or a switch to another profile) | `duration_ms`, `bytes_up`, `bytes_down`, `connections_opened`, `connections_failed`, `udp_blocked`, `pings_dropped`, `reconnects` |
| `connection_problems` | warning | connected | Connections to sites failed while the proxy worked, counted per code over a minute (and when the session ends) | code (`TARGET_UNREACHABLE`, `TARGET_NOT_ALLOWED`…), `count`, `window_s` |

### Storage, bounds and Clear

- The log is a database of its own on the phone (`logs.db`), apart from the profiles. Nothing is uploaded: ProxyFlow has no server.
- At most **5,000 entries**, none older than **24 hours**. The bounds are applied with every write, when the app starts and every hour while it runs, and when the Logs screen opens.
- **Clear** (with a confirmation) removes every entry. SQLite overwrites deleted rows (`secure_delete`) and the journal is truncated after each write, so nothing of the entries stays readable in the file; a test reads the file before and after.
- The session's entries are written in the background, in batches; a log that cannot be written never affects the connection.

### The Logs screen (boards F1–F3)

- **F1** (empty): what will appear, that logs stay on the phone for 24 hours, and what they never include.
- **F2** (list): newest first; each row says what happened in words, then the time, the level in words (never colour alone), the code and the stage. Filters: **All**; **Errors** (warnings and errors); **Connection** (requests, stages, connected, failures to connect, reconnection started and ended, network changes, disconnects, revocations, summaries); **Blocked** (blocked, reconnection started and its failed attempts, giving up, offline). Search matches codes, event and stage keys, detail values and profile names, in any case.
- **F3** (an entry's sheet): everything the entry holds, the profile's name while it exists, **What you can do** for its code (§6), and **Copy this event**.
- **Copy** and **Export** (the share sheet) hand over the entries shown, as text: a header, then one line per entry, oldest first, with stable keys, sanitized once more. At most 200,000 characters (one transaction to the other app): the newest entries that fit, and the header says how many of how many.

```
ProxyFlow diagnostic log
App 0.7.0 (7) · Android 15 (API 35)
Exported 2026-11-03 10:20:00.000 +09:00
Events: 3 of 41 · filter: Errors
Never includes passwords, server or site names, addresses or page content.

2026-11-03 10:15:30.250 +09:00  ERROR    connect_failed  stage=proxy_handshake  code=AUTH_FAILED  profile=4f6c2a1e  detail="proxy rejected the username or password (HTTP 407)"
2026-11-03 10:17:02.118 +09:00  WARNING  reconnect_started  stage=reconnecting  code=PROXY_UNREACHABLE  profile=4f6c2a1e  consecutive_failures=3  max_attempts=8  reason=proxy_failures
2026-11-03 10:17:04.391 +09:00  WARNING  reconnect_attempt_failed  stage=reconnecting  code=PROXY_UNREACHABLE  profile=4f6c2a1e  attempt=1  detail="no route to the proxy"  max_attempts=8  next_try_ms=2000
```

## 2. Connection stages

| Stage | When | Home (B3, one row each) |
|---|---|---|
| starting | Connect was tapped; the VPN service starts | Getting ready |
| VPN permission | Android's VPN consent is checked | VPN permission |
| proxy handshake | The pre-flight check: TCP, TLS to the proxy, the proxy's handshake and sign-in, a test tunnel (ADR-0010 §4) | Checking proxy — TLS and sign-in |
| authenticated | The proxy opened the test tunnel: the VPN interface is set up, the engine starts, Android moves apps over | Proxy accepted — starting the VPN |
| connected | Android routes apps into the VPN | B4 |
| reconnecting | ProxyFlow reconnects on its own, or the phone is offline; traffic held | B5, B7 |
| failed | An attempt failed (nothing changed) or traffic is held after a failure | B6 |
| stopped | Disconnected | B2 |

A switch to another profile or a restart shows Reconnecting with the same rows' stages in the log.

## 3. Session time and traffic

- **A session** lasts from the first Connected to a disconnect or a switch to another profile. Reconnections, restarts and time blocked inside it belong to it: Home's timer and counters go on through them.
- **Sent / Received** (B4) are what the engine relayed for apps' TCP connections through the proxy: the payload the apps' connections carried. Not counted: TCP/IP headers, the proxy handshake, TLS to the proxy, DNS answered on the phone, the pre-flight check. Counted too: every app's traffic through the VPN at the same time (it is the session's). Hence "approximate" on screen.
- **Active connections** are the apps' TCP connections relaying now.
- An engine's counters are added to the session's just before it stops, in one step with Home's readings, so the counters never go back while ProxyFlow reconnects. Home reads them once a second while it is shown.
- **Tolerance** (checked on devices against transfers of known size, 1 MiB up and 4 MiB down through SOCKS5 and HTTP CONNECT): the counters grow by at least the bytes the transfers' connections carried and at most that + 2 % + 256 KiB (other apps' traffic meanwhile).
- The session's summary in the log (`session_summary`) carries its length and totals.

## 4. Reconnecting on its own

| While connected | What happens |
|---|---|
| One or two connections fail at the proxy itself (unreachable, timeout, TLS handshake, protocol, the proxy's name not found) | Nothing: those connections fail (nothing goes around the proxy), the session stays connected |
| Three in a row, as the engine counts them (a connection that works resets the count) | **Reconnecting**: the engine stops, traffic is held at the VPN interface, attempts follow |
| The proxy refuses the sign-in (`AUTH_REQUIRED`, `AUTH_FAILED`) or its certificate (`TLS_UNTRUSTED`, `TLS_HOSTNAME`, `TLS_EXPIRED`) | **Blocked at once** (B6a, B6c): no retry changes that answer; the user edits the profile or tries again |
| The engine itself stops (`ENGINE_STOPPED`) | Blocked; Restart (unchanged from M5) |

- **Each attempt** is the pre-flight check; when it passes, a new engine starts on the same interface (what apps sent meanwhile is dropped; their connections retry and go through).
- **Waits**: 1, 2, 4, 8, 16, 30, 30 and 30 s before attempts 1 to 8, each moved by up to ±20 %: about two minutes in all. After a network comes back or changes, attempt 1 runs at once.
- **Bounded**: an attempt that fails with `PROXY_UNREACHABLE`, `PROXY_DNS_FAILED`, `HANDSHAKE_TIMEOUT`, `TLS_HANDSHAKE`, `PROTOCOL_ERROR`, `TARGET_UNREACHABLE`, `TARGET_REFUSED`, `PROXY_GENERAL_FAILURE` or `INTERNAL` waits for the next; any other code, or the last attempt failing, ends reconnecting: **Blocked**, traffic held, "reconnecting stopped after 8 attempts". Nothing runs on its own after that until the user taps Try again or Stop (or the phone's network changes, §5).
- **Cancellable**: Disconnect, "Stop and use normal network", a switch to another profile, a restart, a revocation and the service ending all cancel the waiting or running attempt first. A cancelled wait that wakes up late does nothing.
- **Never two at once**: there is one reconnection at most; a new trigger replaces it (its attempts start over), and **Retry now** only runs the waiting attempt sooner (it is disabled while an attempt runs).
- **Home (B5)**: "Reconnecting", why (the last error's title, or "Back online" / "Network changed"), "Attempt 2 of 8 · next try in 4 s" (or "trying now…"), the code, Retry now, Edit profile, Stop and use normal network, View logs. **Notification**: "Reconnecting… traffic paused · Attempt 2 of 8".
- The session's time and traffic go on (§3).

## 5. The phone's network

ProxyFlow watches the phone's default network for as long as the app runs. Its own traffic is outside its VPN, so that is the network the VPN's traffic leaves on (Wi-Fi, mobile data, Ethernet), never the VPN.

| Change | While connected | While reconnecting, or blocked after a failure worth retrying | Otherwise |
|---|---|---|---|
| No network | **Offline** (B7): the engine stops, traffic held, "Waiting for a network" | Offline | Noted |
| A network again | — | From Offline: reconnecting at once (`network_back`) | Noted |
| Another network | The engine's connections are ended (`ResetFlows`), apps reconnect on the new network; still Connected | Attempts start over, the first at once (`network_changed`) | Noted |

Android follows the default network for the VPN by itself (`setUnderlyingNetworks(null)`), and the VPN is metered when that network is.

## 6. Errors and what to do

| Kind | Codes | Home's first action | Also | What the log's sheet says to do |
|---|---|---|---|---|
| Sign-in | `AUTH_REQUIRED`, `AUTH_FAILED` | Edit profile | Try again | Check the username and password with the provider, then edit the profile; for an HTTP proxy offering only Digest or another method ProxyFlow lacks: ask for Basic sign-in or use SOCKS5 |
| Certificate | `TLS_UNTRUSTED`, `TLS_HOSTNAME` | Edit profile | Try again | Check the server name, or ask for a valid certificate; ProxyFlow never skips the check |
| Certificate dates | `TLS_EXPIRED` | Try again | Edit profile, Date & time settings | Check the phone's date and time; otherwise the certificate expired |
| TLS handshake | `TLS_HANDSHAKE` | Edit profile | Try again | Check the port and whether the provider offers TLS on it; never a fallback without TLS |
| DNS | `PROXY_DNS_FAILED` | Edit profile | Try again | Check the proxy's name, or use its IP address |
| Timeout | `HANDSHAKE_TIMEOUT` | Try again | Edit profile | Try later or check with the provider |
| Unreachable | `PROXY_UNREACHABLE` | Try again | Edit profile | Check the address and port, and that the proxy is online |
| Unsupported protocol | `PROTOCOL_ERROR` | Edit profile | Try again | Check the protocol and port; for an HTTP proxy that allows no tunnels (HTTP 405/501): another port or proxy |
| Sites | `TARGET_*`, `PROXY_GENERAL_FAILURE` | (at connect: Try again) | — | Some sites were unreachable or refused; other connections are unaffected |
| No network | `OFFLINE` | — | Stop | ProxyFlow reconnects when the phone has a network |

## 7. Limitations

- The counters are approximate (§3): protocol overhead is left out, other apps' traffic through the VPN is counted in.
- While reconnecting or offline the engine is stopped, so nothing is answered on the phone either, DNS included: apps wait, then retry.
- A real switch between Wi-Fi and mobile data was not exercised on the CI emulators (their uplink is kept to one network on purpose, `scripts/e2e_device.sh`): the device tests tell the session of a network change themselves, through the same call the network monitor makes, and check the monitor's own report against Android's on each API level. A check on physical devices belongs to M10.
- Retention (24 h), the limit (5,000) and the log's level are fixed in M7; settings for them, and the option to record destinations M1 reserved, wait for M9.
- An exported log is the user's to share: it holds times, codes, stages, counts, the network's kind and the first 8 characters of profile ids — nothing that names a server, a site or a person.

## 8. How it is verified

| M7 acceptance criterion | JVM tests | Device tests (API 29, 34, 35, 36) |
|---|---|---|
| Logs identify the failure stage without secrets or payloads | `ReconnectTest.aConnectionLogsEachStageInOrderAndNothingSecret`, `aFailedCheckLogsItsStageAndCode`; `LogsTest` (sanitizer, export); `LogRepositoryTest.whateverAWriterPassesIsSanitizedBeforeItIsKept`; `LogsScreenTest.theExportedTextHasAHeaderAndSanitizedEntries` | `LogsEndToEndTest.aRefusedSignInIsLoggedWithItsStageAndNothingSecret` (the app's database and the export, against the run's credentials and proxy address); `aSessionIsLoggedFromItsRequestToItsSummary`; `TlsEndToEndTest.aCertificateThatChangesWhileConnectedBlocksAtOnce` (logged at stage connected) |
| Storage is bounded and Clear removes local entries | `LogRepositoryTest` (limit, retention on write and purge, Clear, the file after Clear) | `LogsEndToEndTest.clearingTheLogLeavesNothingReadableOnThePhone` (the database file read before and after) |
| Traffic counters checked against controlled transfers with a documented tolerance | `ReconnectTest.anAttemptThatPassesTheCheck…` (carried across an engine restart), `theTrafficNeverGoesBackWhileOneEngineStopsAndTheNextStarts` | `ReconnectEndToEndTest.theTrafficCountersMatchControlledTransfers` (SOCKS5 and HTTP CONNECT; `m7_evidence.py` re-checks each line, the bulk server's own counts and that each of its connections came through a proxy) |
| Retry attempts are limited and cancellable | `ReconnectTest` (three in a row, block at once, bounded, refused for good, cancel while waiting and while running, Retry now, no second reconnection) | `ReconnectEndToEndTest.aProxyOutageReconnectsOnItsOwnAndTheSessionGoesOn`, `reconnectingStopsAfterItsLastAttemptWithTrafficHeld`, `disconnectingWhileReconnectingCancelsIt`, `retryNowRunsTheNextAttemptAtOnce`; `m7_evidence.py` (no attempt beyond its limit, every reconnection ended) |
| UI state consistent through rapid connect/disconnect and network changes | `ReconnectTest.rapidConnectsAndDisconnectsLeaveOneConsistentEnd`, the network tests; `VpnSessionTest` (M5's lifecycle tests) | `ReconnectEndToEndTest.rapidConnectsAndDisconnectsEndConsistently`, `withoutANetworkTrafficIsHeldUntilOneIsBack`, `aNetworkChangeEndsTheEnginesConnectionsAndTrafficGoesOn` (a connection held open ends at once), `theNetworkMonitorKnowsThePhonesNetwork` |

Traffic held while reconnecting, offline or blocked is checked as in M5: a line sent to the lab's sink must get no answer, and `m5_evidence.py` fails on any connection the sink logged; lines that had to fail must arrive nowhere (`m6_evidence.py`).
