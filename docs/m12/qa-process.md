# M12 — The ongoing QA process

What every change and every release must pass, where the regression suite for protocol and routing behaviour
lives, and how a new defect becomes a test that stays. Brief task 5 ("maintain regression tests for protocol and
routing behavior") and deliverable "ongoing QA process".

## 1. The rule

Nothing reaches `main` without the whole suite green; nothing reaches Google Play without the suite green **three
times on the same commit** (the attempts of one run), the signed-bundle smoke green, and a row in
`release-history.md` naming the runs. A green run of a different commit — a docs-only commit after it is the one
exception, since the path filters skip the suite for it — is not that commit's proof. This has been the project's
rule since M5 and every acceptance report records it.

## 2. The regression suite, by what it protects

| What | Where it runs | What it checks | Added in |
|---|---|---|---|
| **2.1 Protocol** (SOCKS5, HTTP CONNECT, TLS to the proxy, Basic sign-in, the fake-IP DNS) | The engine job (`engine.yml`): 120 Go tests with the race detector, the fuzzers of the parsers; `core/engine` androidTest on API 29–36 against the lab's Dante, Squid and stunnel fronts (valid, wrong name, expired, untrusted CA) | A proxy's every reply the RFCs allow and a few they forbid; no plaintext fallback; the certificate chain and host name verified by Android's trust manager; the gomobile API's bounds | M4, M6 |
| **2.2 Routing** (what goes through, what is blocked, what is outside) | The device jobs: `scripts/e2e_device.sh` with the evidence scripts `m5_evidence.py` … `m8_evidence.py` and M10's scenarios, on each API level with the uplink captured (the emulator's `-tcpdump` on mobile data, tcpdump inside the phone on Wi-Fi) | Every connection to a destination came through a proxy's tunnel (connections = tunnels); no datagram reached a sink; no lab name was looked up on the uplink; the witness reached only by M8's deliberate checks; traffic held during reconnect; Always-on VPN's start holds traffic before the check | M5, M6, M7, M8, M10 |
| **2.3 Security** | `scripts/m8_failure_injection.sh` (process death, a crash, with and without Android's kill switch); the `LogSecret` and `ExceptionSecret` Lint rules; `scripts/check_release_manifest.py`; `scripts/osv_scan.py`; `scripts/check-secrets.sh` on every commit of the history; the engine's fuzzers and load tests | Nothing leaks when the process dies; no credential, server or site in a log or a crash report; the release's permissions, backups, cleartext and trust settings; no known vulnerability; no key in the repository | M7, M8 |
| **2.4 Data** | `DatabaseMigrationTest` (every schema version's exported JSON migrated with real rows; a newer database refused loudly and kept); `core/data` androidTest (credentials sealed with the Keystore, every file in the data directory checked for plaintext); the data-behaviour lock | Profiles survive every update; a rollback cannot destroy them (`rollback.md` §5); nothing stored in the clear; what is kept or sent is as reviewed | M3, M12 |
| **2.5 UI** | 651 JVM tests; the 123 Roborazzi screenshots with their accessibility checks at the phone size and at large font, light and dark, left-to-right and right-to-left; `CopyClaimsTest` (what the app may never say, and what it must say) | Every screen as designed; every claim and limitation as documented | M2, M9 |
| **2.6 Release** | `release.yml`: the signed bundle verified, installed from its APK set on API 29 and 36, used through its own screens through the lab's proxy, the logs counted | The bundle Play gets is the one that was tested, and it works with no test hook | M11 |
| **2.7 Documents and claims** | `check_listing_claims.py`, `store_assets.py check`, `check_release_ready.py`, `check_target_api.py`, `privacy_policy.py`'s document check, `test_no_bidi_controls.py` | The listing, the policy, the changelog and the history agree with the app and with each other | M9, M11, M12 |

The counts (651, 123, 120, 75 instrumented tests per level) are the verified run's of M10–M11 and grow; the run's
annotations give the current ones.

## 3. Before a release (the owner's list; `maintenance-runbook.md` §4 has the order)

1. `android` and `release` runs green on the release commit; each re-run twice more (`gh run rerun <id>`), nothing
   pushed to the branch meanwhile (a push cancels the attempt — on a pull request, a docs-only push too, since the
   path filter reads the whole pull request's diff). The verified commit is the merge commit on `main`: a pull
   request's `android` run is of GitHub's test merge (`refs/pull/N/merge`), a first proof only, and
   `workflow_dispatch` on a branch runs on the branch head; the `release` run with the upload key exists only on
   `main` (`maintenance-runbook.md` §4 steps 3–4).
2. The signed-bundle smoke's screenshots and recording looked at once (the artifact), for the one thing a count
   cannot tell: that the screens look right.
3. For a change in routing, the VPN, the engine or reconnecting: `docs/m10/physical-phone-checklist.md` on the
   owner's phone with the owner's proxy — the checks an emulator cannot give (a real move between Wi-Fi and mobile
   data, a network with a sign-in page, the maker's battery management).
4. `scripts/release/check_release_ready.py --release-run` before the `release` run (the row's commit and `android`
   run filled, `[Unreleased]` empty) and `--production` before the upload (the `release` run's id too; the heading
   dated or "release candidate").

## 4. A defect becomes a test

Every defect fixed in a maintenance release gets the regression test that would have caught it, in the layer of
§2 it belongs to, **before** the fix is merged — the test fails on the tag, passes with the fix — and the register
row names it. A routing or protocol defect's test runs against the lab on every API level (a scenario in
`scripts/m10_scenarios.sh` or an evidence check), not only on the JVM; a defect one provider's proxy showed gets the
provider's reply reproduced in the lab (Dante's and Squid's configuration, or a line in `e2e_servers.py`'s fake
proxies), with the host redacted.

## 5. What the suite does not cover, and who does

- A real phone, a real network, a maker's power management: the owner's checklist (§3.3) and the testers of closed
  testing (`docs/m11/release-checklist.md` §4); their findings go into the register like any other.
- A provider's proxy the lab does not imitate: the reporter's `curl -v` reply (`triage.md` §1) → a lab imitation.
- The app on Android versions newer than the matrix: the yearly image addition (`maintenance-runbook.md` §3.3).
- Performance on a phone: `docs/m10/performance-baseline.md`'s bars are the emulator's; the phone's figures are the
  owner's (`docs/m10/physical-phone-checklist.md` §3).

## 6. The lab, kept working

The lab's own check (`scripts/e2e_lab_check.py`) runs before every device job: a lab that cannot tunnel, block,
refuse or log as expected fails the job before the app is blamed. Its tools (Dante, Squid, stunnel, dnsmasq,
tcpdump) are the runner image's; a runner image change that breaks one shows up as the lab check failing, and the
fix is in `scripts/e2e_env.sh`, never in a test's expectation. The emulator itself is the lab's least steady part:
its process exits at scenario R's restart now and then (M10-54; three times in M12's runs, with nothing on the host
to say why), so `scripts/adb_reboot.sh` starts it again from the same AVD — a cold start of the same phone, said so in
R's note and the job's annotation — and its second capture is appended to the first (`scripts/pcap_cat.py`); an
install that adb refuses with "device offline" for a moment is tried again (`adb_install`, M12-80). A lab weakness
that fails a job is a register row and a fix of this kind, with its test under `scripts/tests/`; a test's expectation is
never loosened for it.
