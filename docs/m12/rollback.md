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
release and notes the date here. Where this document states what Play Console does, it says so; the owner's
re-reading corrects it.

## 1. Why a rollback is a new version, with a new name

Android installs a package only if its `versionCode` is not lower than the installed one, and Play offers only the
highest active release. So "going back to 1.0.0 (11)" after 1.0.1 (12) went wrong means building a **new version whose
code is 1.0.0's** — `1.0.2 (13)` in the example used below: the sources of the `v1.0.0` tag, `versionCode = 13`,
`versionName = "1.0.2"` — and releasing it. The name is a normal forward name made with `bump_version.py` (the
tooling refuses a name that is not newer, and `check_release_ready.py` and the changelog's links are built for
`MAJOR.MINOR.PATCH`); the changelog section says in its first line that the release *re-releases 1.0.0 (11)'s code*.
The user's data must survive this both ways: profiles, settings and the log are read by both versions (§5), which is
why a Room migration of the profiles database is never destructive and a settings key never changes meaning.

## 2. The staged rollout, every production release

| Step | Share | Hold for | Go on when | Halt when |
|---|---|---|---|---|
| 1 | **5 %** | 2 days | The gate below passes | The gate fails; a critical incident (`incident-response.md` §1) |
| 2 | **20 %** | 2 days | The same | The same |
| 3 | **50 %** | 3 days | The same | The same |
| 4 | **100 %** | — | — | A later finding: a new release |

**The gate**, in Android vitals' own terms (Play Console → Quality → Android vitals → Crashes and ANRs, filtered to
the new version): the **crash rate** (the share of daily sessions with a crash) **≤ 0.2 %** and the **user-perceived
crash rate** below Google's bad-behaviour threshold (1.09 % when read; the Console shows the current one); no new
crash cluster with a ProxyFlow frame; no ANR cluster with a ProxyFlow frame; no report (mail, issue, review) of a
routing or credential problem; and the owner's own check (below) done. Vitals lag about a day and show a rate only
above a minimum number of daily users, so at 5 % of a new app's users the figure may be missing for the whole hold:
**no figure and no report is a pass; no figure and a report is a halt** — the hold is never shortened for lack of
data, and a step may be held longer until a figure appears. The dates of each step go in the *Rollout* column of
`release-history.md`.

The first release, 1.0.0 (11), goes through internal and closed testing first (`docs/m11/release-checklist.md` §4),
then to production whole (Play stages updates only); the closed test's fourteen days with the testers are its staged
step. From the second release on, the four steps. A hotfix for a critical incident may start at 20 % when the fix was
proven on the failing case in CI and on the owner's phone, and go to 100 % after one day at 50 %; a **rollback** of a
critical release goes to **100 % at once** (§4), because the users who have the bad release are the ones it is for.

