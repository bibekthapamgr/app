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
over from these documents alone; the keys and the accounts are the only things that need handing over by hand
(`docs/m11/signing.md` §2 says where the key and its passwords live, §6 what to do if the key is lost).

### 1.1 If the owner is unavailable

A single owner is the project's largest risk: a critical report has a 3-day acknowledgement clock and a 14-day fix
clock whether or not the owner is reachable. Before the first production release the owner names a **second person**
(a friend, a colleague; the date they were named goes in `docs/m11/release-checklist.md` §7's sign-off, the name in
the owner's password manager, not in this public repository) and gives
them, in this order:

1. **Play Console**: a second user on the developer account (Users and permissions) with the permissions to release
   to production (halt, resume, upload), to release to the testing tracks, to view app information and to manage the
   policy declarations (the Console names them per permission, not as a role), so that a release can be halted, a
   rollback uploaded and a policy notice read without the owner. Google asks for
   the account's own verification from time to time; the owner's recovery phone and e-mail are kept current.
2. **The upload key and its passwords**: the two offline backups of `docs/m11/signing.md` §2 — one of them is the
   second person's, or in a store (a password manager's shared vault, a safe) they can open. Without it no release
   can be signed; with Play App Signing a lost upload key can be reset through Play support (`signing.md` §6), which
   needs the Play Console access of step 1.
3. **The repository**: a second admin on `github.com/bibekthapamgr/app` (Settings → Collaborators), who can set the
   secrets (`PROXYFLOW_KEYSTORE_BASE64` and the two passwords), the variables (`PAGES_ENABLED`, the support address,
   the developer name, the key alias), merge a pull request, start the `release` workflow and read the private
   security advisories (Security → Advisories; repository admins see them).
4. **The support mailbox**: a second sign-in, or forwarding to the second person, so that the 3-day clock is kept
   and the auto-reply of `support.md` §3 is not the only answer.

The second person reads §5 and keeps this runbook's calendar when the owner cannot; a change in who that is, is a
line in the sign-off. Checked yearly (§2).

## 2. The calendar

| When | What | Where | Becomes |
|---|---|---|---|
| **Every working day** | The support mailbox and GitHub issues (a person answers within 3 working days); the security channel (acknowledged within 3 days) | Mail; `github.com/bibekthapamgr/app/issues`; Security → Advisories | `triage.md` |
| **Weekly** | Android vitals: the crash rate (28 days) against 0.2 % and the user-perceived crash rate against Google's threshold, new crash clusters, ANRs; new Play reviews; the current rollout's step (`rollback.md` §2) | Play Console → Quality → Android vitals; Reviews; Release → Production | A cluster with a ProxyFlow frame → a defect the same day; a review that reports a bug → an issue the owner files |
| **Monthly** (the first week; the `maintenance` workflow opens the issue) | OSV at today's resolved versions; the Play target API rule; the data-behaviour lock; the release readiness; dependabot's pull requests; the Play Console inbox (policy notices, deadlines, a rejected release → §4.1); the open items of the last release's row. **The owner's calendar carries its own reminder** for the 8th of each month: "is there an issue *Maintenance review YYYY-MM*?" — GitHub disables a workflow's schedule after 60 days without a push to a public repository (it may e-mail the workflow's last author; the issue simply stops), and a quiet app in maintenance is exactly that; when the issue is missing, Actions → maintenance → *Enable workflow*, then *Run workflow* | The issue "Maintenance review YYYY-MM"; `.github/workflows/maintenance.yml` | Ticks on the issue; a vulnerability → §3.1; a target API warning → §3.3 |
| **Quarterly** (January, April, July, October — the monthly issue carries the line) | The policy review: the privacy policy, the Data safety form and the VpnService declaration read against the app | `policy-review.md` | The lock's note updated (`check_data_behavior.py --update`), or a release with the policy changed |
| **Every 180 days at most** | Google's target API page re-read, `docs/m12/play-target-api.json` updated with the date | `check_target_api.py` warns when it is older | — |
| **Yearly** | The Android Gradle plugin, Kotlin and Gradle (§3.2); the Go toolchain and gomobile (§3.2); the lab's images (`docs/m10/compatibility-matrix.md`: the new Android's emulator image added to the matrix when it reaches `google_apis`); the upload key's backups opened and the Play account's recovery details checked (`docs/m11/signing.md` §2, §6); §1.1's second person still reachable and still able to sign in everywhere | — | A release |
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
`docs/m10/compatibility-matrix.md` (the suite must be green on it); then a release. Allow a month. If the bump
will not make Google's date: request the extension in Play Console (the target API notice in the inbox offers it,
until the `extension_until` of the rules file), and when the Console confirms it set `"extension_granted": true`
on that rule in `docs/m12/play-target-api.json` — CI then warns until the extension's end instead of failing every
push; `check_target_api.py --extended` is the same thing for a local dry run.

