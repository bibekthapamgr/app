# 01 — Scope (V1)

**Milestone:** M1 · **Status:** Draft for product-owner approval · **Owner decisions needed:** §6

## 1. Product in one paragraph

ProxyFlow is a free, original Android app that sends other apps' TCP traffic through a proxy server **the user already has and pays for** (HTTP CONNECT or SOCKS5, optionally wrapped in TLS). It uses Android's `VpnService` (no root), keeps everything on the phone (no account, no backend, no ads, no analytics), and **fails closed**: if the proxy stops working, traffic is blocked, never sent around the proxy. It is inspired by the category of apps such as Super Proxy but shares no code, branding, names, icons or assets with any of them.

## 2. Users and jobs to be done

| User | Job | What "done" looks like |
|---|---|---|
| Proxy customer (residential / ISP / datacenter proxy from a provider) | Route phone apps through the proxy they bought | Adds host, port, username, password once; taps Connect; sites see the proxy's IP |
| Privacy-minded user with their own server | Route traffic through their own SOCKS5/HTTP proxy, ideally over TLS | Uses TLS-to-proxy with a valid certificate; nothing in clear text on local Wi-Fi |
| QA / developer | Test an app from a proxy's location | Switches between saved profiles quickly; sees per-connection errors in Logs |

## 3. In scope for V1

| Area | V1 scope | Source of truth |
|---|---|---|
| Platform | Android phones and tablets; minSdk **29 recommended** (26 possible, see ADR-0007); targetSdk = latest required by Play at release (API 36 today) | ADR-0007 |
| Protocols | **SOCKS5** (RFC 1928) with no-auth or username/password (RFC 1929); **HTTP CONNECT** (RFC 9110 §9.3.6) with no-auth or Basic | `02-traffic-support-matrix.md`, ADR-0002 |
| TLS to proxy | Optional per profile: TLS 1.2+ to the proxy endpoint, strict certificate-chain and hostname validation against Android's **system** trust store, optional SNI override; no "trust anyway" button | ADR-0005 |
| Traffic | All IPv4 and IPv6 **TCP** from apps in the personal profile; hostnames resolved by the proxy (remote DNS via fake-IP); non-DNS UDP rejected and ping (ICMP echo) dropped inside the tunnel — never leaked, and ping never answered with a fake reply | `02-traffic-support-matrix.md`, ADR-0003, ADR-0004 |
| Profiles | Multiple saved profiles; add / edit / duplicate / delete; "Test proxy"; one active profile | `04-ux…`, M3 |
| Credentials | Username/password stored encrypted with an Android-Keystore-backed key; never logged; never in backups | ADR-0006 |
| Connection | Connect / disconnect; VPN permission flow; foreground notification; automatic reconnect on network change and proxy outage with back-off | `03-user-journeys.md` |
| Kill switch | In-app fail-closed always on while connected; guided setup of Android's Always-on VPN + "Block connections without VPN" for protection when the app is not running | ADR-0004, `07-threat-model.md` |
| Logs | Local event log (connect, disconnect, errors, blocked UDP counts); 24 h retention default; destinations **off by default**; copy redacted diagnostics | `04-ux…` |
| Statistics | Bytes up/down, active connections, session duration | `04-ux…` |
| UI | Jetpack Compose + Material 3, light and dark themes, WCAG 2.2 AA contrast, TalkBack, 200 % font scale; English source strings, translation-ready | `05-design-tokens.md` |
| Distribution | Google Play (subject to the VpnService policy decision in ADR-0005); free; no in-app purchases | `10-platform-and-policy-constraints.md` |

## 4. Out of scope for V1 (explicit)

