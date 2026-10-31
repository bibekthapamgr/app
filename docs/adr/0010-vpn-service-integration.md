# ADR-0010 — VPN service integration: connect sequence, session ownership, failure handling

**Status:** Accepted 2026-10-31 (technical decisions delegated by the owner, as for ADR-0001 to ADR-0005); the pre-flight target is an engineering default inside open decision D6 · **Date:** 2026-10-31

## Context

M5 connects the M4 engine to Android's VPN interface. M1 already fixed the outline: consent with `VpnService.prepare()` behind an explainer (J4, B9), a pre-flight proxy handshake before the VPN exists (J5), the `Builder` settings (`docs/m1/06-architecture.md` §4), a foreground service of type `systemExempted` (ADR-0007), "Connected" only after a successful handshake, and the error catalogue (`docs/m1/04-ux-screens-and-error-states.md` §4). What M1 left open, and this record decides: where the session lives and who owns the TUN descriptor, what the pre-flight check contacts, what happens when the engine dies or Android revokes the VPN, how duplicate starts are handled, and how all of it is proven on emulators. Reconnecting, offline handling and the escalation rule are M7; the kill switch and Always-on lockdown are M8; the Quick Settings tile and onboarding are M9.

## Decision

1. **Module `:core:vpn`** holds `ProxyFlowVpnService`, `VpnConnectionController` (the app's `ConnectionController`), the connection session and the notifications. Screens keep talking only to `ConnectionController` (ADR-0008); the controller's `state` is a `StateFlow<ConnectionState>` (status, profile, connect step, connected-since time, last error).
2. **One session, one thread of control.** The service runs one `VpnSession` at a time. Every transition — start, pre-flight result, establish, engine start, engine failure, revocation, stop — runs on one single-threaded dispatcher, so no two can interleave; engine calls block there, never on the main thread. The service owns the TUN `ParcelFileDescriptor`; the engine reads it but never closes it (M4), and the session closes it only after the engine has stopped.
3. **Connect sequence.** `startForeground` at once (type `systemExempted` on Android 14+), then: read the profile and its credentials (`CREDENTIAL_UNREADABLE` stops here) → **pre-flight**: one tunnel through the proxy with the same adapters (`ProxyEngine.probe`), on a socket protected with `VpnService.protect` → `Builder.establish()` with the M1 settings (`198.18.0.1/32`, `fd00:66::1/128`, routes `0.0.0.0/0` and `::/0`, DNS `198.18.0.2`, MTU 9000, the app itself excluded, the current network as underlying network, non-blocking, never `allowBypass`) → engine start on the descriptor → **Connected**. Any failure before `establish()` leaves the phone's network untouched and ends in Disconnected with the code (board B6b); nothing is ever reported Connected before the engine runs on the new interface.
4. **Pre-flight target: `connectivitycheck.gstatic.com:443`, CONNECT only.** HTTP proxies check sign-in only on a `CONNECT`, so the check needs a destination; it opens the tunnel and closes it at once, sending no bytes through it. The host is the one Android's own connectivity check uses, and port 443 is the one proxies allow by default (Squid's `SSL_ports`). A target-level answer (`TARGET_*`, `PROXY_GENERAL_FAILURE`) proves the proxy is reachable and accepted the sign-in, so the connection proceeds; a proxy-level code fails the check. The target is one constant; it is part of open decision D6 (what "Test connection" contacts) and the privacy policy must name it.
5. **Duplicate starts.** `connect()` for the profile that is already connecting or connected does nothing; for another profile it replaces the session: the new interface is established before the old one is closed (Android swaps them), so no traffic leaves outside the tunnel in between. `disconnect()` while connecting cancels the pre-flight and the session ends without ever reporting Connected.
6. **Engine failure fails closed.** When the engine reports `engine_failed` (its packet loop ended without `Stop`), the session keeps the VPN interface up with nothing reading it, so apps' traffic is blocked, and shows `ENGINE_STOPPED` (status Blocked) with Restart and "Stop and use normal network". Restart runs the connect sequence again; its `establish()` replaces the interface before the old descriptor is closed.
7. **Revocation.** `onRevoke()` (another VPN app started, or the user removed the permission) stops the engine, closes the descriptor, ends the foreground service and reports `VPN_REVOKED` (board B6d) in the app and in a notification on the "Connection problems" channel. Android has already removed the VPN, so apps use the normal network unless the system lockdown is on (M8).
8. **Service lifecycle.** `ACTION_CONNECT` / `ACTION_DISCONNECT` intents from the controller and the notification's Disconnect action; a start by Android with `VpnService.SERVICE_INTERFACE` (Always-on) connects the profile the user explicitly chose, or stops when there is none. `START_NOT_STICKY`: after process death the VPN is gone and the app restarts Disconnected, which is true. `onDestroy()` tears down whatever is left.
9. **Notifications.** Channel "Connection status" (low importance, ongoing) carries the foreground notification — Connecting (with the step), Connected (with Disconnect) or Blocked (with Restart and Disconnect); channel "Connection problems" (high importance) carries revocation. Tapping opens the app. Lock screens show a public version without the profile name (threat model I-11).
10. **Permissions in the UI.** Connect shows the B9 explainer before the system consent dialog whenever `VpnService.prepare()` returns an intent; a refusal or back-press shows H1 (`VPN_PERMISSION_DENIED`), and nothing is started. On Android 13+ the notification permission is asked once at the first Connect until onboarding (A3, M9) takes that over; the VPN works without it.

## Alternatives considered

| Option | Assessment |
|---|---|
| **Session on one serial dispatcher (chosen)** | Transitions can't race (a revocation during the pre-flight, a disconnect during `establish()`); blocking engine calls stay off the main thread |
| Locks around a shared state | The same safety with more ways to get it wrong |
| A separate process for the service | Isolation, but state would have to cross processes; the engine already isolates per-flow failures |
| Handshake-only pre-flight | Impossible for HTTP proxies, which evaluate sign-in only on `CONNECT` |
| Pre-flight to a URL with an HTTPS request | Proves more, but sends a request through the user's proxy on every connect; kept for "Test connection" (D6) |
| Close the interface when the engine dies | Simpler, but apps would silently fall back to the normal network: fail-open |
| `specialUse` service type first | Needs a Play justification; `systemExempted` is meant for VPN apps (ADR-0007) and is verified on Android 14–16 in CI |

## Consequences

- The app gains `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` and `POST_NOTIFICATIONS`, and its APK carries the engine library (`libgojni.so` per ABI).
- Every connect sends one CONNECT for `connectivitycheck.gstatic.com:443` through the user's proxy; the privacy policy must say so (D6).
- After an engine failure apps are offline until the user restarts or disconnects; that is the intended fail-closed behaviour, and the notification says so.
- The app's own sockets bypass the VPN (excluded application, and the engine's sockets are protected), so tests that need traffic through the tunnel must make it from another identity: the instrumented tests use the shell.

## Verification (M5)

JVM tests drive the session with fakes for every transition above; on emulators (API 29, 34, 35 and 36) the instrumented tests grant consent through the real system dialog and through `appops`, carry TCP from the shell through the VPN to a destination behind real proxy servers (Dante for SOCKS5, Squid for HTTP CONNECT) running on the CI runner, check the servers' logs, capture the emulator's uplink with `-tcpdump` and assert that nothing reached the destination or a DNS server directly, revoke the VPN by starting a second VPN app, and check that every disconnect releases the interface, the descriptors and the session's jobs.
