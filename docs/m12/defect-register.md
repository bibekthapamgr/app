# M12 — Defect register

**Milestone:** M12 · Every defect M12's work, its reviews and its CI runs found. Severity as M11's register: **critical** (a process that would let a secret or a leak through), **high** (a check that passes when it should not, a runbook step that would lose users' data or break a release), **medium** (a check that fails without a defect or misses a case; a document that misleads), **low** (cosmetic). The register's columns are the ones every maintenance release's register uses from now on (`docs/m12/triage.md` §3).

Found by: M12's own CI runs and the independent reviews of the work (**A**, the support and security process and the maintenance automation; **B**, the runbooks and the release discipline against the code and Google Play's rules) and the re-reviews of their fixes.

## Writing the milestone

| # | Severity | Found | Defect | Fix | Evidence | Status |
|---|---|---|---|---|---|---|
| M12-1 | high | Writing `docs/m12/rollback.md` against `DatabaseMigrationTest` | The first draft said a rollback release "reads the columns it knows": Room refuses a database whose version is higher than the code's (the test `aDatabaseFromANewerVersionFailsLoudlyAndKeepsItsData` proves the refusal and that nothing is deleted), so a rollback across a schema bump would leave users with an app that cannot open and profiles they cannot read | The rollback procedure ships a downgrade migration with a test when the bad release bumped the schema (§4 step 2a, §5); a destructive downgrade is forbidden; ADR-0017 decision 9 | `docs/m12/rollback.md` | Fixed |
| M12-2 | low | Writing `docs/m12/rollback.md` | The first draft put 1.0.0 (11) through a staged rollout; Google's page says staged rollouts apply to updates only, not to an app's first publishing | The first release goes to production whole after closed testing; the steps apply from the second release | Google's page, read 2026-11-08 | Fixed |

## CI runs

| # | Severity | Found | Defect | Fix | Evidence | Status |
|---|---|---|---|---|---|---|
