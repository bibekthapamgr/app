# Release history

Every version of ProxyFlow that reached Google Play (or is on its way), newest first: the verified commit, the green
runs that are its regression results, the date it reached production, and the crash-free rate Android vitals showed
over the 28 days after. Brief acceptance "each release includes changelog and regression-test results":
`scripts/release/check_release_ready.py` requires a row here and a section in `CHANGELOG.md` for the version being
built, and CI runs it on every push. The columns are fixed (the script reads them): a row with `pending` in the commit
or run columns is a release being prepared; `--production` refuses it.

| Version | Verified commit | `android` run | `release` run | On Play (production) | Crash-free sessions, 28 days | Rollout | Notes |
|---|---|---|---|---|---|---|---|
| 1.0.0 (11) | 75ce907 | 42366997269 | 42366997278 (attempts 1–3) | pending (closed testing first: `docs/m11/release-checklist.md` §4) | pending | — | The first release; M1–M11; `docs/m11/release-notes.md`; defects in `docs/m11/defect-register.md` and the registers of M4–M10 |

## What a row's runs are

- **`android` run**: the build job (651 JVM tests, Lint, the screenshots with their accessibility checks, the release
  manifest, OSV, the evidence scripts' tests, the listing and asset checks, the data-behaviour lock, the release
  readiness) and the eight device jobs (API 29–36: the instrumented tests, M8's failure injection, M10's scenarios and
  every evidence script of M5–M10 against the lab with packet captures) — the regression suite of
  `docs/m12/qa-process.md`.
- **`release` run**: the signed bundle, its verification, and the signed-bundle smoke on API 29 and 36 — green in three
  attempts of the same commit for a release (the project's rule since M5).
- **Crash-free sessions**: Play Console → Quality → Android vitals → Crashes and ANRs, "crash-free sessions", the 28
  days after the production date; the bar is 99.8 % (`docs/m10/compatibility-matrix.md` §4). Written in by the owner.
- **Rollout**: the staged-rollout steps the release went through and their dates (`docs/m12/rollback.md`): e.g.
  "5 % 2026-12-01 → 20 % 12-03 → 50 % 12-05 → 100 % 12-08", or "halted at 20 % 12-04 (M12-x), superseded by 1.0.1 (12)".

## Tags

Each production release is tagged `v<versionName>` on the verified commit (`git tag -a v1.0.0 75ce907 -m "1.0.0 (11)"`,
pushed with `git push origin v1.0.0`), so the changelog's links resolve and a hotfix branch has a base.
