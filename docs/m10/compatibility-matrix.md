# M10 — Device and OS compatibility matrix

**Milestone:** M10 · **Verified commit:** `d48f4f8` · **CI:** run 42344467318, attempts 1–3 · **Decisions:** ADR-0015 §1

ProxyFlow declares Android 10 to 16 (minSdk 29, targetSdk 36). Every one of those versions runs the whole device suite in CI, on an emulator, in each attempt of the verified run. Physical phones are the owner's to check (`docs/m10/physical-phone-checklist.md`); the wider range of real devices is M11's closed testing.

## 1. Every declared Android version, in CI

All are Google's `google_apis` x86_64 system images, booted fresh for each job with `-no-snapshot`, on GitHub's `ubuntu-24.04` runners, against the lab of `scripts/e2e_env.sh`. The uplink is chosen per image by `scripts/e2e_device.sh`: mobile data, except Wi-Fi in airplane mode on API 34 and 35, and on API 33 when tcpdump can be run inside the phone as root, through `su` — their mobile data drops during runs (M10-4, M10-88, M10-113, M10-120). The captures are the emulator's own of its mobile data (`-tcpdump`, which from API 30 on does not record Wi-Fi: M10-89), and tcpdump inside the phone on every uplink interface: from API 34, where `adb root` survives, as root through adbd; on API 33 as root through `su`, adbd left as the shell user. The phone's captures are pulled by the shell user (M10-121).

| Android (API) | Uplink | Device tests: run, of them skipped | Failed | M5–M8 evidence, M10 performance and scenarios | Job |
|---|---|---:|---:|---|---|
| 10 (29) | mobile data | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |
| 11 (30) | mobile data | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |
| 12 (31) | mobile data | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |
| 12L (32) | mobile data | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |
| 13 (33) | Wi-Fi | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |
| 14 (34) | mobile data and Wi-Fi, both up | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |
| 15 (35) | Wi-Fi | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |
| 16 (36) | mobile data | 75, 6 | 0 | M10 performance: ✓; M10 scenarios: a restart with Always-on VPN: ✓; M10 scenarios: no notification permission: ✓; M5 VPN lifecycle: ✓; M5 end-to-end evidence: ✓; M6 end-to-end evidence: ✓; M7 evidence: ✓; M8 evidence: ✓ | success |

The counts are the first attempt's; the second and third attempts ran the same suite on each level with the same result. The 6 "skipped" are the same on every level: the five steps of `KillSwitchEndToEndTest` and `NotificationPermissionEndToEndTest`, which the plain test run leaves to the scripts that drive them (M8's failure injection and scenario N) and which ran there, counted under the evidence. Scenario N's ✓ on API 29 to 32 is the step saying it does not apply there (Android asks for no notification permission before 13); it tests the permission on API 33 to 36. The job column is the attempt's conclusion.

## 2. What differs between the versions, and how it is covered

| Android behaviour | Versions | What ProxyFlow does | Covered by |
|---|---|---|---|
| The notification permission (`POST_NOTIFICATIONS`) | 13+ (API 33–36) | Asked on onboarding's A3 and from Settings, never needed to connect; the VPN's foreground service runs without it | `OnboardingFlowTest`; `NotificationPermissionEndToEndTest` (scenario N, API 33–36) |
| A notification action's own unlock requirement (`setAuthenticationRequired`) | 12+ (API 31–36) | Disconnect, Cancel and Restart carry it; on Android 10 and 11 they go through an activity, which Android starts only unlocked (M10-17) | `VpnNotificationsTest` (Robolectric, both ways); `VpnEndToEndTest` on API 31–36 |
| Foreground service types | 14+ (API 34–36) | The VPN service is `systemExempted` (`FOREGROUND_SERVICE_SYSTEM_EXEMPTED`), the type Android gives VPN apps | Every device test that connects, on API 34–36; `scripts/check_release_manifest.py` |
| Why a process ended (`ApplicationExitInfo`) | 11+ (API 30–36) | Home names Android's reason for an interrupted connection; on Android 10 it says it can't tell | M8's failure injection, every level |
| Whether a VPN is bypassable, in its capabilities | 13+ | Read for the evidence: Android routes every uid but ProxyFlow's into the VPN, `bypassable=false` | M6 evidence, every level |
| App Standby's restricted bucket | 11+ (rare before) | ProxyFlow stays on the network, ranked as its own foreground service | `scripts/m10_scenarios.sh restart`, P (the bucket read back: restricted, 45, from API 31; on API 30 rare, 40, as Android 11 gives while its restricted bucket is off, its default (M10-52), or restricted where it is on; rare, 40, on API 29), every level |
| Doze, Battery Saver | all | The same | `scripts/m10_scenarios.sh restart`, P, every level, after R: without instrumentation, ProxyFlow's process ranked as a started foreground service — Android's own binding of a VPN service may let it past these modes by itself, so the rank is what ties the result to ProxyFlow (M10-59) |
| Always-on VPN and "Block connections without VPN" | all | Shown as Android last said; Android's start connects the chosen profile, holding traffic first; after a restart too | M8's scenario B; M10's scenario R (a restart), every level |
| The Quick Settings tile, added and tapped through Android's own system UI | all | — | `QuickTileEndToEndTest`, every level (the test adds the tile with `cmd statusbar add-tile` and would skip a version without it; none is) |
| Edge-to-edge drawing | enforced from 15 (targetSdk 35+) | Every screen draws edge to edge and pads for the system bars since M2 | The screenshot tests (Robolectric, API 35), the device tests' screens on every level |
| The predictive back gesture | 16 (targetSdk 36) | Back goes through AndroidX's back dispatcher (Navigation, Compose's `BackHandler`), which predictive back drives the same way | `NavigationTest`, `AddEditProxyScreenTest`, `OnboardingTest` (Robolectric) |

## 3. Physical phones

None yet: this workspace has no phone, and every run above is on emulators. The owner's phone, with their own proxy, runs `docs/m10/physical-phone-checklist.md`: the lab checks E01 and E07–E10 with a capture at a laptop's hotspot (M1's condition 1), the battery and throughput bars, a real move between Wi-Fi and mobile data, a network with a sign-in page, Private DNS strict, a restart with Always-on VPN, and the phone maker's own battery management overnight.

| Phone | Android | Proxy | Result |
|---|---|---|---|
| (the owner's) | | | not yet run |

## 4. Representative devices for closed testing (M11)

What the real-device spread should cover, since emulators can't: one phone of each of the makers whose battery management stops background apps the most (Samsung One UI, Xiaomi HyperOS or MIUI, and one of OnePlus/Oppo/Realme's ColorOS), a Pixel on the newest Android, an Android Go or low-memory phone on Android 10 or 11 (the memory bar), and a 64-bit ARM phone on each of Android 10 and 14 (the engine ships for arm64-v8a, armeabi-v7a and x86_64, `engine/build-aar.sh`; the emulators run x86_64 only). Closed testing's crash and ANR reports give the crash-free bar (≥ 99.8 % of sessions, `docs/m1/01-scope.md` §7).
