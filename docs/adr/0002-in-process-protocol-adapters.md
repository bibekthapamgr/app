# ADR-0002 — In-process protocol adapters, dial-before-accept, no local listener

**Status:** Accepted 2026-10-31 (part of D1, delegated by the owner) · **Date:** 2026-10-30

## Context

Many tun2socks-style apps run the TUN engine against a SOCKS5 server listening on `127.0.0.1`, with protocol conversion behind it. Any app on the phone can connect to such a port. Detection apps now scan localhost for exactly these ports (for example RKNHardering on F-Droid, whose "Bypass Check" "scans localhost for open SOCKS5/HTTP proxies"; `10-platform-and-policy-constraints.md` §8). An unauthenticated local port also lets any other app send traffic through the user's paid proxy and learn its exit IP.

Separately, a proxy failure must never let an app's connection proceed elsewhere ("do not silently bypass the configured proxy").

## Decision

1. Protocol adapters (SOCKS5, HTTP CONNECT, TLS wrapper) run **inside the engine** as Go functions called from the TCP forwarder. ProxyFlow opens **no listening socket**.
2. **Dial-before-accept:** for each new app connection the engine dials the proxy and completes the proxy handshake *before* completing the app's TCP handshake. If the dial fails, the app gets a RST and the failure is classified with a stable code. There is no fallback path in the code.
3. Adapters return typed errors (`adapters/errors.go`) split into proxy-level and target-level codes; the UI escalation rule is defined in `docs/m1/04-ux-screens-and-error-states.md` §4.

## Alternatives considered

- **Local SOCKS5 front-end on loopback** (needed for hev or other SOCKS5-only engines): rejected for the exposure above; authentication on the local port reduces but does not remove the risk (credentials live in the same process's memory and config).
- **Unix domain socket front-end** (e.g. in the app's private directory): avoids TCP port scans but still adds a second process boundary and protocol hop; unnecessary with an in-process engine.
- **Accept-then-dial** (complete the app handshake immediately, dial in the background): makes apps believe a connection exists when none does, delays failure until the first read, and complicates fail-closed reasoning. Rejected.

## Consequences

- No loopback attack surface; one code path for all protocols; precise per-connection error codes.
- Connection setup latency includes the proxy handshake (lab E01: a whole HTTP request took 27 ms on the virtual LAN; real proxies add their round-trip time to every new connection). Apps see a slower SYN-ACK rather than a fast SYN-ACK followed by a stall, which is the honest behaviour.
- Pending handshakes are bounded (`MaxInFlight` 512; tunnel set-up 15 s, with proxy connect and proxy handshake 10 s each). A proxy that stops answering therefore makes new app connections wait up to that long before they are refused; a proxy whose port is closed is refused at once (E10).

## Evidence

E01–E04 (all protocols through real servers), E10 (proxy port closed → RST in 0.011 s, no direct packets), E15 (hev would need a front-end for HTTP/TLS). M5 adds an instrumentation test asserting no listening sockets belong to the app.

## Implementation (M4)

`engine/adapters` (SOCKS5, HTTP CONNECT, TLS, categorised errors, credentials kept as wipeable bytes) and `engine/tunnel/tcp.go` (dial before the app's handshake completes; any failure answers the SYN with a RST). Cancellation: a tunnel being set up stops at once when the engine stops (`CANCELED`, never counted as a proxy failure). The engine opens no listening socket; the M5 instrumentation test for that remains planned.
