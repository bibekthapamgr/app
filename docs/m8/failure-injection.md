# M8 — Failure injection

**Milestone:** M8 · **Decision:** ADR-0013 · **Scripts:** `scripts/m8_failure_injection.sh`, `scripts/m8_evidence.py` · **Steps:** `app/src/androidTest/kotlin/app/proxyflow/e2e/KillSwitchEndToEndTest.kt` · **Lab:** `scripts/e2e_env.sh` (the witness, port 8095)

M8 asks for tests of tunnel failure, proxy disconnect, network changes, permission revocation and process death, and that an engine failure never silently sends supported traffic directly. This page lists where each failure is injected and checked, and gives the device results of the one M8 adds: the process ending while connected, with and without Android's kill switch, and a crash with a planted secret.

## 1. Every failure, where it is tested

| Failure | What must happen | Where | Since |
|---|---|---|---|
| Engine fails while connected (its packet loop ends) | Traffic held at the interface, Blocked (B6, `ENGINE_STOPPED`); nothing goes around the proxy | Device: `VpnEndToEndTest.anEngineFailureHoldsTrafficUntilRestart` (the sink beside the destination gets nothing; `m5_evidence.py` checks the captures); JVM: `VpnSessionTest` | M5 |
| Proxy refuses connections while connected | New connections get a RST; three in a row reconnect with traffic held | Device: `whenTheProxyStopsAnsweringNothingGoesAroundIt`, `ReconnectEndToEndTest` | M6, M7 |
| Proxy accepts and hangs | Each connection fails at the handshake timeout; three in a row reconnect | Device: `aProxyThatHangsIsReportedAsATimeout` | M6, M7 |
| SOCKS5 proxy leaves connection requests unanswered | Each is the site's failure; 3 in a row to 2+ sites reconnect | JVM: `KillSwitchTest`; Go: `TestUnansweredSOCKS5RequestsAreCountedApart`, `TestUnansweredCountStartsOverOnAnAnswer` | M8 |
| Certificate changes while connected | Blocked at once (`TLS_UNTRUSTED`), nothing unencrypted | Device: `aCertificateThatChangesWhileConnectedBlocksAtOnce` | M6, M7 |
| Proxy disconnect (the user's Disconnect, Stop while blocked) | Engine stopped, interface closed, service stopped, the marker removed; no resource left | Device: `VpnEndToEndTest.repeatedConnectAndDisconnectLeaveNothingBehind`, `nothingListensWhileConnected`; JVM: `KillSwitchTest` (no session job left after an end, the marker removed) | M5, M8 |
| Switch to another profile, restart | The interface stays, traffic held during the new check | Device: `VpnEndToEndTest.switchingProfilesKeepsTrafficInTheTunnel` | M5 |
| The phone's network changes or goes away | Connections ended and re-made on the new network; offline holds traffic | Device: `ReconnectEndToEndTest`; JVM: `ReconnectTest` | M7 |
| Permission revocation (another VPN app, consent withdrawn) | Everything closed, Home and an alert say so; B6d points to Android's kill switch | Device: `VpnEndToEndTest.revocationClosesEverythingAndSaysSo`; screens: `HomeScreenTest` | M5, M8 |
| **Process death while connected** (killed as Android's low-memory killer does) | Android removes the VPN: without Android's kill switch apps use the normal network (Android's behaviour), with it nothing gets out; the next start finds it, logs it and tells the user | Device: this page, scenarios A and B | M8 |
| **Crash while connected** | The same, and Android's crash report holds no secret from the exception's message | Device: this page, scenario C | M8 |
| **Always-on VPN start with the proxy away** | The interface comes up first, traffic held, attempts retried; a refused sign-in blocks | JVM: `KillSwitchTest`; device: scenario B starts ProxyFlow this way | M8 |
| Malformed packets, connection floods | Dropped, refused or held to the bounds; the engine goes on | Go: `TestMalformedPacketsAreDroppedAndTheEngineGoesOn`, `FuzzPacketLoop`, `TestManyConnectionsAtOnceStayWithinMaxConns`, `TestAHandshakeFloodIsHeldToMaxInFlight` | M8 |

## 2. How the process-death scenarios run

After the device tests and their logcat, `scripts/e2e_device.sh` runs `scripts/m8_failure_injection.sh`, which installs the app and its test APK again and drives `KillSwitchEndToEndTest` one step at a time (`am instrument -e m8 <step>`), doing from outside what no test inside the app's process can: killing it, crashing it, and having Android's Always-on VPN start it.

- **The witness.** A destination on the runner's loopback (`127.0.0.1:8095`). The phone reaches it directly as `10.0.2.2:8095`, but never through a proxy (Dante and Squid would connect to their own 10.0.2.2, where nothing listens), so every line it logs got out around ProxyFlow. The lab checks this from the runner before the emulator starts (`scripts/e2e_lab_check.py`).
- **Killing.** `kill -9` of the app's process as the app's own user (`run-as`), as Android's low-memory killer ends a process; where that fails (on the Android 10 image `run-as … kill` answers "unknown pid"), as root (`su 0`, which the emulator images have), and only then a force-stop. The script checks the process is gone.
- **Android's kill switch.** The test APK holds a device admin used only in the lab (`LabDeviceAdmin`), made device owner with `dpm set-device-owner`; its command receiver (`LabAdminCommands`, behind DUMP, which only the shell has) calls `DevicePolicyManager.setAlwaysOnVpnPackage(ProxyFlow, lockdown = true)` — exactly what Android's VPN settings set when the user turns on Always-on VPN and "Block connections without VPN" — and afterwards turns it off and gives up device ownership.
- **Nothing else is touched while ProxyFlow is down.** No lab name is looked up and no lab destination contacted while ProxyFlow is not connected, so the M5 and M6 evidence, which check the whole run's DNS and captures, are unaffected.

**A — killed while connected, no Always-on VPN.** The step connects the chosen profile, shows a line reaching the destination through the proxy, and waits; the script kills the process, then probes the witness from the shell every half second (at most 20 times) until it answers — the negative control: it shows what Android does without its kill switch, and that the witness and the captures would see a leak. The next start (step `afterProcessDeath`) must begin Disconnected with no notification left, and have found the interruption with the reason Android gives (Android 11+: killed, other or low memory; Android 10: unknown), the profile, the end time (Android 11+) and the log entry.

**B — Always-on VPN with "Block connections without VPN".** The device owner sets it; Android starts ProxyFlow, which connects the chosen profile and reads that blocking is on; a line goes to the destination through the proxy; the process is killed; the witness is probed 40 times — every probe must fail, the witness must log none, and no capture that covers the uplink may hold a SYN to it while the setting is on. The next start (step `afterLockdownKill`) must have kept what Android said (on, blocking), found the interruption, and connect again while Android blocks everything else (a line reaches the destination through the proxy). Then the setting is turned off, ProxyFlow force-stopped, and the witness must answer the shell directly again (the second negative control). Turning Always-on VPN off this way withdrew ProxyFlow's VPN consent on Android 14 and 16 (the first run's scenario C then found none); the script notes what Android left and gives the consent back.

**C — a crash with a planted secret.** The step connects, then throws `IllegalStateException("PF8-planted-<token> proxy pf8user:PF8SECRET<token>@planted.pf8.invalid:1080 failed")` on a thread of its own, as a bug's exception would reach the top of a thread. The process must end; Android's crash report (`dumpsys dropbox --print data_app_crash`) must name the exception (`PF8-planted-<token>`) and must not hold `PF8SECRET<token>`; the next start must find a crash (Android 11+). The phone's own log is searched too: Android's runtime writes the uncaught exception there itself before any app code runs — the evidence reports that count; no app can change it (ADR-0013 §7).

`scripts/m8_evidence.py` then checks the steps the script noted (with the phone's time) against the witness's log, the captures of the phone's uplink (each counts only if it saw A's direct line, so it is shown to cover the uplink; at least one must) and the crash report, and fails on any check that doesn't hold or any step the script marked FAILED. Its checks have their own tests (`scripts/tests/test_evidence.py`, `M8EvidenceTest`: a run where everything holds passes; a line heard or a SYN seen while blocking, an answered probe, negative controls that never reach the witness, the secret in the crash report, a failed or missing step, a process that couldn't be killed, and no capture covering the uplink each fail).

## 3. Results

The verified run and its three attempts are in `docs/m8/M8-acceptance-report.md`. Per API level (first verified attempt):

| | API 29 | API 34 | API 35 | API 36 |
|---|---|---|---|---|
| A: killed by | | | | |
| A: witness reached directly after the kill | | | | |
| A: next start found | | | | |
| B: Always-on VPN start connected | | | | |
| B: ProxyFlow read | | | | |
| B: probes with ProxyFlow gone / answered | | | | |
| B: Android started ProxyFlow again | | | | |
| B: next start | | | | |
| B: witness reached once the setting was off | | | | |
| C: crash report | | | | |
| C: Android's own logcat lines with the secret | | | | |
| C: next start found | | | | |
| Captures that saw the uplink / SYNs to the witness while blocking | | | | |
