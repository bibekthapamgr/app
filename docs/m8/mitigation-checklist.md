# M8 — Mitigation checklist

**Milestone:** M8 · **Use:** before every release candidate (M10, M11), each line is re-checked against the build that ships; a line that no longer holds blocks the release ("Do not publish until critical security and routing tests pass"). IDs refer to `docs/m1/07-threat-model.md`. **Re-checked for M10's release candidate:** `docs/m10/mitigation-recheck.md` (lines added or changed there are marked M10).

Legend: ✅ in place and checked automatically on every CI run · 🔁 in place, re-checked by hand per release · ⏳ planned (milestone named) · ⚠️ accepted residual risk (threat model §5)

## Routing integrity (A4) and real IP (A3)

| | Item | How it is checked |
|---|---|---|
| ✅ | No connection to a destination except through the active profile's adapter; no fallback dial (§6 item 1) | Engine tests; device evidence: every line arrives once, through a proxy (`m5_evidence.py`, `m6_evidence.py`) |
| ✅ | While the VPN interface exists, every failure holds traffic: engine failure, proxy refusing or hanging, certificate change, reconnecting, offline, switch, restart (I-5) | Device tests M5–M7 with captures |
| ✅ | An Always-on VPN start holds traffic before the proxy check and retries with traffic held (I-5, ADR-0013 §3), until connected or blocked, also across a switch or a restart (M10) | `KillSwitchTest`, `ReconnectTest`; device scenario B |
| ✅ | A sign-in page on the phone's network releases nothing; its going only tries held traffic again (M10, ADR-0015 §3) | `ReconnectTest`; `ReconnectEndToEndTest.signingInToTheNetworkReconnectsAtOnce` |
| ✅ | Always-on VPN connects only the profile the user chose; none chosen → nothing connects, the user is told | `KillSwitchTest`, `HomeViewModelTest`, `HomeScreenTest` |
| ✅ | Process death while connected: measured on devices with and without Android's kill switch; found and explained on the next start (I-6) | `scripts/m8_failure_injection.sh` + `scripts/m8_evidence.py`, API 29/34/35/36, on every CI run (the verified run: `docs/m8/failure-injection.md` §3); Home's notice: `HomeScreenTest`, screenshot B2 |
| ✅ | The marker names the profile whose traffic the interface carries or holds, and since when — a switch writes the new profile's as it begins, a switch back the first one's afresh, a session's first connection its start; the interruption reaches Home even when Home begins to read just as it is written (ADR-0013 §4) | `KillSwitchTest`, `ProtectionStoreTest` (DataStore 1.3.0, and the store's own read again after each write) |
| ⚠️ | Without Android's kill switch, apps use the normal network when the VPN goes away (R1) | Told in E1/E1b, E2, B2, B6d and after an interruption |
| ✅ | DNS never leaves the phone except the proxy's own name (I-4) | `m6_evidence.py` |
| ✅ | UDP refused, ping unanswered, IPv6 handled (I-7, I-8, I-13) | Device tests M6; `TestPingIsNeverAnswered`, `TestEchoFilterBlocksOutboundReplies` |
| ⚠️ | Android system traffic and privileged apps may bypass any VPN (R5) | Named by owner in the M6 evidence |
| ⏳ | The same on physical phones (Wi-Fi ↔ mobile switch, OEM builds) | `docs/m10/physical-phone-checklist.md`, the owner's |

## Credentials (A1)

| | Item | How it is checked |
|---|---|---|
| ✅ | AES-256-GCM per credentials set, key in the Android Keystore, profile id as associated data, no plaintext fallback (I-2, T-4) | `core/data` JVM and device tests (M3) |
| ✅ | A lost or invalidated key reads as `CREDENTIAL_UNREADABLE` (H3), never as other credentials | `CredentialCipher` tests (M3); reviewed in M8 |
| ✅ | Backups and device transfers off; every storage domain excluded | `check_release_manifest.py` |
| ✅ | Credentials only as a byte array in the engine, zeroed on stop (I-12) | Engine test (M4) |
| ✅ | No credential in logcat, the diagnostic log, the export, an exception message or Android's crash report (I-3) | `LogSecret` and `ExceptionSecret` Lint checks; `LogsTest`, `LogRepositoryTest`, `CrashReportsTest`; device: M5's logcat search, M7's `LogsEndToEndTest`, M8's scenario C |
| ⚠️ | Android's runtime logs an uncaught exception's message to logcat before any app handler (R7) | Reported by scenario C |
| ⚠️ | Plaintext proxy profiles expose credentials on the network (R2) | Warning D4; owner decision D2 |

## Logs and diagnostics (A2, A5)

| | Item | How it is checked |
|---|---|---|
| ✅ | Typed entries, fixed keys, sanitized on write, read and export; bounded (5,000 entries; kept 1 h, 24 h or 7 days as the user chooses, 24 h by default, the choice applied before the first purge — M9, M10); Clear leaves nothing readable | M7 tests and device test; `LogBoundsTest` |
| ✅ | M10's entries (`sign_in_page`, `sign_in_page_gone`) carry the network's kind only | `ReconnectTest`, `LogsScreenTest` |
| ✅ | M8 entries (`session_interrupted`, `system_kill_switch`) carry keys, yes/no and durations only | `KillSwitchTest`, `LogsTest` |
| ✅ | `ApplicationExitInfo`'s description is never read | Code review (`AndroidProcessHistory`) |
| ✅ | No profile name on the lock screen (I-11): the status notification names none at all (M10), the tile none while the phone is locked (M9) | `VpnNotificationsTest`, `VpnEndToEndTest`; `QuickTileTest` |

