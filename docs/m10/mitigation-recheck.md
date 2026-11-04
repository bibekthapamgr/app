# M10 — Mitigation checklist re-checked for the release candidate

**Milestone:** M10 · **Release candidate:** `{SHA}` (version 0.10.0, code 10) · **CI:** run {RUN} · **Checklist:** `docs/m8/mitigation-checklist.md` ("before every release candidate, each line is re-checked against the build that ships; a line that no longer holds blocks the release") · **Re-checked by:** an independent review of the code at c28645f (review C), whose findings were fixed (M10-17 to M10-21), then each line again against the verified commit's CI run

## Verdict

Every line holds on the release candidate, or is a residual risk M8 accepted, or is the owner's phone checks (⏳). The review's first finding was that the candidate it was given did not build (M10-6): no Lint, no app tests and no device evidence existed for any commit after 93d91b9 until d345b94. Its second was a real gap since M5: the connection notification's Disconnect worked from a locked phone's shade, and the lock screen could show the profile's name (M10-17), now fixed. Nothing in the code had stopped holding of M8's mitigations.

## Line by line

| Checklist line | Verdict on the release candidate | Evidence |
|---|---|---|
| No path to a destination except the profile's adapter; no fallback | Holds | Engine job (race tests, fuzzing); device evidence on every level: every connection that reached the destination came through a proxy, every bulk connection was a proxy's tunnel (`docs/m10/compatibility-matrix.md` §3). The engine changed only by its stats fields; Test connection's probe tunnels through the tested proxy only |
| Every failure holds traffic while the interface exists (I-5) | Holds | `ReconnectTest`, `KillSwitchTest`, `VpnSessionTest`; device captures of every level. The sign-in network handling only restarts held attempts (`afterTheSignInPage`); the tile's Disconnect and the page going while traffic is held release nothing (`ReconnectTest.theTilesDisconnectAndThePageGoingReleaseNothingHeld`) |
| Always-on start holds before the check and retries | Holds; now also across a switch or a restart before connecting (M10-20) | `KillSwitchTest`, `ReconnectTest` (2 new); device scenario B |
| Always-on connects only the chosen profile | Holds | `KillSwitchTest`, `HomeViewModelTest`, `HomeScreenTest`; the tile's connect records the choice as Home's does |
| Process death measured on devices | Holds | `scripts/m8_failure_injection.sh` with `m8_evidence.py`, every level of the verified run |
| The marker names the profile and since when | Holds | `KillSwitchTest`, `ProtectionStoreTest` |
| ⚠️ R1 (no kill switch: normal network when the VPN goes) | Accepted | Told in E1/E1b, E2, B2, B6d; Help's background topic |
| DNS only the proxy's own name (I-4) | Holds | `m6_evidence.py` on every level. Test connection looks up the *tested* profile's proxy name outside the VPN — a proxy's name, as I-4 allows, never a destination's |
| UDP refused, ping unanswered, IPv6 handled | Holds | Engine tests; device evidence (UDP sinks: none arrived) |
| ⚠️ R5 (Android's own traffic outside VPNs) | Accepted | About, Help, the privacy policy, the guide |
| ⏳ Physical phones | Open, the owner's | `docs/m10/physical-phone-checklist.md` |
| Credentials: AES-256-GCM, Keystore key, profile id as associated data | Holds (unchanged since M3) | `core/data` tests; device Keystore tests |
| A lost key reads as `CREDENTIAL_UNREADABLE` | Holds | `EngineConnectionTesterTest`; no fallback |
| Backups and transfers off | Holds | `check_release_manifest.py` |
| Credentials as bytes, zeroed (I-12) | Holds | Engine test; the tester wipes its arrays |
| No credential in logcat, logs or crash reports (I-3) | Holds | `LogSecret`/`ExceptionSecret` Lint checks (they ran again from d345b94), `LogsTest`, `LogRepositoryTest`, `CrashReportsTest`; M5's logcat search; M10's new log lines carry kinds and fixed words only |
| ⚠️ R7, ⚠️ R2 | Accepted | Unchanged |
| Logs typed, sanitized, bounded, Clear | Holds; the line now says the user's 1 h / 24 h / 7 days, applied before the first purge (M10-21) | M7 tests, `LogBoundsTest` |
| M8 and M10 log entries carry keys, yes/no, durations, kinds only | Holds | `KillSwitchTest`, `LogsTest`, `ReconnectTest` |
| `ApplicationExitInfo`'s description never read | Holds | `AndroidProcessHistory` unchanged |
| No profile name on the lock screen (I-11) | Holds, now whatever Android's lock-screen setting: the notification names no profile (M10-17); the tile none while locked | `VpnNotificationsTest`, `VpnEndToEndTest`, `QuickTileTest` |
| Only allow-listed permissions | Holds | `check_release_manifest.py`, `ManifestTest`; no new permission or library (OSV) |
| VPN service declaration | Holds | `check_release_manifest.py` |
| Nothing exported without a permission but the launcher | Holds | `check_release_manifest.py`; the new activity is not exported |
| Not debuggable; no cleartext; network security config | Holds | `check_release_manifest.py` |
| What the APK ships for the @xml files | Holds | `check_release_manifest.py` (aapt2) |
| `PendingIntent`s immutable | Holds | The notification's (service and activity), the tile's, the app's |
| The notification's actions only on an unlocked phone (M10) | Holds | `VpnNotificationsTest` (Robolectric, as on Android 15 and on Android 11), `VpnEndToEndTest` on 12+ |
| Taps through another app's window dropped (S4) | Holds | `ObscuredTouchesTest`; Settings' Clear goes through the confirmation that drops them |
| Tile only behind `BIND_QUICK_SETTINGS_TILE` | Holds (was ⏳ for M9) | `check_release_manifest.py`, `ManifestTest` |
| The engine's JNI names survive R8 (M10) | Holds | `check_release_manifest.py` on the release APK |
| Engine bounds, malformed packets, fuzzed parsers | Holds | Engine job |
| Reconnects bounded | Holds; a sign-in page that comes and goes adds no attempts (M10-14) | `ReconnectTest` |
| ⚠️ R4 | Accepted | Told in E2 |
| ⏳ Memory and battery on physical phones | Open, the owner's; the emulators' baseline is recorded | `docs/m10/performance-baseline.md` |
| Go modules verified; govulncheck; OSV; wrapper; pinned actions | Holds | Engine and build jobs |
| No key or credential in files or history | Holds | `secrets.yml`; CI's test-signing key lives in the runner's temporary directory and is deleted |
| ⏳ Play App Signing | M11 | — |
| No forbidden claims; the kill switch never an app toggle; no older kill-switch reading as current | Holds | `CopyClaimsTest`, `SettingsScreenTest`, `HomeScreenTest` |
| 🔁 The disconnected notification's wording | Holds, read for this release | `core/vpn` strings: "Apps use your normal network unless Android's system kill switch is on" |
| Limitations stated | Holds | `CopyClaimsTest.theLimitationsAreStated` |
| 🔁 Store listing and support texts | M11 (none exists yet); Help, the privacy policy and onboarding say no more than `docs/m6/routing-and-limitations.md` | Read for this release |

## What the re-check added

- Lines for what is new since M8: the sign-in network (releases nothing), the notification's actions on a locked phone, the engine's JNI names after R8, M10's log entries.
- The tile's tap on a locked phone asks to unlock first (`unlockAndRun`); no automated test can show it — Robolectric's tile unlocks at once, and the device test unlocks the phone first — so it is checked by review and on the owner's phone (the lock-screen row of the phone checklist).
- The device suite runs the debug build; the release build is checked for what R8 could break (M10-18) and runs end to end on the owner's phone and in M11's closed testing.
