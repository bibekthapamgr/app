# ADR-0007 — Platform baseline: SDK levels, service type, modules, engine packaging

**Status:** Proposed (owner decision D3 on minSdk) · **Date:** 2026-10-30

## Decision

| Item | Decision | Reason |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 | Brief |
| minSdk | **29 (Android 10)** recommended; 26 possible | 29 gives `isAlwaysOn()`/`isLockdownEnabled()` for a truthful kill-switch status and `getConnectionOwnerUid()` for per-app attribution; covers ≈ 90.8 % of active devices (Dec 2025 data) vs ≈ 98.4 % for 26 (`10-platform…` §6) |
| targetSdk / compileSdk | 36 now; raise to the Play requirement in force at M11 | Play target-API rule (API 36 since 31 Aug 2026) |
| VPN service | `ProxyFlowVpnService : VpnService`, foreground, `foregroundServiceType="systemExempted"` + `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`; `specialUse` as a verified fallback | Android 14 FGS-type rules list VPN apps configured in VPN settings as eligible for `systemExempted` |
| Permissions | `BIND_VPN_SERVICE` (service), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE` | Minimal set; no location, no `QUERY_ALL_PACKAGES` in V1 |
| Modules | `:app`, `:feature:*`, `:core:ui`, `:core:model`, `:core:data`, `:vpn`, `:engine-api`, `:engine` | Brief: separate UI, data, VPN service, packet engine, protocol adapters (`06-architecture.md` §2) |
| Engine packaging | Go module built with `gomobile bind` into an AAR (ABIs: arm64-v8a, armeabi-v7a, x86_64); Go and NDK versions pinned | One `.so` per ABI; App Bundles deliver one ABI per device |
| Build | Gradle (Kotlin DSL) with version catalog, Java 17 toolchain, R8 in release, dependency verification metadata | Reproducibility and supply-chain checks |
| Tests | JUnit + Turbine (unit), Robolectric (Android-light), instrumentation on emulator and devices; the Linux lab (E-suite) stays in CI for engine routing; on-device packet-capture suite from M5 | "A build that merely compiles … does not prove proxy routing works" |

## Alternatives considered

- **minSdk 26:** +7.6 % reach; kill-switch status must show "unknown" on Android 8–9, Logs cannot attribute connections to apps there, and two more major versions need device testing.
- **`specialUse` FGS type first:** requires a Play justification and is less specific; used only if `systemExempted` is rejected on some devices.
- **c-shared library + hand-written JNI instead of gomobile:** more control over the ABI, more glue code; reconsider if gomobile's binding limits (types, callbacks) become a problem in M4.

## Open verification items

1. `systemExempted` accepted on Android 14, 15, 16 right after VPN consent and on Always-on start (M5).
2. gomobile AAR size and startup time on a low-end device (M4/M10).
3. Behaviour of `setMtu(9000)` across OEM kernels (M10), fallback 1500.