## 4. A release, from the first change to 100 %

On a branch `release/<name>` from `main` (a hotfix: `hotfix/<name>` from the bad release's tag; a rollback:
`rollback.md` §4). The commit that gets the tag is the one `main` is released from; the dates come later, in a
docs-only commit, because the tooling is built for that order (`check_release_ready.py` accepts a heading marked
"release candidate").

1. `scripts/release/bump_version.py <name>` — the version everywhere at once: `app/build.gradle.kts`, the changelog's
   new section (heading "— release candidate"; written from the merged changes; a placeholder bullet if `[Unreleased]`
   was empty — replaced **before the first push**: `check_release_ready.py` refuses it on every push, not only at
   the release), a `pending` row in `release-history.md`, the privacy policy's version line. Then the
   "What's new" in `docs/m11/store-listing.md` §7 (≤ 500 characters, the checker reads it).
2. **If what the app keeps or sends changed** (and only then — the lock leaves the policy's version line out, so a
   bump alone does not touch it): `policy-review.md`, then `scripts/check_data_behavior.py --update --reviewed-by
   --note`. CI fails on every push until this is done.
3. The pull request to `main` and its `android` run green (a first proof: a pull request's run is of GitHub's test
   merge of the branch into `main`, not of the branch head). Merge with **Create a merge commit**: the merge commit
   on `main` is **the verified commit** — its `android` run (the push to `main`) rerun twice with `gh run rerun`,
   pushing nothing meanwhile (`qa-process.md` §3).
4. The history row's *commit* and *`android` run* columns filled with the merge commit and its run, in a docs-only
   commit on `main` (`qa-process.md` §1 allows it: the tree built is the verified commit's);
   `scripts/release/check_release_ready.py --release-run` green. The hand-started `release` run on `main` with the
   fingerprint (`docs/m11/signing.md` §5; it builds `main`'s head, whose code tree is the verified commit's), green
   three times (rerun twice); the bundle's `release-facts.txt` checked; the row's *`release` run* column filled;
   `check_release_ready.py --production` green; the tag `v<name>` on the verified commit.
5. Internal testing (the owner's phone, `docs/m10/physical-phone-checklist.md` for a change in routing or the VPN),
   then closed testing if the change is large, then production as a staged rollout (`rollback.md` §2).
6. The docs-only commit that closes the release: the changelog heading's date (replacing "release candidate"), the
   history row's production date and rollout steps, `docs/m12/known-issue.md` emptied if a notice was up; the
   28-day crash rate written into the row a month later.

### 4.1 If Play rejects the release, or sends a policy notice

Play Console → Inbox (and the e-mail) names the reason: a declaration (the VpnService declaration, Data safety, the
*App access* instructions for a proxy the reviewer could not test — `docs/m11/declarations.md` says what each answer
is and why, *App access* being a row of its §2 table), a listing claim, a policy the app is said to breach, or a target API deadline.

1. Read the reason in full and write it into the release's defect register with the date.
2. A declaration or listing problem: fix the text in `docs/m11/declarations.md` / `docs/m11/store-listing.md`
   (`check_listing_claims.py` and the data-behaviour lock still apply), re-enter it in the Console, resubmit the same
   bundle. A build problem: a new version through this section. A "could not test" of the proxy: the App access
   instructions re-written with a proxy the reviewer can reach (that row; the owner's own test
   proxy for the review period, its password changed afterwards) and resubmitted.
3. A policy decision the owner believes wrong: the appeal from the inbox message, with the documents that show the
   behaviour (`docs/m6/routing-and-limitations.md`, the privacy policy, the capture evidence), and a note here under
   §2's monthly line until it is answered.
4. When the rejected release was a critical hotfix: the clock of `SECURITY.md` keeps running — the known-issue
   notice stays up, the reporter is told the new date, and a resubmission is done the same day the reason is known.
   A deadline notice (a target API, a policy update) → §3.3 or `policy-review.md`, with the date in the monthly issue.

## 5. What a new maintainer reads first

`README.md`, then `docs/m6/routing-and-limitations.md` (what the app promises), `docs/m8/security-review.md` and
`docs/m1/07-threat-model.md` (what it defends against; updated in M8), `docs/m11/signing.md` (the keys), this runbook, and the last
release's row in `release-history.md` with its defect register. The lab (`scripts/e2e_env.sh`) runs on any Linux
machine with Docker-free tools (Dante, Squid, stunnel, dnsmasq, Python) and an emulator; `android.yml` shows the
exact steps.