**The owner's own check**: the same bundle is installed on the owner's phone from the **internal track** (the release
is made on the internal track first, then promoted to production — the owner's device is *not* thereby among
production's 5 %, who are chosen by Play at random): Connect through the owner's proxy, a page loaded, Disconnect —
the one check no vitals graph gives. Done before step 1 is raised.

## 3. Halt

Play Console → Release → Production → the release → **Halt rollout**. Users who have the update keep it; no one else
gets it. Then `incident-response.md` §2 from step 2 (assess): either **resume** (the finding was not the release's —
written in the history row) or a **new release** (§4). A halted rollout is never left overnight without the
known-issue notice if the problem is critical. Note what a halt does *not* do: the users who got the bad release keep
it until a new release reaches them, which is why a rollback of a critical release goes to 100 % at once.

## 4. The rollback release, step by step

The rollback is made **on a branch from `main`**, not from the old tag: `main` already holds the bad release, and the
branch must end up as the commit `main` is released from (the upload key is granted to `main` only,
`docs/m11/signing.md` §3), with its three green runs on *that* commit (`qa-process.md` §1: a green run of another
commit is not this commit's proof).

1. `git checkout -b rollback/<name> main`.
2. `git revert` the bad release's commits (`git revert -m 1 <merge commit>` when it was merged as a merge commit; the
   version-bump commit too), so the tree is the old tag's sources: `git diff v1.0.0 -- app core engine feature` is
   empty but for what step 3 adds.
3. `scripts/release/bump_version.py 1.0.2` (the next forward name; code 13 in the example). In the new changelog
   section's first line: "Re-releases the code of 1.0.0 (11); 1.0.1 (12) ‹what went wrong, the defect's id›". The
   history row the script added says "rollback of 1.0.1 (12)" in *Notes*.
4. **If the bad release bumped a Room schema** of the profiles database (`core/data/schemas/…ProxyFlowDatabase/` has
   a version above the tag's): the **downgrade migration and its test** (§5) are added on this branch — the revert
   removed the new schema's exported JSON, so it is restored from the bad release's tag (`git checkout v1.0.1 --
   core/data/schemas/app.proxyflow.core.data.db.ProxyFlowDatabase/4.json`, kept as the test's input) — and the branch
   is no longer "the tag plus the version": the migration is reviewed like any code. Without it the release is not
   uploaded. The log database needs nothing (§5).
5. The data-behaviour lock: a rollback restores a reviewed behaviour, so `check_data_behavior.py` passes as it is
   (the policy's version line is left out of the lock). It fails only when the bad release had changed the behaviour
   *and* the lock was updated for it: then the revert brings the old behaviour back and the lock is updated once more,
   `--note "rollback to 1.0.0 (11)'s behaviour"`, and `docs/m9/privacy-policy.md` is checked to say the old thing.
6. The regression suite three times on the branch's final commit: open the pull request to `main` (the `android` run
   on the pull request), rerun it twice with `gh run rerun <id>` — pushing nothing in between — and
   `check_release_ready.py --release-run` green. Merge the pull request as a fast-forward or a merge commit; the
   hand-started `release` run on `main` with the `fingerprint` input builds the signed bundle (`docs/m11/signing.md`
   §3). If the merge made a new commit, that commit is the one `release` builds: the pull request's runs were of its
   parent, so the `android` run of the merge commit on `main` is waited for and rerun twice before the `release` run
   (a merge of the branch's head without a merge commit avoids this).
7. Upload to the internal track, the owner's own check (§2), promote to production: **100 % at once** for a critical
   incident; 20 % → 50 % → 100 % on the hotfix clock otherwise. The known-issue notice updated (`incident-response.md`
   §3); the tag `v1.0.2` on the merged commit; the history row's columns filled; `check_release_ready.py --production`
   green before the upload.
8. The bad release's defect in its register, with the regression test that would have caught it, before the next
   forward release.

A **forward fix** (1.0.1 → 1.0.2 with the bug fixed) follows `maintenance-runbook.md` §4 on a branch
`hotfix/<name>` cut from the tag of the bad release (`v1.0.1`), merged to `main` through a pull request before the
`release` run exactly as steps 6–7 say; it is preferred whenever the fix is small and proven, since a rollback carries
the old bugs back.

## 5. Data compatibility across versions (acceptance "profile data remains compatible or has a safe migration")

- **Profiles** (Room, `ProxyFlowDatabase`, version 3): every schema version is exported under `core/data/schemas/`
  and every migration is tested from each earlier version with real data (`DatabaseMigrationTest`): a migration
  adds, never drops or renames. A destructive migration is forbidden: the downgrade case by the test
  (`aDatabaseFromANewerVersionFailsLoudlyAndKeepsItsData` fails if `fallbackToDestructiveMigrationOnDowngrade` is
  added) and both directions by CI, which fails the build if `fallbackToDestructive` appears anywhere but
  `LogDatabase.create` (`android.yml`, "No destructive migration on the profiles database"). **Going back is not
  free**: Room refuses to open a database whose version is higher than the code's — the old app fails loudly and
  deletes nothing, so a user who gets a rollback release after a schema bump would see the app fail to start, with
  their profiles intact but unreadable. So a rollback release that crosses a schema bump **ships a downgrade
  migration** in `ProxyFlowDatabase`'s builder (`addMigrations(…, MIGRATION_4_3)`), §4 step 4. What that migration
  must do: Room validates the opened table against the entity, column by column (an extra column fails the open
  with "Migration didn't properly handle"), and `ALTER TABLE … DROP COLUMN` needs SQLite 3.35, which Android 10–13
  (API 29–33) do not have — so the migration **recreates the table with exactly the old columns**: `CREATE TABLE
  profiles_old (…the old columns…)`, `INSERT INTO profiles_old SELECT ‹old columns› FROM profiles`, `DROP TABLE
  profiles`, `ALTER TABLE profiles_old RENAME TO profiles`, the old indices recreated. The data of the new columns is
  lost — it was unread by the old code anyway — and the changelog says which. Its test in `DatabaseMigrationTest`
  creates the database from the newer schema's exported JSON (restored from the bad release's tag, §4 step 4) with a
  profile and its credentials, runs the migration with `MigrationTestHelper.runMigrationsAndValidate`, then opens it
  with `ProxyFlowDatabase.create` and reads the profile and the credentials back.
  `fallbackToDestructiveMigrationOnDowngrade` is never used here: losing the profiles is worse than the bug being
  rolled back.
- **Credentials** (Android Keystore keys per profile, ADR-0006): the sealed blob **is versioned**, in three places —
  the `credentials_format` column, `CredentialCipher.FORMAT_VERSION` bound into the GCM associated data
  (`proxyflow/credentials/v1/<profile id>`), and `CredentialCodec`'s leading version byte. A format bump is a major
  release with a re-sealing migration tested on an emulator (`core/data` androidTest) and a note in the changelog.
  Rolling back across such a bump is safe for the profiles and *loud* for the credentials: `unseal` reports a blob of
  an unknown format as `CredentialUnreadableException(permanent = true)`, the profile stays, the app asks the user to
  enter the sign-in again (the M3 behaviour for an unreadable credential). The rollback's changelog and the
  known-issue notice say so.
- **Settings** (DataStore): keys are only added; a removed feature leaves its key unread, never repurposed.
- **The log** (`LogDatabase`, version 1): at most 24 hours old, so a `logs.db` written by a newer schema is **dropped
  and recreated** when an older release opens it — `LogDatabase.create` is the one builder with
  `fallbackToDestructiveMigrationOnDowngrade(true)` (`LogRepositoryTest.aLogDatabaseFromANewerVersionIsDroppedAndRecreatedNotRefused`);
  without it Room would refuse the file and the Logs screen would crash on every visit after a rollback across a
  log-schema bump. The changelog says so when it happens.
- The data-behaviour lock changes with any of these, which forces the policy review and so a conscious decision.

## 6. The test of this document

Before the first production release, the owner does §4 once on the internal track with a throwaway version built
from the tag — **`1.0.1 (12)`, made with `bump_version.py 1.0.1` on a branch, committed and merged to `main` like any
release**, since the code 12 is consumed on Play for good once uploaded and the next real release must then be
`1.0.2 (13)` (`bump_version.py` counts from `app/build.gradle.kts`); its history row says "internal only: rollback
drill" in *Notes* and its changelog section "Rollback drill; no change". Uploaded to the internal track, installed over
1.0.0 (11) on the owner's phone: the profiles and settings still there afterwards, Connect works, the Logs screen
opens. The **halt** is rehearsed on the closed-testing track, which supports staged rollouts (the internal track does
not: `incident-response.md` §6). The owner writes the date here: ‹date›.
