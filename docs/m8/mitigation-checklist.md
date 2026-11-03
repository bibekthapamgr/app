# M8 — Mitigation checklist

**Milestone:** M8 · **Use:** before every release candidate (M10, M11), each line is re-checked against the build that ships; a line that no longer holds blocks the release ("Do not publish until critical security and routing tests pass"). IDs refer to `docs/m1/07-threat-model.md`.

Legend: ✅ in place and checked automatically on every CI run · 🔁 in place, re-checked by hand per release · ⏳ planned (milestone named) · ⚠️ accepted residual risk (threat model §5)

## Routing integrity (A4) and real IP (A3)

| | Item | How it is checked |
|---|---|---|
| ✅ | No connection to a destination except through the active profile's adapter; no fallback dial (§6 item 1) | Engine tests; device evidence: every line arrives once, through a proxy (`m5_evidence.py`, `m6_evidence.py`) |
| ✅ | While the VPN interface exists, every failure holds traffic: engine failure, proxy refusing or hanging, certificate change, reconnecting, offline, switch, restart (I-5) | Device tests M5–M7 with captures |
| ✅ | An Always-on VPN start holds traffic before the proxy check and retries with traffic held (I-5, ADR-0013 §3) | `KillSwitchTest`; device scenario B |
| ✅ | Always-on VPN connects only the profile the user chose; none chosen → nothing connects, the user is told | `KillSwitchTest`, `HomeViewModelTest`, `HomeScreenTest` |
| ⏳ | Process death while connected: measured on devices with and without Android's kill switch; found and explained on the next start (I-6) | `scripts/m8_failure_injection.sh` + `scripts/m8_evidence.py`, API 29/34/35/36, on every CI run; ✅ once a run passes on all four (`docs/m8/failure-injection.md` §3) |
| ✅ | The marker names the profile whose traffic the interface carries or holds, and since when — a switch writes the new profile's as it begins, a switch back the first one's afresh, a session's first connection its start; the interruption reaches Home even when Home begins to read just as it is written (ADR-0013 §4) | `KillSwitchTest`, `ProtectionStoreTest` (DataStore 1.3.0, and the store's own read again after each write) |
| ⚠️ | Without Android's kill switch, apps use the normal network when the VPN goes away (R1) | Told in E1/E1b, E2, B2, B6d and after an interruption |
| ✅ | DNS never leaves the phone except the proxy's own name (I-4) | `m6_evidence.py` |
| ✅ | UDP refused, ping unanswered, IPv6 handled (I-7, I-8, I-13) | Device tests M6; `TestPingIsNeverAnswered`, `TestEchoFilterBlocksOutboundReplies` |
| ⚠️ | Android system traffic and privileged apps may bypass any VPN (R5) | Named by owner in the M6 evidence |
| ⏳ | The same on physical phones (Wi-Fi ↔ mobile switch, OEM builds) | M10 device checklist |

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
| ✅ | Typed entries, fixed keys, sanitized on write, read and export; bounded (5,000 entries, 24 h); Clear leaves nothing readable | M7 tests and device test |
| ✅ | M8 entries (`session_interrupted`, `system_kill_switch`) carry keys, yes/no and durations only | `KillSwitchTest`, `LogsTest` |
| ✅ | `ApplicationExitInfo`'s description is never read | Code review (`AndroidProcessHistory`) |
| ✅ | No profile name on the lock screen (I-11) | M5 device test |

## Manifest and platform (E-1)

| | Item | How it is checked |
|---|---|---|
| ✅ | Only the allow-listed permissions | `check_release_manifest.py`, `ManifestTest` |
| ✅ | VPN service not exported, `BIND_VPN_SERVICE`, `android.net.VpnService` only | `check_release_manifest.py`, `ManifestTest` |
| ✅ | Nothing exported without a permission but the launcher activity | `check_release_manifest.py` |
| ✅ | Not debuggable; no cleartext opt-in; network security config: no cleartext, system CAs only, no exceptions | `check_release_manifest.py` (`test_release_manifest.py`) |
| ✅ | What the release APK ships for the network security config and both backup rule files passes the same checks in every configuration it ships (a library's qualified variant would apply where it matches, the release build type's copy instead of main's) | `check_release_manifest.py` with `aapt2 dump resources` and `dump xmltree` (`test_release_manifest.py`) |
| ✅ | `PendingIntent`s immutable | Code review; Lint |
| ✅ | Taps through another app's window dropped on Disconnect, Stop, Delete, Discard, Clear (S4) | `ObscuredTouchesTest` |
| ⏳ | Quick Settings tile exported only behind `BIND_QUICK_SETTINGS_TILE` | M9 |

## Availability and bounds (A6, D-1–D-4)

| | Item | How it is checked |
|---|---|---|
| ✅ | `MaxConns`, `MaxInFlight`, DNS slots, buffers, timeouts, event queue, name pool | Engine tests incl. M8's load tests (`docs/m8/security-review.md` §3.6) |
| ✅ | Malformed packets dropped, the engine goes on (T-3) | `TestMalformedPacketsAreDroppedAndTheEngineGoesOn`, `FuzzPacketLoop` in CI |
| ✅ | Parsers bounded and fuzzed (T-2) | Fuzz targets in CI |
| ✅ | Reconnects bounded (8, about 2 min), cancellable, one at a time; SOCKS5 unanswered rule needs 2+ sites | `ReconnectTest`, `KillSwitchTest` |
| ⚠️ | A netstack bug could still end the engine; with Android's kill switch on, apps then stay offline until the user opens ProxyFlow (R4) | Told in E2 |
| ⏳ | Memory and battery on low-end physical phones | M10 |

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
| ✅ | The limitations the user must be told are stated (About, routing doc) | `CopyClaimsTest.theLimitationsAreStated` |
| 🔁 | The store listing and support texts say no more than `docs/m6/routing-and-limitations.md` | M11 review |
