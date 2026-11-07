# M12 — Staged rollouts and rollback

Brief task 6 ("use staged rollouts when available and document rollback procedure") and acceptance "rollback and
incident-response instructions exist". The facts of Google Play this relies on were read on 2026-11-08
(`support.google.com/googleplay/android-developer/answer/6346149`, "Release app updates with staged rollouts"): a
production *update* can go to a percentage of users (a staged rollout applies to updates, not to an app's first
publishing — 1.0.0 (11) goes to 100 % of production after closed testing, and §2's steps apply from the second
release on) and be raised by hand (it never rises on its own), **halted** or **resumed** (the same set of users); a
halted rollout keeps the users who already got the update on it; and the page names no way to roll a user back —
"create and roll out a new release" with a fixed bundle is what Google says to do, so a rollback is a new release
with a higher `versionCode` whose code is the old one's. The owner re-reads the page before the first production
release and notes the date here.

## 1. Why a rollback is a new version

Android installs a package only if its `versionCode` is not lower than the installed one, and Play offers only the
highest active release. So "going back to 1.0.0 (11)" after 1.0.1 (12) went wrong means building **1.0.0 (13)** — the
code of the `v1.0.0` tag with `versionCode = 13` — and releasing it. The user's data must survive this both ways:
profiles, settings and the log are read by both versions (§5), which is why a Room migration is never destructive and
a settings key never changes meaning.

## 2. The staged rollout, every production release

| Step | Share | Hold for | Go on when | Halt when |
|---|---|---|---|---|
| 1 | **5 %** | 2 days | Crash-free sessions of the new version ≥ 99.8 % (Android vitals, "by version"); no new crash cluster with a ProxyFlow frame; no report of a routing or credential problem; Connect works on the owner's phone from Play | Any of those fails; a critical incident (`incident-response.md` §1) |
| 2 | **20 %** | 2 days | The same | The same |
| 3 | **50 %** | 3 days | The same | The same |
| 4 | **100 %** | — | — | A later finding: a new release |

The first release, 1.0.0 (11), goes through internal and closed testing first (`docs/m11/release-checklist.md` §4),
then to production whole (Play stages updates only); the closed test's fourteen days with the testers are its staged
step. From 1.0.1 on, the four steps. A hotfix for a critical incident may start at 20 % when the fix was proven on the
failing case in CI and on the owner's phone, and go to 100 % after one day at 50 %. Each step's date goes in the
*Rollout* column of `release-history.md`.

The owner's own phone updates from Play at step 1 (the owner is in the first percentage by opting in to the internal
track, which always gets the release first): Connect through the owner's proxy, a page loaded, Disconnect — the one
check no vitals graph gives.

## 3. Halt

Play Console → Release → Production → the release → **Halt rollout**. Users who have the update keep it; no one else
gets it. Then `incident-response.md` §2 from step 2 (assess): either **resume** (the finding was not the release's —
written in the history row) or a **new release** (§4). A halted rollout is never left overnight without the
known-issue notice if the problem is critical.

## 4. The rollback release, step by step

1. `git checkout -b hotfix/<name> v<previous name>` — the tag of the last good release (`release-history.md` §Tags).
2. `scripts/release/bump_version.py <previous name>` is refused (the name must be newer): for a pure rollback, edit
   `app/build.gradle.kts` by hand — `versionName` stays the old one with a suffix (`1.0.0-r1`), `versionCode` is the
   next number — and add the changelog section `## [1.0.0-r1 (13)]` saying "the code of 1.0.0 (11), re-released
   because 1.0.1 (12) ‹what went wrong›"; regenerate the privacy policy (`scripts/privacy_policy.py --out
   docs/m9/privacy-policy.md`); add the history row.
2a. If the bad release bumped the Room schema (`core/data/schemas/` has a higher version than the tag's): the
   downgrade migration and its test (§5), in the rollback release; without it, the release is not uploaded.
3. The data-behaviour lock: the old code's behaviour is the one last reviewed for it, so `check_data_behavior.py`
   passes once the policy document names the new version (the lock hashes the text; the version line differs → run
   `--update` with the note "rollback to 1.0.0's behaviour").
4. The whole regression suite green on the hotfix commit (`qa-process.md` §3) — the suite of the old code is known to
   pass; this proves the branch is what the tag was plus the version.
5. The hand-started `release` run on the hotfix branch is refused the upload key (`main` only, `docs/m11/signing.md`
   §3): merge the hotfix branch to `main` first (a fast-forward of the version commits; the bad release's commits
   stay in history and are reverted on `main` by `git revert` so `main` builds the old behaviour), then the run on
   `main`.
6. Release 1.0.0-r1 (13) at 20 % → 50 % → 100 % on the hotfix clock (§2); the known-issue notice updated; the tag
   `v1.0.0-r1`.
7. The bad release's defect in its register, with the regression test that would have caught it, before the next
   forward release.

A forward fix (1.0.1 → 1.0.2) follows `maintenance-runbook.md` §4 on a branch from the tag; it is preferred whenever
the fix is small and proven, since a rollback carries the old bugs back.

## 5. Data compatibility across versions (acceptance "profile data remains compatible or has a safe migration")

- **Profiles** (Room, `ProxyFlowDatabase`, version 3): every schema version is exported under `core/data/schemas/`
  and every migration is tested from each earlier version with real data (`DatabaseMigrationTest`): a migration
  adds, never drops or renames (a destructive migration is forbidden by the test and by review). **Going back is
  not free**: Room refuses to open a database whose version is higher than the code's — the old app fails loudly and
  deletes nothing (`aDatabaseFromANewerVersionFailsLoudlyAndKeepsItsData`), so a user who gets a rollback release
  after a schema bump would see the app fail to start, with their profiles intact but unreadable. So a rollback
  release that crosses a schema bump **ships a downgrade migration** (`Migration(new, old)`: drop nothing the old code
  reads; the new columns stay, unread) in `ProxyFlowDatabase`'s builder, with a test from the new schema's exported
  JSON in `DatabaseMigrationTest` — the rollback release is built fresh from the tag and can carry it; `§4` step 2a.
  `fallbackToDestructiveMigrationOnDowngrade` is never used: losing the profiles is worse than the bug being rolled
  back.
- **Credentials** (Android Keystore keys per profile, ADR-0006): the key alias scheme and the sealed-blob format have
  no version field and are not changed in a patch or minor release; a change is a major release with a re-sealing
  migration tested on an emulator (`core/data` androidTest) and a note in the changelog.
- **Settings** (DataStore): keys are only added; a removed feature leaves its key unread, never repurposed.
- **The log** (`LogDatabase`, version 1): at most 24 hours old — a version mismatch may drop it; acceptable, and
  the changelog says so when it happens.
- The data-behaviour lock changes with any of these, which forces the policy review and so a conscious decision.

## 6. The test of this document

Before the first production release, the owner does §4 once on the internal track with a throwaway version (a
"1.0.0-r0 (12)" built from the tag, uploaded, installed over 1.0.0 (11) on their phone: the profiles and settings
there afterwards; then the real 1.0.1 (13) later), and writes the date here: ‹date›.
