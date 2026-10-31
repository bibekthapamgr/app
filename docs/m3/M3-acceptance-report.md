# M3 — Proxy profiles and secure storage: acceptance report

**Status: DRAFT — the CI runs of the final commit and the independent review are still in progress. Nothing in this report is accepted yet; results read "Pending" until their evidence is in.**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m3-profiles-secure-storage`, pull request #2 |
| Verified commit | Pending |
| CI runs of that commit | Pending |
| Build environment | ubuntu-24.04 runner · Temurin JDK 21 · Gradle 9.8.2 (wrapper, distribution SHA-256 pinned) · Android Gradle Plugin 9.5.0 with built-in Kotlin 2.4.21 · KSP 2.3.13 · compileSdk/targetSdk 36 · minSdk 29 |
| Test environment | JVM and Robolectric 4.16 (Android 15, API 35); screenshots with Roborazzi 1.56.0 on a Pixel 7 profile; Android emulators with the real Android Keystore, API 29 (Android 10, the app's minimum) and API 35 (Android 15), `google_apis` x86_64 images started by `reactivecircus/android-emulator-runner` v2.38.0 |

As in M2, the workspace this was written in cannot reach Google's Maven repository or Maven Central, so every Android build and test runs in GitHub Actions. ktlint, the secrets scan and the plain-Kotlin model, crypto and repository tests were also run locally before each push. CI reports failures, every test result, a sample database export and the shipped libraries as annotations, which can be read through the GitHub API.

The milestone began with a separate, CI-verified toolchain upgrade, proposed in the M2 report: Android Gradle Plugin 8.13 → 9.5.0, Gradle 8.14.3 → 9.8.2, Kotlin 2.2.20 → 2.4.21 (built into AGP 9), Roborazzi 1.56.0 and Lifecycle 2.10.0 (commits `2e1443b`, `b0f88d4`).

## Acceptance criteria

| Criterion (brief) | Evidence | Result |
|---|---|---|
| Multiple profiles survive app restart | `StoredProfilesTest.profilesSurviveAnAppRestart`: three profiles saved, the database closed, then a new database object and repository opened on the same file, which is what a new process does: all three come back in order with their settings, and both sign-in profiles decrypt to their own username and password. `KeystoreOnDeviceTest.profilesAndCredentialsSurviveARestartAndNoFileHoldsThemInClear` does the same on the API 29 and API 35 emulators, with a new Android Keystore handle as well. Both restarts happen inside the test process; the operating system's own process restart is not simulated. `DatabaseMigrationTest` shows profiles and credentials also survive the move from database version 1 to 2 | Pending |
| Invalid configuration cannot be saved as a valid profile | `ProfileValidator` checks name (≤ 40 characters), server (hostname or IP literal), port (1–65535), required username and password, protocol limits (HTTP usernames without `:`, credentials ≤ 255 bytes for SOCKS5 and ≤ 1 024 bytes for HTTP), TLS server name, control characters and broken text such as half an emoji, with only the digits 0–9 in addresses and ports (`ProfileValidatorTest`, 19 tests). The repository validates again before writing, and Duplicate validates the name it is given, so no caller can store an invalid profile: `ProfileRepositoryTest.invalidFormsAreNeverSaved` gets `InvalidProfileException` with the field errors and nothing is stored; `aCopyNameIsValidatedLikeATypedOne`. The form shows the errors after the first Save and keeps them live while they are fixed (`AddEditProxyViewModelTest`, `AddEditProxyScreenTest`); through the real app, an invalid form stays open and says what to fix (`NavigationTest.invalidFormStaysOpenAndSaysWhatToFix`) | Pending |
| Credentials are encrypted at rest and not present as plaintext in database exports | Username and password are sealed together with AES-256-GCM under an Android Keystore key made for them alone; only ciphertext, IV, a format number and the key's id reach Room (ADR-0006). `StoredProfilesTest.noCredentialIsInTheDatabaseFilesOrAnExportOfIt` exports every column of every row of every table, and reads the database files themselves, once while the database is open (with its write-ahead log, which the test checks is there) and once after closing, looking for the username and password as text and as UTF-8 and UTF-16 bytes: not found, while the server, searched the same way, is found. On the emulators, `KeystoreOnDeviceTest` searches **every file in the app's data directory** the same way, before and after a restart: not found. CI prints the exported table (below) | Pending |
| Editing one profile does not alter others | `ProfileRepositoryTest.editingOneProfileLeavesTheOthersUntouched`: after one profile's server, name and password are changed, the profile is duplicated and the copy deleted, every other profile's record (settings, ciphertext and IV, compared byte for byte) and key are unchanged and still decrypt to their own credentials. Each ciphertext is bound to its profile id (associated data): the same key and bytes presented as another profile's are refused (`CredentialCipherTest.sealedCredentialsOpenOnlyForTheirOwnProfile`, and on the emulators `KeystoreOnDeviceTest.keysLiveInTheAndroidKeystoreAndCannotBeExported`) | Pending |
| Deleting a profile removes its credential material | Deleting removes the row and then the key its credentials were sealed under, so any copy of the old ciphertext left in free pages or flash can never be decrypted again. `ProfileRepositoryTest.deletingAProfileDeletesItsCredentialsAndItsKey`, `StoredProfilesTest.deletingAProfileLeavesNothingThatCanBeDecrypted` (the saved ciphertext no longer opens; other profiles still do), `KeystoreOnDeviceTest.deletingAProfileDeletesItsKeystoreKey` (the alias is gone from the Android Keystore on API 29 and 35). The same holds when credentials are replaced: a new password is sealed under a new key and the old key is deleted (`changingTheCredentialsMakesTheOldCiphertextUnreadable`). Turning sign-in off deletes the key too (`turningSignInOffDeletesTheKey`), and keys no row uses, left by an interrupted change, are removed at the next start (`keysNoCredentialsUseAreDeletedAtStart`) | Pending |
| Test Connection displays truthful result states and never fabricates success | The screen shows only what the `ConnectionTester` returns. M3 has no engine, so the app's tester answers "Testing isn't available yet … Nothing was sent to your proxy" (board D5 below). A result is cleared when the form changes and dropped if the form changed while testing; a tester that throws is shown as a failure (`INTERNAL`), never a pass; an invalid form is not tested (`AddEditProxyViewModelTest`: `testConnectionShowsOnlyWhatTheTesterFound`, `aTestInProgressIsDroppedWhenTheFormChanges`, `aTesterThatBreaksIsReportedAsAFailureNeverAPass`, `anInvalidFormIsNotTested`; `AddEditProxyScreenTest.testConnectionNeverShowsAPassItWasNotGiven`; `RepositoryTest.connectionControllerAndTesterAreHonestAboutThisBuild`) | Pending |

### Sample database export (from CI)

The `profiles` table as `StoredProfilesTest.noCredentialIsInTheDatabaseFilesOrAnExportOfIt` exported it in CI (run 41161058796), after saving a sign-in profile, editing it with the password left empty and duplicating it. The username and password were generated for that run; neither appears in the export, and every other column is plain settings:

```
Test credentials, generated for this run only: username alice-3aa55036-d8a8-41c4-ac0e-01a88f75c264, password pw-6f303f7d-06a8-42bb-89a4-a81cdbedbbc8
table profiles: id|name|protocol|host|port|tls_mode|tls_server_name|requires_sign_in|credentials_ct|credentials_iv|credentials_format|created_at|updated_at
271fc3bc-…|Office 2|HTTP|203.0.113.10|3128|None|NULL|1|2661a6c20d9e50163b5df861e20ae54cb0afe401…42231a281004|d7f2d9322992aab94e76ab77|1|1793414754998|1793414755076|
afe001ec-…|Office (copy)|HTTP|203.0.113.10|3128|None|NULL|1|c6a5c2cf8f1d873e87bee2a5910a2daa17a8abf0…7f14d102e6294dd2|55d86d7064c7d41c60b9e4df|1|1793414755094|1793414755094|
```

(Ciphertexts shortened here; the annotation has them in full.) The copy has its own IV and ciphertext because it is sealed under its own key.

## The brief's tasks

| Task | Where |
|---|---|
| 1. `ProxyProfile` model: id, name, protocol, host, port, username reference, TLS mode, timestamps, non-secret settings | `core/model/.../ProxyProfile.kt`: id (UUID), name, protocol, host, port, `tlsMode` (`None`/`Tls`), `tlsServerName`, `requiresSignIn`, `createdAtMillis`, `updatedAtMillis`. The username is a credential here: it is stored only inside the profile's sealed credentials, which the profile refers to by its id (Keystore alias `proxyflow_credentials_<id>`, columns `credentials_*`), so the model itself never carries it (ADR-0006) |
| 2. HTTP CONNECT and SOCKS5 | `ProxyProtocol.HTTP` / `SOCKS5`, chosen on the form; validation follows each protocol's limits |
| 3. Validation | `ProfileValidator` (form and repository both use it) |
| 4. Create, edit, duplicate, delete, select active, list | `ProfileRepository`; Profiles list with the actions menu (board C2 below); Duplicate copies settings and credentials under a new id, name and key ("Office (copy)", "Office (copy 2)", in the phone's language); the active profile is the chosen one while it exists, otherwise the oldest |
| 5. Non-secret fields in Room | `ProxyFlowDatabase` version 1, schema in `core/data/schemas/` (ADR-0009) |
| 6. Keystore-backed encryption; key invalidation and recovery documented | `core/data/.../crypto/`; ADR-0006 §5: a missing, invalidated or damaged key makes the credentials *unreadable*, the form asks for them again (board H3) and saving seals them under a new key, deleting the unusable one (`aKeyAndroidRejectsIsReplacedWhenTheSignInIsEnteredAgain`, on the JVM and on both emulators with a key the Keystore refuses); a possibly temporary Keystore failure is tried once more, then reported as temporary without changing anything (`aTemporaryKeystoreFailureNeverCopiesOrDropsCredentials`); nothing falls back to plaintext |
| 7. Show/hide password; credentials never in logs, analytics or clipboard | Show/hide on the password field. The app has no logging calls and no analytics. The password field offers no Copy or Cut, even while shown; the username field's Copy and Cut put nothing on the clipboard (Cut still removes the selection) (`ComponentsTest.credentialFieldsNeverPutTheirTextOnTheClipboard`, with an ordinary field as control). `toString()` of the form and of decrypted credentials is redacted; the form never writes credentials to saved instance state; the Add/Edit window is `FLAG_SECURE` |
| 8. Test Connection interface for the engine | `ConnectionTester` (`core/data`); M4's engine implements it. A profile being edited is tested under its id, so a password left empty can stand for the saved one under the same rule as saving |
| 9. Migration tests | The review's key change needed a schema change, so M3 already has one: version 2 adds `credentials_key`. `DatabaseMigrationTest.version1ProfilesAndTheirCredentialsSurviveTheMigrationTo2` creates a version-1 database from its committed schema with a sign-in profile, migrates it, has Room check it against version 2, then reads it through the app's database and repository: settings and credentials intact. A database from a newer version fails loudly and keeps its rows (no destructive fallback). CI fails when the current schema changes without its committed file (ADR-0009) |

## Tests per module

Pending: counts from the final run.

## Deliverables

| Deliverable | Where |
|---|---|
| Profile screens | `feature/profiles`: Profiles list with Use, Edit, Duplicate and Delete; Add/Edit form with sign-in, show/hide password, TLS, Test connection and the unreadable-credentials explanation |
| Data model | `core/model` (`ProxyProfile`, `ProxyProfileInput`, `ProfileValidator`, `ProfileNames`, `ConnectionTestResult`), Room entity `ProfileEntity` |
| Repository | `ProfileRepository` / `DefaultProfileRepository` with `RoomProfileStore` and `DataStoreActiveProfileStore` |
| Secure credential store | `AesGcmCredentialCipher`, `CredentialCodec`, `AndroidKeystoreSecretKeyStore` |
| Unit tests | Pending: counts from the final run |
| Decisions | ADR-0006 (credential storage, revised and Accepted), ADR-0009 (local storage and migrations, Accepted) |
| Shipped libraries | `docs/m3/release-runtime-classpath.txt` — 129 libraries; new: Room 2.8.5 and androidx.sqlite 2.6.2 (Apache-2.0) |

## Screens

New and changed boards, recorded in CI and committed under `app/screenshots/`; every CI run renders them again and fails if one differs.

| Board (docs/m1) | Light | Dark |
|---|---|---|
| C2 Profiles, actions menu with Duplicate | `C2_profile_actions_light.png` | — |
| C2 Profiles, list | `C2_profiles_list_light.png` | `C2_profiles_list_dark.png` |
| C3 Delete confirmation (now says the key is deleted too) | `C3_delete_confirm_light.png` | — |
| D1 Edit proxy, saved password kept | `D1_edit_proxy_light.png` | `D1_edit_proxy_dark.png` |
| D5 Testing | `D5_testing_light.png` | `D5_testing_dark.png` |
| D5 Test result in M3: not available, nothing sent | `D5_test_unavailable_light.png` | `D5_test_unavailable_dark.png` |
| H3 Saved sign-in unreadable, asked for again | `H3_credentials_unreadable_light.png` | `H3_credentials_unreadable_dark.png` |
| B2 Home, G1 About, D1/D4/D8 forms | Updated copy ("This build saves your proxies but doesn't connect yet"; how credentials are protected) and the Test connection button | |

## What CI found, and what changed

The M3 code was first compiled in CI ([run 41159805755](https://github.com/bibekthapamgr/app/actions/runs/41159805755), commit `bd6212e`). Formatting, Lint with warnings as errors, both builds, the release manifest, all 31 `core/data` tests (Room, Robolectric, migrations) and the emulator tests on API 29 and API 35 passed on that first run. It found:

| Found | Change |
|---|---|
| Two app tests tapped *Add proxy* while Home was still loading the profiles from Room (in M2 they came from memory, so the button was there at once) | The app-test helper waits until the app has stopped loading before a test starts; Home's loading is off the main thread by design |
| A Test connection test checked a banner's second line while it was below the visible area | It scrolls to each line it checks |
| The clipboard test could not copy from the password field: Compose offers no Copy or Cut on a password field at all, even while it is shown | The test now checks exactly that, and that Copy on the username field puts nothing on the clipboard while an ordinary field's Copy does |
| The Room schema check failed, as designed, because the schema file was not committed yet | The file exported in CI was reviewed and committed (`core/data/schemas/…/1.json`) |
| Screens changed by this milestone, and new boards without reference images | Recorded in CI, reviewed, committed (table above) |

Also changed after reading the code again before the review: Duplicate, Delete and Use now report a storage or Keystore failure once (a short message) instead of crashing the app, and a tester that throws is reported as a failure (`INTERNAL`).

## Independent review: findings and changes

A reviewer who had not written the code read the source, tests, CI and design records at commit `d47e82d`, ran the plain-Kotlin tests, and wrote probe tests and mutations for its suspicions. It found 13 defects (1 High, 5 Medium, 7 Low); nine were reproduced by its probes. All 13 were fixed; the tests named here are new or extended to prove each fix.

| Severity | Found | Change |
|---|---|---|
| High | A key that still existed but could no longer be used (invalidated by Android, or damaged) was reused under the same alias, so after the form asked for the sign-in again (H3), every Save failed | Every set of credentials is now sealed under a **new** key; the unusable one is deleted after the save (`aKeyAndroidRejectsIsReplacedWhenTheSignInIsEnteredAgain`, JVM and both emulators, the latter with a key the Keystore refuses for AES-GCM) |
| Medium | Changing a password re-sealed it under the same key, so old ciphertext left in free pages stayed decryptable, which undercut the reason for per-profile keys | A new key per seal, the old key deleted after the row is written; a failed write deletes the new key instead. Schema version 2 stores each row's key id, with a tested migration (`changingTheCredentialsMakesTheOldCiphertextUnreadable`, `aFailedWriteLeavesTheStoredCredentialsAndNoNewKey`, `DatabaseMigrationTest`) |
| Medium | A Keystore failure that fails twice was treated like an unreadable key: Duplicate silently made a copy without credentials, and a rename asked for the password with a misleading reason | Reads now tell *unreadable* from *temporarily unavailable*; Duplicate, a save that needs the old password, and opening the form fail with a message instead, and connecting gets `TemporarilyUnavailable`, not H3 (`aTemporaryKeystoreFailureNeverCopiesOrDropsCredentials`, `aProfileThatCantBeReadIsShownAsSuchAndNeverSavedOver`) |
| Medium | Duplicate stored its name without validation; copy names and the 40-character cap could split an emoji in half; a translated copy pattern without a number looped forever | Duplicate validates the name; names are cut only between whole characters; the validator refuses half characters; the copy-name search always ends (`aCopyNameIsValidatedLikeATypedOne`, `namesAreCutWithoutBreakingAnEmoji`, `copyNamesStayUniqueAndShortEnough`) |
| Medium | No test proved the associated data binds a ciphertext to its profile: with it removed, every test still passed, because each profile also had a different key | Now that the key id is stored apart from the profile id, the tests present the same key and bytes as another profile's and expect a refusal, on the JVM and on the emulators |
| Medium | `DatabaseMigrationTest`'s first test compared the schema file with itself | Replaced by a real migration test and a test that opens a database built from the current schema file with the app's own entities; the report and ADR-0009 now say that the committed file is compared with the code by the CI schema step |
| Low | HTTP credentials had no length limit, so a very long one passed validation and then failed every Save | HTTP usernames and passwords are limited to 1 024 bytes each, with their own error text (`httpCredentialsAreLimitedTo1024Bytes`) |
| Low | Digits from other scripts (Arabic-Indic, full-width) were accepted in IP addresses and ports | Only 0–9 (`rejectsMalformedServers`, `portMustBeBetween1And65535`, `thePortTakesOnlyTheDigits0To9`) |
| Low | The edit form had no loading state: input typed before the profile loaded could be saved over it with form defaults (sign-in off, which deletes the key), and a load error crashed the app | The form shows a loading state and accepts no input until the profile is read; a load error is shown and nothing can be saved; whether the form was restored is decided before any keystroke (`theFormWaitsForTheProfileAndIgnoresInputUntilThen`, `aProfileThatCantBeReadOffersNoFormAndNoSave`) |
| Low | ADR-0006 said Copy and Cut "do nothing" in the username field, but Cut removes the selected text | The ADR and this report say what happens; the test checks Cut as well as Copy |
| Low | Tests were cited for more than they prove: Activity recreation cited as an app restart, file scans without a positive control, the write-ahead log never present during the scan, an in-process restart not called that | Citations corrected; the scans check the server *is* found, run while the database is open (asserting the write-ahead log exists) and after closing; restarts are described as in-process |
| Low | The "test passed" text would claim a sign-in for profiles that have none | "Your proxy opened a test connection in … ms" |
| Low | The active profile falls back to the oldest one, which a connection started without the user (Always-on, M5) should not do | `chosenProfileId` gives only an explicit choice, kept apart from the display fallback (`onlyAnExplicitChoiceIsReportedAsChosen`) |

The reviewer also listed what it checked and found correct: no path puts credentials in logs, saved state, DataStore or `toString`; the key parameters; the keep-password rule enforced in the repository; the lifecycle of sign-in off, delete and the sweep; Test connection; `FLAG_SECURE`; backups; no destructive fallback; the CI schema check and device job.

## Decisions and deviations

- **Username stored as a secret.** The brief lists a "username reference" on the profile. The username identifies a paid proxy account, so it is sealed together with the password and the profile refers to them through its id; the model never carries it in clear (decided in M1, ADR-0006).
- **A new key for every set of credentials** (ADR-0006, revised in M3 after the independent review): deleting a profile or changing its password deletes the only key that can read the old ciphertext, and a key Android has invalidated is never used again.
- **Room 2.8, Room 3 before release** (ADR-0009): Room 3.0 has been stable since July 2026 and Room 2.x is in maintenance mode. The move is a separate change with its own migration evidence, due before the first release.
- **ADR-0006 and ADR-0009 accepted** by the developer under the owner's delegation (as for ADR-0008); the owner can reopen either.

## Limits of this evidence

- The Android Keystore was exercised on emulators (API 29 and 35, Google's x86_64 images), not on physical phones. Keystore implementations differ between manufacturers; tests on phones from several manufacturers belong to M10. StrongBox is not used.
- JVM and Robolectric tests use software AES keys in place of the Keystore, which the JVM does not have; everything else in them (Room, DataStore, the screens) is real.
- Backups and device transfers are off by the manifest and the data-extraction rules (checked on the release build in CI). A restore onto another phone was not performed; by design it could only bring back unreadable credentials (ADR-0006 §6), which the repository tests cover with lost keys.
- Text typed into any field passes through the phone's keyboard app. The password field is marked as a password, so keyboards do not learn or suggest it; the username field is not, so a keyboard may learn it.
- Nothing connects yet: Test connection always answers that testing isn't available (M4 adds the engine, M5 the VPN service).

## How to check it yourself

- Pull request #2 → **Checks**, or the run pages above. The `build` job's annotations hold the test counts, the database export and the shipped libraries; each `device` job's annotations list every emulator test and its result.
- Locally, with Android Studio's JDK and SDK 36: `./gradlew testDebugUnitTest :core:model:test`, and with an emulator or phone connected, `./gradlew :core:data:connectedDebugAndroidTest`.
