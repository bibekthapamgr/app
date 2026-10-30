# ProxyFlow for Android

ProxyFlow is a free, original Android app that routes other apps' TCP traffic through a proxy the user already has — HTTP CONNECT or SOCKS5, optionally over TLS — using Android's `VpnService`. It needs no account, has no backend, and **fails closed**: when the proxy stops working, traffic is blocked instead of being sent around the proxy.

**Status: Milestone 1 (requirements, feasibility, UI/UX) delivered, awaiting product-owner approval of the networking approach.** No production app code exists yet, by design: the brief requires approval of the networking approach first. Start with `docs/m1/M1-acceptance-report.md`.

## Repository

| Path | Contents |
|---|---|
| `docs/m1/` | M1 deliverables: scope, traffic support matrix, user journeys, UX and error states, design tokens, architecture, threat model, dependency review, feasibility report, platform/policy constraints, acceptance report |
| `docs/adr/` | Architecture decision records 0001–0007 |
| `docs/diagrams/` | Mermaid sources and rendered SVGs (architecture, packet path, connect sequence, threat-model DFD) |
| `design/` | Screen boards (design canvas files), design tokens, contrast report and checker |
| `spike/` | Feasibility spike: Go engine prototype (gVisor netstack), Python lab harness, end-to-end tests E01–E16, committed evidence runs |
| `.github/workflows/spike.yml` | CI for the spike, written but not run yet (Ubuntu 24.04 with IPv6; regenerates `go.sum` against the public checksum database; evidence upload) |

## Key results (M1)

- A TCP request from a TUN interface reached a destination through a controlled SOCKS5 proxy with the proxy resolving the hostname and nothing leaving the "phone" except to the proxy (E01).
- SOCKS5 + auth (Dante), HTTP CONNECT + auth (Squid) and TLS-to-proxy (stunnel, six validation cases) all work and fail closed.
- Apps' ordinary DNS lookups (UDP port 53) never leave the device; only the proxy's own hostname is looked up on the normal network, and DNS that apps send over TCP or HTTPS goes through the proxy. UDP and ping are blocked, not leaked or faked. When the proxy's port is closed, apps' connections are refused in about 11 ms, and traffic recovers automatically.
- 20/20 end-to-end tests (149 checks), 54/54 Go tests plus 20 race-detector repeats of the engine tests, fuzzing without crashes. Canonical evidence: `spike/evidence/run-m1-20261030T003301Z`, produced by the code in this repository; three earlier runs are kept as history.
- Two independent checks found real problems: the engine answered IPv6 pings by itself (first plain ones, then large fragmented ones), and two cleanup figures were mis-measured. All are fixed and covered by tests (feasibility report §5).
- IPv6 end to end and everything on a real Android device are still pending (feasibility report §6).

## Run the spike

```bash
cd spike
sudo -E HEV_TAG=2.19.3 FUZZTIME=30s GO_TEST_REPEAT=20 ./run_spike.sh   # Linux + root; package list in spike/README.md
```

## Decisions needed before M2

See `docs/m1/01-scope.md` §6: networking approach (D1), plaintext proxies in the Play build (D2), minSdk (D3), credential encryption implementation (D4), DNS record handling (D5), "Test proxy" target (D6), TLS default (D7), app licence and translations (D8).

## Licence

To be decided by the product owner (D8). Third-party components and their licences: `docs/m1/08-dependency-review.md`.
