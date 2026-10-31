# ADR-0004 — Non-TCP traffic policy and fail-closed semantics

**Status:** Proposed · **Date:** 2026-10-30

## Context

HTTP CONNECT carries only TCP. SOCKS5 defines `UDP ASSOCIATE`, but most consumer proxies do not support it and it cannot be relied on. ICMP cannot be carried by either. The brief requires explicit UDP, DNS, ICMP and IPv6 behaviour, no silent bypass, and verification with packet captures.

## Decision

| Traffic | Behaviour |
|---|---|
| TCP over IPv4 and IPv6 | Proxied (hostname for fake-IP destinations, address otherwise) |
| UDP/53 | Answered by the fake-IP DNS responder (ADR-0003) |
| Other UDP (v4 and v6) | **Rejected inside the tunnel** with ICMP port-unreachable, so apps get `ECONNREFUSED` immediately and QUIC/HTTP-3 clients fall back to TCP; counted and shown in Logs ("36 UDP packets blocked"). A silent-drop mode exists for diagnosis only. |
| ICMP / ICMPv6 echo | Dropped. **Never answered with a fake reply**, so ping and diagnostics tools show the truth: the destination is not reachable through ProxyFlow. Not registering an ICMP transport is not enough — gVisor's IPv6 layer answers echo requests for any address by itself, also after reassembling a large, fragmented request — so an echo filter between the TUN and the stack drops echo requests and every fragment of an ICMP packet on the way in (walking IPv6 extension headers), and discards any echo reply or ICMP fragment on the way out. ProxyFlow's own ICMP output (port unreachable) is small and never fragmented. |
| IPv6 | Captured (`::/0`); TCP proxied by address (works if the proxy has IPv6); never leaks around the tunnel |
| Proxy failure | New flows refused with RST; existing flows end; nothing direct; automatic recovery |
| Engine crash, TUN still up | Black-holed (blocked) |
| VPN torn down (process death, revoked, disconnect) | Android sends traffic direct **unless** Always-on + "Block connections without VPN" is on → app detects (API 29+) and guides the user (E2) |

The in-app "kill switch" is therefore always on while connected (it is the engine's behaviour, not a setting), and the "system kill switch" is Android lockdown, which the app explains and monitors but cannot enable itself.

## Alternatives considered

- **Proxy UDP via SOCKS5 UDP ASSOCIATE when available:** valuable for calls and games, but needs per-profile capability detection, a second data path, and HTTP profiles cannot use it. Deferred to V2.
- **Silently drop UDP:** apps wait for timeouts (seconds) before falling back; worse UX. Kept only as a diagnostic option.
- **Answer pings locally:** some tunnel apps do this to make "connectivity checks" look green; it misleads users and diagnostics. Rejected.
- **Exclude LAN ranges from the VPN:** would bypass the proxy for local traffic; deferred to an explicit opt-in in V1.1.

## Consequences

- Video calls and games that require UDP may not work while connected; the onboarding (A2), Home protection summary (B4) and About (G1) say so.
- `UDP blocked` counters let support distinguish "app needs UDP" from proxy problems.
- Lockdown remains the only protection when ProxyFlow is not running — a residual risk (R1) stated in the threat model.

## Evidence

E08 (UDP and UDP/443 rejected, ICMP unreachable delivered, zero UDP on uplink), E09 (kernel pings, small and 2,000-byte fragmented, plus raw IPv4 and IPv6 echo requests — small, 2 KB fragmented, behind an extension header, atomic fragment: zero replies; zero ICMP on the uplink), unit `TestPingIsNeverAnswered` (fails without the filter), `TestEchoFilterBlocksOutboundReplies`, `TestClassifyICMP`, E10 (outage), E11 A/B/C (crash vs teardown with and without lockdown), unit `TestIPv6UDPRejectedAndDNSAnswered`, `TestIPv6TCPIsForwardedByAddress`.
