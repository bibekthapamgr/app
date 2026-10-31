# ADR-0009 — Local storage: Room for profiles, exported schemas, tested migrations

**Status:** Accepted 2026-10-31 (decision delegated by the owner) · **Date:** 2026-10-31

## Context

M3 makes profiles permanent. They must survive restarts and app updates; invalid profiles must never be stored; credentials are stored only as ciphertext (ADR-0006); later milestones add fields (test results in M4, statistics in M7). A schema change that loses or corrupts the user's profiles after an update is the failure to design against. The app has no backend, so the phone holds the only copy.

## Decision

1. **Room** for profiles: one database, `proxyflow.db`, in the app's private storage, built by `ProxyFlowDatabase.create` in `core/data` (the only module that sees Room). One table, `profiles`: `id` (UUID, primary key), `name`, `protocol`, `host`, `port`, `tls_mode`, `tls_server_name`, `requires_sign_in`, `credentials_ct`, `credentials_iv`, `credentials_format`, `credentials_key` (version 2), `created_at`, `updated_at`. Enums are stored by name. Nothing secret is stored: the `credentials_*` columns hold ciphertext and the id of the Keystore key that seals it (ADR-0006).
2. **Preferences DataStore** for settings and the active profile id (`active_profile_id`). The active profile is the chosen one while it exists, otherwise the oldest profile. No secret is ever stored there.
3. **Schemas are exported and committed.** `exportSchema = true`; the Room Gradle plugin writes `core/data/schemas/app.proxyflow.core.data.db.ProxyFlowDatabase/<version>.json`. CI fails when the schema generated from the entities differs from the committed file, and publishes the generated file on `ci-screenshots/<branch>` for review.
4. **Every schema change is a new version with a tested migration.** The change raises `version`, adds a `Migration` (or an `AutoMigration`), commits the new schema file, and adds a `MigrationTestHelper` test in `DatabaseMigrationTest` that creates each earlier committed version with data, migrates it, validates the result against the new schema and reads the data back through the app's own database and repository. The first one came within M3: version 2 adds `credentials_key` (ADR-0006, after the independent review), and `MIGRATION_1_2` gives version-1 rows the key they were sealed under (key id = profile id). Version 1 never left CI, so the migration is not needed by any phone; it is kept as the worked example and test of this rule. Older schema files are never edited: the build rewrites only the current version's file, and CI fails when that differs from the committed one.
5. **No destructive fallback.** No `fallbackToDestructiveMigration*`: a missing migration fails loudly instead of deleting the user's profiles. A database written by a newer app version (an older APK installed over it) is not opened and not wiped; `DatabaseMigrationTest` checks both.
6. **Room 2.8 now, Room 3 before release.** Room 3.0 has been stable since July 2026 and Room 2.x is in maintenance mode (bug fixes and dependency updates only). M3 uses Room 2.8.5: the app code uses only entity and DAO annotations, `suspend` and `Flow` DAO functions and the database builder, and the tests use the established 2.x testing APIs. Room 3 moves to the `androidx.room3` package, requires a `SQLiteDriver` and changes `MigrationTestHelper` to driver connections; that move is a separate, reviewed change with its own migration-test evidence, done before the first release so that no installed database ever needs it.

## Alternatives considered

| Option | Assessment |
|---|---|
| **Room (chosen)** | Compile-time checked SQL, observable queries as `Flow`, schema export and migration testing built in |
| Proto DataStore with a list of profiles | No queries or migrations framework; every change rewrites the whole file; fine for settings, weak for growing records |
| SQLDelight | Comparable; SQL-first, but adds a second persistence style next to Jetpack DataStore for no gain here |
| JSON file | No schema, no migration discipline, easy to corrupt on a crash mid-write |
| Room 3.0 directly | The long-term target (point 6); its driver-based API needs its own port and evidence, kept out of M3's credential work |

## Consequences

- A schema change costs a migration and a test; a forgotten schema file or migration fails CI before review. Because the build regenerates the current schema file before the tests run, the comparison of that file with the committed one is the CI schema step's job, not a test's.
- An update can never silently empty the profile list; a broken migration shows up as a crash in testing instead of data loss in the field.
- The Room 3 move is open work (owner-visible in the M3 acceptance report) and must land before M11.

## Evidence

`core/data/schemas/…/1.json` and `2.json`; `DatabaseMigrationTest` (version-1 profiles and their credentials survive the migration to version 2 and are read by the app's database and repository; a database created from the current schema file opens with the app's entities; a newer database fails loudly and keeps its rows); `StoredProfilesTest` (profiles survive a restart of the database); the CI schema check. Run IDs in `docs/m3/M3-acceptance-report.md`.