## Manifest and platform (E-1)

| | Item | How it is checked |
|---|---|---|
| ✅ | Only the allow-listed permissions | `check_release_manifest.py`, `ManifestTest` |
| ✅ | VPN service not exported, `BIND_VPN_SERVICE`, `android.net.VpnService` only | `check_release_manifest.py`, `ManifestTest` |
| ✅ | Nothing exported without a permission but the launcher activity | `check_release_manifest.py` |
| ✅ | Not debuggable; no cleartext opt-in; network security config: no cleartext, system CAs only, no exceptions | `check_release_manifest.py` (`test_release_manifest.py`) |
| ✅ | What the release APK ships for the network security config and both backup rule files passes the same checks in every configuration it ships, read back strictly (a library's qualified variant would apply where it matches, the release build type's copy instead of main's) | `check_release_manifest.py` with `aapt2 dump resources` and `dump xmltree` (`test_release_manifest.py`) |
| ✅ | `PendingIntent`s immutable | Code review; Lint |
| ✅ | The notification's Disconnect, Cancel and Restart run only on an unlocked phone (M10): Android's flag from Android 12, a not-exported activity before it | `VpnNotificationsTest`, `VpnEndToEndTest` (Android 12+) |
| 🔁 | The tile asks to unlock first when tapped on a locked phone (M9) | By review per release, and on the phone checklist: no automated test reaches it (Robolectric's tile unlocks at once; the device test unlocks first) |
| ✅ | Taps through another app's window dropped on Disconnect, Stop, Delete, Discard, Clear (S4) | `ObscuredTouchesTest` |
| ✅ | Quick Settings tile exported only behind `BIND_QUICK_SETTINGS_TILE` (M9) | `check_release_manifest.py` (`check_tile_service`), `ManifestTest` |
| ✅ | What the engine's JNI layer finds by name survives R8 in the release APK (M10) | `check_release_manifest.py` (`check_engine_names`) |

## Availability and bounds (A6, D-1–D-4)

| | Item | How it is checked |
|---|---|---|
| ✅ | `MaxConns`, `MaxInFlight`, DNS slots, buffers, timeouts, event queue, name pool | Engine tests incl. M8's load tests (`docs/m8/security-review.md` §3.6) |
| ✅ | Malformed packets dropped, the engine goes on (T-3) | `TestMalformedPacketsAreDroppedAndTheEngineGoesOn`, `FuzzPacketLoop` in CI |
| ✅ | Parsers bounded and fuzzed (T-2) | Fuzz targets in CI |
| ✅ | Reconnects bounded (8, about 2 min), cancellable, one at a time; SOCKS5 unanswered rule needs 2+ sites; a sign-in page that comes and goes adds no attempts to a reconnection, and from a block each time it goes starts one round (M10-14) | `ReconnectTest`, `KillSwitchTest` |
| ⚠️ | A netstack bug could still end the engine; with Android's kill switch on, apps then stay offline until the user opens ProxyFlow (R4) | Told in E2 |
| ⏳ | Memory and battery on low-end physical phones (the emulators' baseline: `docs/m10/performance-baseline.md`) | `docs/m10/physical-phone-checklist.md`, the owner's |

## Supply chain (A7, E-3)

| | Item | How it is checked |
|---|---|---|
| ✅ | Go modules verified against the checksum database; govulncheck before the AAR | Engine job |
| ✅ | Every release library checked against OSV; HIGH, CRITICAL or unrated fails unless allow-listed with a reason and review date | `scripts/osv_scan.py` in the build job |
| ✅ | Gradle wrapper validated before any Gradle step; actions pinned by commit | Workflow |
| ✅ | No key, signing file or credential in the files or history | `scripts/check-secrets.sh`, `secrets.yml` |
| ⏳ | Play App Signing, signed release build | M11 |

## Claims (copy)

| | Item | How it is checked |
|---|---|---|
| ✅ | No "all traffic", "every app", "full VPN", "anonymous", "protection", "100 %"… in any string | `CopyClaimsTest` |
| ✅ | Android's kill switch never presented as guaranteed by an app toggle: no switch, status "as Android said", "ProxyFlow can't turn this on for you" | `SettingsScreenTest`, `HomeScreenTest`, screenshots E1, E1b, E2 |
| ✅ | No line states an older reading of Android's kill switch as current: Home and Settings say when Android said it; Home, where ProxyFlow isn't connected, and the active profile's delete confirmation, for after the connection ends, say apps "use your normal network unless Android's system kill switch is on" (F12) | `HomeScreenTest`, `SettingsScreenTest`, `ProfilesScreenTest`, screenshots B2, B6b, E1, E1b |
| 🔁 | The disconnected notification says the same | Read per release (`core/vpn` strings; no test checks its wording — `CopyClaimsTest` reads it only for forbidden claims) |
| ✅ | The limitations the user must be told are stated (About, routing doc) | `CopyClaimsTest.theLimitationsAreStated` |
| 🔁 | The store listing and support texts say no more than `docs/m6/routing-and-limitations.md` | M11 review |
