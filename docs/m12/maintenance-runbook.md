# M12 — Maintenance runbook

What the owner does after ProxyFlow is on Google Play, how often, with which script or page, and what each
finding turns into. The other M12 documents are the detail: `support.md` (the channels and what users are told),
`triage.md` (what a report becomes), `incident-response.md` (a security or routing problem), `rollback.md` (staged
rollouts and what to do when one goes wrong), `policy-review.md` (the privacy policy and the Play declarations),
`qa-process.md` (the regression suite and what a release must pass), `release-history.md` (every release and its
results). Brief deliverable "maintenance runbook".

## 1. Who

One person, the owner (`bibekthapamgr`): signs the releases, holds the upload key and the Play Console account,
answers reports (`SECURITY.md` names the clock). Everything below is written so a second maintainer could take it
over from these documents alone; the keys and the account are the only things that need handing over by hand
(`docs/m11/signing.md` §5–§6).

## 2. The calendar

| When | What | Where | Becomes |
|---|---|---|---|
| **Every working day** | The support mailbox and GitHub issues (a person answers within 3 working days); the security channel (acknowledged within 3 days) | Mail; `github.com/bibekthapamgr/app/issues`; Security → Advisories | `triage.md` |
| **Weekly** | Android vitals: crash-free sessions (28 days) against 99.8 %, new crash clusters, ANRs; new Play reviews; the current rollout's step (`rollback.md` §2) | Play Console → Quality → Android vitals; Reviews; Release → Production | A cluster with a ProxyFlow frame → a defect the same day; a review that reports a bug → an issue the owner files |
| **Monthly** (the first week; the `maintenance` workflow opens the issue) | OSV at today's resolved versions; the Play target API rule; the data-behaviour lock; the release readiness; dependabot's pull requests; the Play Console inbox (policy notices, deadlines); the open items of the last release's row | The issue "Maintenance review YYYY-MM"; `.github/workflows/maintenance.yml` | Ticks on the issue; a vulnerability → §3.1; a target API warning → §3.3 |
| **Quarterly** (January, April, July, October — the monthly issue carries the line) | The policy review: the privacy policy, the Data safety form and the VpnService declaration read against the app | `policy-review.md` | The lock's note updated (`check_data_behavior.py --update`), or a release with the policy changed |
| **Every 180 days at most** | Google's target API page re-read, `docs/m12/play-target-api.json` updated with the date | `check_target_api.py` warns when it is older | — |
| **Yearly** | The Android Gradle plugin, Kotlin and Gradle (§3.2); the Go toolchain and gomobile (§3.2); the lab's images (`docs/m10/compatibility-matrix.md`: the new Android's emulator image added to the matrix when it reaches `google_apis`); the upload key's and the Play account's recovery details checked (`docs/m11/signing.md` §6) | — | A release |
| **Before every release** | `qa-process.md` §3 | — | `release-history.md` |

## 3. Dependencies and the platform

### 3.1 A vulnerability (OSV, govulncheck, a Play notice, a report)

The `android` build fails on a HIGH or CRITICAL advisory in a release library (M8, `scripts/osv_scan.py`), the
engine job on a Go advisory (`govulncheck`), and the monthly workflow re-asks OSV at the versions resolved today, so
an advisory published after the last release is seen within a month. Then: is the vulnerable code reachable from the
network or from another app? Reachable → `incident-response.md` (severity by its table; a fixed release on the
high or critical clock of `SECURITY.md`). Not reachable → the bump goes into the next planned release and
`scripts/osv_allowlist.json` says why it cannot reach ProxyFlow, with the advisory id and the date, until then.

### 3.2 Updates

- **Libraries** (AndroidX, Room, DataStore, Compose, the test tools; the engine's Go modules; the actions): dependabot
  proposes them monthly, grouped (`.github/dependabot.yml`); each pull request runs the whole CI — a bump that breaks
  a routing, protocol, security or screenshot test does not merge. A screenshot that changed only because a Compose
  version renders a pixel differently is re-recorded from the `ci-screenshots/<branch>` branch and looked at; one that
  changed what a screen says is a defect.
- **The Android Gradle plugin, Kotlin, Gradle, the Go toolchain, gomobile, the NDK**: by hand, once a year or when a
  target API bump needs them, on a branch of their own, with the whole `android` and `release` runs green three
  times (the project's rule) before merging. `gradle/libs.versions.toml` holds every version; `engine/build-aar.sh`
  the Go and NDK ones.
- **Room schema**: a change to an entity is a new database version with an exported schema under
  `core/data/schemas/` and a migration with a test in `DatabaseMigrationTest` (`qa-process.md` §2.4); the data
  behaviour lock changes with the schema, which forces the policy review.

### 3.3 The target API

`scripts/check_target_api.py` (in every build and in the monthly review) fails when Google Play's requirement has
passed and warns 120 days ahead, from the dates in `docs/m12/play-target-api.json` and Google's pattern. A bump:
`compileSdk` and `targetSdk` in `gradle/libs.versions.toml`; the new Android's behaviour changes read
(`developer.android.com/about/versions/<n>/behavior-changes-<n>`) against the VpnService, the foreground service,
notifications, the Keystore and storage; the new emulator image added to `android.yml`'s matrix and to
`docs/m10/compatibility-matrix.md` (the suite must be green on it); then a release. Allow a month.

## 4. A release, from the first change to 100 %

1. `scripts/release/bump_version.py <name>` — the version everywhere at once; the changelog's new section written
   from the merged changes; the "What's new" in `docs/m11/store-listing.md` §7 (≤ 500 characters, the checker reads it).
2. If what the app keeps or sends changed: `policy-review.md`, then `scripts/check_data_behavior.py --update`.
3. The regression suite green three times on the release commit (`qa-process.md` §3); the row in
   `release-history.md` filled with the commit and the runs; `scripts/release/check_release_ready.py --production`.
4. The hand-started `release` run on `main` with the fingerprint (`docs/m11/signing.md` §5); the bundle's
   `release-facts.txt` checked; the tag `v<name>` pushed.
5. Internal testing (the owner's phone, `docs/m10/physical-phone-checklist.md` for a change in routing or the VPN),
   then closed testing if the change is large, then production as a staged rollout (`rollback.md` §2).
6. The changelog's date, the history row's date and rollout steps; the 28-day crash-free rate written in a month later.

## 5. What a new maintainer reads first

`README.md`, then `docs/m6/routing-and-limitations.md` (what the app promises), `docs/m8/security-review.md` and
`docs/m8/threat-model.md` (what it defends against), `docs/m11/signing.md` (the keys), this runbook, and the last
release's row in `release-history.md` with its defect register. The lab (`scripts/e2e_env.sh`) runs on any Linux
machine with Docker-free tools (Dante, Squid, stunnel, dnsmasq, Python) and an emulator; `android.yml` shows the
exact steps.
