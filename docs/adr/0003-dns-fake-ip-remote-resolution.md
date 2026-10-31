# ADR-0003 — DNS: fake-IP with resolution at the proxy

**Status:** Accepted 2026-10-31 (decision D5 delegated by the owner: the recommended record-type handling in point 4) · **Date:** 2026-10-30

## Context

Apps resolve hostnames before connecting. If those lookups go to the ISP's resolver, the ISP learns every destination (a DNS leak) and the user's location may be revealed. HTTP CONNECT and SOCKS5 can both carry a **hostname** to the proxy, which then resolves it — but by the time the engine sees a TCP SYN, only an IP address is left. Most consumer proxies do not support UDP, so DNS cannot simply be forwarded over the proxy.

## Decision

1. Route all DNS into the tunnel (`addDnsServer(198.18.0.2)` plus capture of UDP/53 to **any** address, since apps may hard-code resolvers).
2. Answer `A` queries locally with a **fake address** from `198.18.0.0/15` (RFC 2544 benchmarking range, not routable on the internet), TTL 5 s; the first 256 addresses are reserved (TUN `198.18.0.1`, DNS `198.18.0.2`).
3. When a TCP flow targets a fake address, map it back to the hostname and send **hostname:port** to the proxy (remote DNS). Unknown fake addresses are refused (`STALE_DNS_MAPPING`).
4. `AAAA`, `HTTPS` and `SVCB` → `NOERROR` with no records (apps use the A answer; the proxy picks the address family when it resolves). Other types → `REFUSED`. Malformed or injection-shaped names → `REFUSED`/`FORMERR`.
5. The proxy's **own** hostname is resolved on the underlying network by Android (`Network.getAllByName`), from the app that is excluded from the VPN — the only DNS that leaves the phone.
6. DNS over TCP, DoT and DoH are ordinary TCP flows and are proxied.

## Alternatives considered

- **Forward DNS through the proxy over TCP** (DNS-over-TCP to a public resolver via CONNECT/SOCKS5): leaks the resolver choice to the proxy anyway, adds a round trip per lookup, fails on proxies that block port 53, and apps would connect to real IPs, so the proxy would see IPs instead of names (breaks geo-routing proxies that resolve close to the exit).
- **Resolve on the phone via the underlying network**: a DNS leak. Rejected by the brief ("do not … fall back to direct DNS when the proxy fails").
- **Synthesize fake IPv6 (AAAA) as well**: possible later; adds a second pool and complexity, and the proxy would still receive a hostname. Not needed for V1.

## Consequences

- No DNS leaves the phone except the proxy's own hostname (E07, E16). The proxy sees hostnames, so provider-side geo-resolution works.
- Apps that need `TXT`/`MX`/`SRV` over UDP (rare in consumer apps) will fail while connected; tracked via the `refused` DNS counter in Logs.
- Fake addresses can outlive a session in app caches; the 5 s TTL and `STALE_DNS_MAPPING` handling make apps re-resolve. Production adds a minimum reuse delay before an address is recycled (threat model D-2).
- Android Private DNS in strict mode: queries go to the configured DoT server through the tunnel as TCP; apps then connect by IP, which works but means the proxy sees IPs. Confirmed on devices in M6.

## Evidence

E07 (A mapped, TXT refused, queries to 8.8.8.8 and to the underlay resolver intercepted, zero port-53 packets on the uplink), E16 (only proxy hostnames reached the underlying resolver; unknown proxy host → `PROXY_DNS_FAILED`), unit tests `TestResponder*`, `TestPool*`, fuzz target `FuzzResponder`.

## Implementation (M4)

`engine/fakedns` and `engine/tunnel/udp.go` (UDP/53 to any address answered locally; at most 256 DNS sockets at once, extra ones dropped and counted). The proxy's own hostname is resolved through the app's `Platform.ResolveProxyHost` callback (`Network.getAllByName` on the underlying network); the engine has no other resolver in production.
