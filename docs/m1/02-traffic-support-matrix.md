# 02 — Protocol and traffic support matrix

**Milestone:** M1 · **Status:** Draft for approval · **Evidence run:** `spike/evidence/run-m1-20261030T003301Z/` (E-numbers below refer to its tests; produced by the code now in the repository)

ProxyFlow V1 carries **TCP**. Everything else is either answered locally (DNS) or blocked. Nothing is ever sent around the proxy. This page is the single source for what the UI, the store listing and the support docs may claim.

> **M6 update (2026-11-01):** the rows marked "Device check (M6)" were verified on emulators (API 29, 34, 35, 36) with packet captures and controlled DNS and proxy servers; DNS over TCP port 53 is now answered locally (ADR-0011). The current matrix, with the device evidence, is `docs/m6/routing-and-limitations.md`; where the two differ, it wins.

**Status key**
- **Verified (lab)** — passed end-to-end in the Linux lab with real proxies and packet captures (TUN → engine → proxy → destination).
- **Verified (unit)** — passed hermetic Go tests against the engine, not yet end-to-end.
- **Device check** — behaviour depends on Android and must be confirmed on phones with packet capture (M5/M6/M10).
- **Not supported** — blocked by design in V1.

## 1. Proxy protocols

| Proxy type | Auth | Transport to proxy | V1 | Status / evidence |
|---|---|---|---|---|
| SOCKS5 (RFC 1928), `CONNECT` command | none | TCP | Yes | Verified (lab) E01 |
| SOCKS5 | username/password (RFC 1929) | TCP | Yes | Verified (lab) E02 against Dante 1.4.3; wrong password → `AUTH_FAILED`, no destination contact |
| SOCKS5 | GSSAPI or other methods | — | No | A server that accepts none of the offered methods (`0xFF`) → `AUTH_REQUIRED` when no credentials are set (unit: `TestSOCKS5AuthFailures`), `AUTH_FAILED` otherwise; credentials are never sent unless the server selected username/password |
| SOCKS5 `UDP ASSOCIATE` / `BIND` | — | — | No | Not requested by ProxyFlow |
| HTTP `CONNECT` (RFC 9110 §9.3.6) | none | TCP | Yes | Verified (unit) `TestHTTPConnectIPv6LiteralAndNoCredentials`; every HTTP proxy in the lab requires sign-in, so the lab covers the Basic row below |
| HTTP `CONNECT` | Basic (preemptive) | TCP | Yes | Verified (lab) E03 against Squid 6.14 (HTTPS and HTTP through the tunnel); 407 → `AUTH_FAILED` |
| HTTP `CONNECT` | Digest / NTLM / Negotiate | — | No | Explained to the user (unit: `TestHTTPConnectDigestOnlyIsExplained`) |
| Plain HTTP forward proxy (`GET http://…`) | — | — | No | Not needed: all TCP uses CONNECT |
| SOCKS5 or HTTP **over TLS** ("TLS to proxy") | as above | TLS 1.2+ | Yes, optional | Verified (lab) E04: valid chain + hostname pass; expired, wrong host, unknown issuer, plaintext port all fail closed |
| SOCKS4 / SOCKS4a | — | — | No | Out of scope |

What an observer between the phone and the proxy can see (E04 vs E05):

