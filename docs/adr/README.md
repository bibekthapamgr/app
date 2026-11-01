# Architecture decision records

Format: context → decision → alternatives → consequences → evidence. Status values: **Proposed** (awaiting product-owner approval), **Accepted**, **Superseded by ADR-nnnn**.

M1 rule: "No full implementation begins until the networking approach is approved." ADR-0001 to ADR-0004 together are the networking approach; the owner delegated that decision to the tech lead ("Timi nai decide gara"), who accepted them on 2026-10-31 before the M4 engine work.

| ADR | Title | Status | Owner decision |
|---|---|---|---|
| [0001](0001-tun-tcp-engine.md) | TUN/TCP engine: gVisor netstack | Accepted 2026-10-31 (decision delegated by the owner; implemented in M4) | D1 |
| [0002](0002-in-process-protocol-adapters.md) | In-process protocol adapters, dial-before-accept, no local listener | Accepted 2026-10-31 (decision delegated by the owner; implemented in M4) | D1 |
| [0003](0003-dns-fake-ip-remote-resolution.md) | DNS: fake-IP with resolution at the proxy | Accepted 2026-10-31 (decision delegated by the owner; implemented in M4) | D5 |
| [0004](0004-non-tcp-traffic-and-fail-closed.md) | Non-TCP traffic policy and fail-closed semantics | Accepted 2026-10-31 (decision delegated by the owner; implemented in M4) | — |
| [0005](0005-tls-to-proxy-and-play-policy.md) | TLS to proxy, trust anchors and the Play encryption requirement | Technical decisions accepted 2026-10-31 (delegated; implemented in M4); D2 and D7 open for the owner | D2, D7 |
| [0006](0006-credential-storage.md) | Credential storage with per-profile Android Keystore keys | Accepted 2026-10-31 (decision delegated by the owner; revised in M3: one key per profile) | D4 |
| [0007](0007-platform-baseline.md) | Platform baseline: SDK levels, service type, modules, engine packaging | Proposed (minSdk 29 chosen by the owner) | D3 |
| [0008](0008-app-architecture.md) | App architecture: modules, MVVM, manual DI, navigation | Accepted 2026-10-31 (decision delegated by the owner) | — |
| [0009](0009-local-database.md) | Local storage: Room for profiles, exported schemas, tested migrations | Accepted 2026-10-31 (decision delegated by the owner) | — |
| [0010](0010-vpn-service-integration.md) | VPN service integration: connect sequence, session ownership, failure handling | Accepted 2026-10-31 (technical decisions delegated; implemented in M5); pre-flight target inside open D6 | D6 |

Template for new records: copy any ADR, keep the headings, add a row here.