| Not in V1 | Why / where it might go |
|---|---|
| UDP proxying (SOCKS5 `UDP ASSOCIATE`, QUIC, WebRTC, games, VoIP media) | Most HTTP/SOCKS proxies sold to consumers do not support it; HTTP CONNECT cannot carry it. Blocked with a fast ICMP error so apps fall back to TCP (ADR-0004). Candidate for V2 behind a per-profile capability test. |
| Ping and traceroute (ICMP) | ICMP cannot be carried by HTTP CONNECT or SOCKS5. Ping is never answered with a fake reply, so it shows 100 % loss. Traceroute is not usable while connected: ICMP probes get no answer, and UDP probes are rejected by ProxyFlow itself, so the destination appears one hop away (`02-traffic-support-matrix.md` §2). |
| Per-app routing (include/exclude apps) | Adds a bypass surface; V1.1 candidate with explicit, visible UI. |
| LAN access while connected (printers, casting, NAS) | Would be a bypass of the proxy. V1 documents "local devices not reachable"; V1.1 candidate "Allow local network" opt-in. |
| SOCKS4/4a, HTTP Digest/NTLM/Kerberos, proxy chaining, PAC/WPAD, rule-based routing | Rare in the target market or large scope; Digest-only proxies get a clear error (`AUTH_FAILED` explanation). |
| Self-signed / private-CA proxy certificates, certificate pinning | V1 accepts only certificates trusted by Android's system store; V1.1 candidate: pin a certificate fingerprint per profile. |
| Synthesised IPv6 DNS answers (AAAA) | V1 answers AAAA with "no data"; the proxy picks the address family (ADR-0003). |
| Accounts, sync, backend, subscriptions, ads, analytics, crash-reporting SDKs | Product decision: free and local-only. Crash diagnostics are local and user-initiated. |
| iOS, desktop, Android TV, Wear OS, Android Auto, work profiles, tethering clients | Platform scope. Tethered devices and work-profile apps are not routed (documented in the matrix). |
| Root-only features (iptables, transparent proxy) | V1 is non-root by design. |
| Importing profiles from other apps, QR codes, URLs | V1.1 candidate (must never import secrets silently). |

## 5. Constraints and assumptions

1. **Original work.** No Super Proxy (or other) source code, names, icons, screenshots, copy or assets. Name and icon must be checked for trademark conflicts before M11.
2. **User-supplied proxy.** ProxyFlow does not sell, recommend or operate proxies. The proxy operator can see destinations and any unencrypted payload; the UI says so (A2, G1).
3. **No silent bypass.** Traffic that cannot be carried is blocked and explained, never sent directly (brief §Security).
4. **No full implementation before the networking approach is approved** (M1 acceptance criterion). The spike code in `spike/` is a feasibility prototype, not production code.
5. **Google Play policy.** VpnService apps must encrypt device-to-tunnel-endpoint traffic (`10-platform-and-policy-constraints.md` §1). Plain SOCKS5/HTTP profiles conflict with that wording — owner decision D2.
6. **Honest claims.** Store listing and UI never claim "all device traffic"; they say "TCP traffic from your apps" and list what is blocked.

## 6. Decisions requested from the product owner

| # | Decision | Options | Recommendation |
|---|---|---|---|
| D1 | Networking approach | (a) gVisor netstack + in-process adapters (Go, Apache-2.0); (b) hev-socks5-tunnel + local front-end; (c) tun2proxy (Rust) + TLS extension | **(a)** — ADR-0001/0002, `09-feasibility-report.md` |
| D2 | Plaintext proxies in the Play build | (a) allowed with prominent warning; (b) TLS required in Play build, plaintext only in a non-Play build; (c) ask Play policy support first | **(c) then decide**; engineer a `requireTlsToProxy` build flag either way — ADR-0005 |
| D3 | minSdk | 26 (≈98 % of devices) vs 29 (≈91 %) | **29** — lockdown detection, connection-owner lookup, smaller test matrix — ADR-0007 |
| D4 | Credential encryption implementation | Keystore AES-GCM wrapper vs Tink AEAD | **Keystore AES-GCM** (no extra crypto dependency); Tink acceptable — ADR-0006 |
| D5 | DNS record types beyond A | Current: AAAA/HTTPS/SVCB → no data, others refused | Keep for V1; revisit if a target app breaks — ADR-0003 |
| D6 | "Test proxy" target | A neutral HTTPS endpoint fetched through the proxy (e.g. a `generate_204` style URL) vs handshake-only test | Handshake + one HTTPS request to a configurable, documented URL; disclosed in privacy policy |
| D7 | TLS default in the Add-proxy form | Off (matches most providers) vs On | **Off with the plaintext warning shown** unless D2 = (b) |
| D8 | App licence and first translations | Open-source (Apache-2.0/MIT/GPL) vs closed; Nepali, Japanese, others | Owner choice; note GPL engines (sing-tun) are excluded only if the app is not GPL |

## 7. V1 quality bars (targets to be verified on devices in M10)

These are proposed acceptance targets, not measured results. Spike numbers come from a Linux VM and only show relative cost (`09-feasibility-report.md`).

| Metric | Target |
|---|---|
| Time to connected (warm, good network, proxy RTT < 150 ms) | ≤ 3 s at p90 |
| Fail-closed reaction when the proxy dies | New connections refused (RST) in < 1 s; no direct packet in packet capture |
| Throughput through a local test proxy on a mid-range phone | ≥ 100 Mbit/s down, CPU < 50 % of one core at 50 Mbit/s |
| Engine memory | < 50 MB RSS at 200 concurrent connections |
| Battery when idle-connected | < 2 % per hour above baseline (screen off, no traffic) |
| Crash-free sessions | ≥ 99.8 % in closed testing |