| Profile | Proxy password | Destination hostname | Payload of non-HTTPS apps | Proxy name (SNI) |
|---|---|---|---|---|
| SOCKS5 plain | **visible** (E05) | **visible** (E05) | **visible** (E05) | n/a |
| HTTP plain | **visible**, Base64 `Proxy-Authorization` (E05) | **visible**, CONNECT line (E05) | **visible** (not captured separately: the tunnel carries the app's bytes unchanged) | n/a |
| HTTP + TLS with sign-in | hidden (E04 valid-http-tls-auth) | hidden (E04) | hidden (E04) | visible by design |
| SOCKS5 + TLS | hidden (inferred: the whole SOCKS5 exchange is inside TLS; the lab case used no credentials) | hidden (E04 valid-socks5-tls) | hidden (E04) | visible by design |

## 2. Traffic from apps (while connected)

| Traffic | What ProxyFlow does | What the user notices | Status / evidence |
|---|---|---|---|
| TCP to a hostname (normal app traffic) | App's DNS lookup gets a fake IPv4 address from 198.18.0.0/15; the TCP flow to it is sent to the proxy as **hostname:port** (proxy resolves) | Works; sites see the proxy's IP | Verified (lab) E01–E04, E16 |
| TCP to an IPv4 literal | Sent to the proxy as **address:port** | Works | Verified (lab) E11-A (IP-literal request through the proxy); unit `TestIPLiteralAndStaleMapping` |
| TCP to an IPv6 literal | Sent to the proxy as IPv6 **address:port** (SOCKS5 ATYP 4 / `CONNECT [addr]:port`) | Works only if the proxy has IPv6 | Verified (unit) `TestIPv6TCPIsForwardedByAddress`, `TestHTTPConnectIPv6LiteralAndNoCredentials`; **verified on devices (M6)** `ipv6AddressesGoThroughTheProxy`, `ipv6OnlyNamesAreReachedThroughTheProxy` |
| TCP to a fake address from a previous session | Refused (RST), logged `STALE_DNS_MAPPING` | App retries and re-resolves | Verified (unit) |
| DNS over UDP/53 to **any** resolver (system, 8.8.8.8, router) | Intercepted inside the tunnel and answered locally; never leaves the phone | Works | Verified (lab) E07: zero UDP and zero port-53 packets on the uplink |
| DNS `A` | Fake IPv4 (TTL 5 s in the spike) | — | Verified (lab) E07 |
| DNS `AAAA`, `HTTPS`, `SVCB` | `NOERROR` with no records | Apps use the A answer; the proxy chooses IPv4/IPv6 for the hostname | Verified (unit) `TestResponder` |
| Other DNS types (TXT, MX, SRV, …) | `REFUSED` | Rare app features that need them fail | Verified (lab) E07 (TXT) |
| Malformed or injection-shaped names (spaces, CR/LF, `:`) | `REFUSED` / `FORMERR` | — | Verified (unit) + fuzzing `FuzzResponder` |
| DNS over TCP/53 | **Since M6 (ADR-0011):** answered locally like UDP/53, to any address (before: proxied as ordinary TCP) | Works, whatever the proxy allows | Verified (unit) `TestDNSOverTCPIsAnsweredLocally`; on devices (M6) |
| DNS over TLS / HTTPS chosen by an app (DoH, DoT) | Ordinary TCP → proxied; the app then connects to IP literals, proxied by address | Works; proxy sees IPs instead of names | Follows from TCP rows; Android Private DNS strict mode not automated (needs a DoT server trusted by the system store; `docs/m6/routing-and-limitations.md` §4); "Automatic" (the default) runs in every device test |
| UDP, any other port (QUIC/HTTP-3, WebRTC, VoIP, games, NTP, STUN) | Rejected inside the tunnel with ICMP port-unreachable; counted in Logs | Browsers fall back to TCP; calls/games may fail or use TCP relays | Verified (lab) E08 (incl. UDP/443): zero UDP on the uplink |
| IPv6 UDP (non-DNS) | Rejected as above | — | Verified (unit) `TestIPv6UDPRejectedAndDNSAnswered` |
| ICMP and ICMPv6 echo (ping), any size | Echo requests, and every fragment of an ICMP packet, are dropped before the network stack (IPv6 extension headers are walked); any echo reply or ICMP fragment the stack would still produce is discarded on the way out. **Never** answered with a fake reply | Ping shows 100 % loss | Verified (lab) E09: kernel pings (small and 2,000-byte fragmented) and raw IPv4/IPv6 echo requests injected at the TUN (small, 2 KB fragmented, behind an extension header, atomic fragment) all got no reply; 12 requests and 4 later fragments dropped; unit `TestPingIsNeverAnswered`, `TestEchoFilterBlocksOutboundReplies`. *Fixed in M1 in two steps: the first engine answered IPv6 pings for any address, and the first fix still answered large fragmented ones (found by the two independent checks).* |
| Traceroute | ICMP-based: no answers. UDP-based: the first probe is rejected by ProxyFlow itself with port-unreachable, so the destination appears one hop away — a known artefact of the UDP policy | Do not use traceroute for diagnosis while connected (help text) | Follows from the UDP and ICMP rows |
| LAN unicast (192.168.x.x printers, Chromecast, NAS) | Captured like any other address and sent to the proxy, which cannot reach the user's LAN | "Local devices not reachable" (A2, G1) | **Verified on devices (M6)** `lanAddressesGoThroughTheProxyToo` |
| Multicast / broadcast discovery (mDNS, SSDP) | Not carried (UDP is refused) | Discovery of local devices does not work | Follows from the UDP rows (unicast UDP refused on devices in M6, `udpIsRefusedOnThePhoneAndNeverSent`; multicast and broadcast not sent separately) |

## 3. Traffic that is *not* captured (and must not be claimed)

| Traffic | Why | Handling |
|---|---|---|
| ProxyFlow's own connections to the proxy, and the DNS lookup of the proxy's hostname | The app is excluded from its own VPN (`addDisallowedApplication(self)`), otherwise it would loop | Only the proxy endpoint and its name lookup leave the phone directly. Verified (lab) E16: the underlying resolver saw only proxy hostnames |
| Android system traffic that runs outside VPNs (e.g. network validation/captive-portal probes, carrier IMS/VoLTE, some system services, privileged apps such as Google Play services) | Platform behaviour | Documented; M6: the evidence checks the connected periods: each connection or lookup that left the phone around the VPN is put down to its socket's owner where the phone's socket samples caught it (TCP, and connected UDP), and otherwise to apps Android lets leave a VPN only when Android's own view of the VPN routed every uid but ProxyFlow's into it (`docs/m6/routing-and-limitations.md` §4, `docs/m6/packet-capture-report.md`) |
| Tethering / hotspot clients | Android does not route tethered traffic through `VpnService` by default | Documented as not covered |
| Work profile and other Android users | A `VpnService` applies to the user/profile that started it | Documented as not covered |
| Apps the user excludes | Not in V1 (no per-app routing) | — |

The app never calls `allowBypass()`, so other apps cannot opt out of the VPN by binding to the underlying network.

## 4. Failure behaviour (fail closed)

| Situation | Result | Status / evidence |
|---|---|---|
| Proxy refuses connections (port closed, proxy process down) | New app connections get an immediate RST (0.011 s in E10); no direct packets; recovers automatically when the proxy returns | Verified (lab) E10 |
| Proxy silent (host down, packets dropped) | New app connections wait until the connect to the proxy times out (10 s in the spike; tunnel set-up bounded at 15 s), then get a RST; no direct packets | Verified (unit) `TestProxyUnreachableAndTimeout` (with a short timeout); device check M5 |
| Proxy auth fails | Connection refused with `AUTH_FAILED`; no destination contact; traffic stays blocked (how Home shows it is designed in B6a, not part of the lab) | Verified (lab) E02, E03 |
| TLS validation fails (expired, wrong name, untrusted, not TLS) | Connection refused with a specific code; no fallback to plaintext | Verified (lab) E04 |
| Proxy refuses one destination (policy) | That connection fails (`TARGET_NOT_ALLOWED`); tunnel stays up for everything else | Verified (lab) E03 |
| Engine crashes but the VPN interface stays up | Traffic black-holed (blocked) | Verified (lab) E11-A |
| VPN interface torn down **without** OS lockdown | Traffic **goes direct** (leak) — this is Android's normal behaviour after a VPN stops | Verified (lab) E11-B (negative control) |
| VPN interface torn down **with** OS lockdown (Always-on + Block connections without VPN) | Blocked | Verified (lab, emulated) E11-C; **Device check** M8 |
| User disconnects | VPN removed; apps use the normal network (stated on the Disconnect button and notification) | By design |

## 5. Claims the product may and may not make

May say: "Routes your apps' TCP traffic through your HTTP or SOCKS5 proxy", "DNS lookups are resolved by your proxy" (only the proxy's own address is looked up on your network), "If your proxy stops working, ProxyFlow blocks traffic instead of sending it around the proxy", "UDP, ping and local devices are blocked while connected".

Must not say: "all device traffic", "full VPN", "anonymous", "encrypted" (unless the profile uses TLS — and then only "encrypted between your phone and your proxy"), "supports games/calls/QUIC".

M6 (ADR-0011): the TLS setting is always "TLS to proxy", and wherever it is explained the text adds that HTTPS sites stay encrypted end to end and that ProxyFlow never decrypts them; no "protection" wording. `CopyClaimsTest` checks every string of every module and language against these rules.
