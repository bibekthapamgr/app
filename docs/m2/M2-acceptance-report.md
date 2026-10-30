# M2 — Android foundation: acceptance report

**Status: every M2 acceptance criterion passes in CI, in three runs of the same commit. Ready for the product owner's review (pull request #1).**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m2-android-foundation`, pull request #1 |
| Verified commit | [`540c1b0`](https://github.com/bibekthapamgr/app/commit/540c1b0ed6123c27f8194a0475f4279220d84571) — code, tests and reference screenshots. Later commits on the branch change only this report |
| CI runs of that commit | `android` [run 41102685953](https://github.com/bibekthapamgr/app/actions/runs/41102685953), attempts [1](https://github.com/bibekthapamgr/app/actions/runs/41102685953/attempts/1), [2](https://github.com/bibekthapamgr/app/actions/runs/41102685953/attempts/2) and [3](https://github.com/bibekthapamgr/app/actions/runs/41102685953/attempts/3), all green · `secrets` runs [41102676863](https://github.com/bibekthapamgr/app/actions/runs/41102676863) (push) and [41102685915](https://github.com/bibekthapamgr/app/actions/runs/41102685915) (pull request), green |
| Build environment | ubuntu-24.04 runner · Temurin JDK 21 · Gradle 8.14.3 (wrapper, distribution SHA-256 pinned) · Android Gradle Plugin 8.13.0 · compileSdk/targetSdk 36 · minSdk 29 |
| Test environment | Robolectric 4.16 running Android 15 (API 35) on the JVM; screenshots with Roborazzi 1.50.0 in native graphics mode on a Pixel 7 profile |

This milestone was written in a cloud workspace that cannot reach Google's Maven repository, Maven Central or the Gradle servers, so nothing Android-specific could be compiled there. Every build, lint run and test therefore runs in GitHub Actions on the owner's repository; ktlint 1.7.1, the secrets scan and the plain-Kotlin model and repository tests were also run locally before pushing. CI logs and artifacts can't be downloaded from that workspace either, so the workflow also reports failures, test counts, APK sizes, permissions and the dependency list as annotations, which can be read through the GitHub API (`scripts/ci_error_digest.py`, `scripts/ci_build_facts.py`).

## Acceptance criteria

| Criterion (brief) | Evidence | Result |
|---|---|---|
| Debug build compiles on the agreed JDK/Android Studio setup | `:app:assembleDebug` with JDK 21 and AGP 8.13.0: `app-debug.apk` 31,465,326 bytes. `:app:assembleRelease` with R8: `app-release-unsigned.apk` 2,751,805 bytes | **Pass** |
| All screens navigate without crashes | `NavigationTest` (5 tests) through the real `MainActivity`, navigation graph and ViewModels: every bottom-bar tab; Settings → About → Back; Home → Add proxy → Save → Home shows the proxy; Change → Profiles → Edit → Back; system Back on an edited form asks *Discard changes?*, on an untouched form it just closes; an invalid form stays open and says what to fix; a double tap opens the form only once | **Pass** |
| UI state is not stored only inside Activity | `StateRestorationTest` (3): a half-filled form, saved profiles and the chosen theme survive Activity recreation. `AddEditProxyViewModelTest`: the form is restored after process death from `SavedStateHandle`; the username and password are never written there; an edit whose profile is gone after process death comes back as an Add form that asks before discarding | **Pass** |
| No network work runs on the main thread | M2 has no network code and no `INTERNET` permission, so the app cannot open a socket at all. The release build's merged manifest is checked in CI (`scripts/check_release_manifest.py`): its only permission is `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, the signature-level permission AndroidX Core declares to keep the app's own receivers private; `ManifestTest` checks the same on the debug build. Debug builds turn on StrictMode with a crash on main-thread network use; DataStore does its file I/O off the main thread | **Pass** |
| Unit tests and static checks run repeatably | 98 tests in 9 modules, `spotlessCheck` (ktlint), `:app:lintDebug` with warnings as errors, the release manifest check, and every screen compared with its committed image: all green in three runs of the verified commit (attempts 1–3 of `android` run 41102685953) | **Pass** |
| No secrets or signing credentials are committed | The `secrets` workflow runs on every push and pull request, whatever changed. It scans the current files and every commit in the history (22 commits at the verified commit) for key and keystore files, signing properties, private keys, common token formats and passwords written as literals; findings name the file and line, never the value, and a scan that fails to run fails the check. `.gitignore` covers keystores and `keystore.properties`; release signing reads an untracked file or CI secrets only. The repository is public, so this matters from the first commit | **Pass** |

### Tests per module

From the verified commit's runs (all three identical):

| Module | Tests | What they cover |
|---|---:|---|
| `app` | 24 | Navigation through the real `MainActivity` (5); form, profiles and theme across Activity recreation (3); permissions and backups in the manifest (2); screenshots of every screen, light and dark (14) |
| `core/data` | 5 | Theme stored with DataStore and read back by a new instance; profiles added, updated, deleted and made active; a saved password kept only for the same proxy account and erased when sign-in is turned off; the connection controller cannot connect |
| `core/designsystem` | 8 | Password field show/hide, error text replacing the hint, a loading button that cannot be pressed twice, dialogs and list rows (6); compiled colours equal the design tokens and every required pair meets WCAG 2.2 AA (2) |
| `core/model` | 16 | Profile validation (hosts and IPv6 literals, ports, credentials per protocol, TLS server name), form ↔ profile conversion, when a saved password may be kept, credentials redacted from `toString` |
| `feature/about` | 2 | Version, scope and limits shown; Back leaves |
| `feature/home` | 5 | Empty state leads to Add proxy; Connect disabled and explained; the active profile shown (screen 2, ViewModel 3) |
| `feature/logs` | 4 | Empty state; levels in words, not only colour; newest first (screen 2, ViewModel 2) |
| `feature/profiles` | 29 | Add/Edit form (screen 7, ViewModel 17): errors only after the first Save, saved once, digits-only port, saved state and process death, credentials never in saved state, password rules, missing profiles; Profiles list (screen 3, ViewModel 2): order, active profile, delete only after confirmation |
| `feature/settings` | 5 | Theme chosen from a dialog and stored; About opens from Settings (screen 3, ViewModel 2) |
| **Total** | **98** | 0 failed, 0 skipped |

## Deliverables

| Deliverable | Where |
|---|---|
| Runnable source project | `app/`, `core/`, `feature/`, Gradle files and wrapper |
| Reusable UI components | `core/designsystem/`: primary, secondary, text and destructive buttons; card; text and password fields; switch, radio and choice-chip rows; list row; confirm and choice dialogs; loading, empty and error states; banner; status pill; top bar. The theme is generated from the M1 design tokens and checked against them (`TokenColorsTest`, CI step "Design tokens and generated colours agree") |
| Build instructions | `README.md` |
| Initial CI configuration | `.github/workflows/android.yml`: design-token check, Gradle wrapper validation (no Gradle step runs without it), ktlint, Lint, tests, debug and release builds, release manifest check, screenshot comparison, build facts and an error digest. `.github/workflows/secrets.yml`: secrets scan of the files and the history on every push and pull request. Third-party actions pinned to commit SHAs; the jobs that run project code have a read-only token and keep none in their checkout |
| Architecture decision | `docs/adr/0008-app-architecture.md` (Proposed, awaiting the owner) |
| Reference screenshots | `app/screenshots/` — 25 images, every M2 screen in light and dark plus the three dialogs, compared with what the code renders on every CI run |
| Shipped libraries | `docs/m2/release-runtime-classpath.txt` — 120 libraries: 119 Apache-2.0, one BSD-3-Clause |

## Screens

All recorded in CI (Pixel 7 profile) and committed under `app/screenshots/`. Every CI run renders them again and fails if one differs from its committed image.

| Board (docs/m1) | Light | Dark |
|---|---|---|
| B1 Home, no proxy yet | `B1_home_empty_light.png` | `B1_home_empty_dark.png` |
| B2 Home, proxy chosen, not connected | `B2_home_disconnected_light.png` | `B2_home_disconnected_dark.png` |
| C1 Profiles, empty | `C1_profiles_empty_light.png` | `C1_profiles_empty_dark.png` |
| C2 Profiles, list | `C2_profiles_list_light.png` | `C2_profiles_list_dark.png` |
| C3 Delete confirmation | `C3_delete_confirm_light.png` | — |
| D1 Add proxy | `D1_add_proxy_light.png` | `D1_add_proxy_dark.png` |
| D3 Validation errors | `D3_validation_errors_light.png` | `D3_validation_errors_dark.png` |
| D4 Plaintext warning | `D4_plaintext_warning_light.png` | `D4_plaintext_warning_dark.png` |
| D8 Unsaved changes | `D8_unsaved_changes_light.png` | — |
| E1 Settings, theme dialog | `E1_settings_light.png`, `E1_theme_dialog_light.png` | `E1_settings_dark.png` |
| F1 Logs, empty / F2 with entries | `F1_logs_empty_light.png`, `F2_logs_sample_light.png` | `F1_logs_empty_dark.png`, `F2_logs_sample_dark.png` |
| G1 About & privacy | `G1_about_light.png` | `G1_about_dark.png` |

## What the first CI runs found, and what changed

The code had never been compiled before it reached CI. Everything below was found there and fixed on the pull request branch; each fix is its own commit.

| Found | Change |
|---|---|
| One compile error: `feature/profiles` used `LocalActivity` but compiled against the older activity-compose that Navigation brings in | Direct `activity-compose` dependency |
| 29 files not in ktlint's `android_studio` style | Formatted with ktlint 1.7.1 |
| Lint `DataExtractionRules` (minSdk 29 also needs backup rules for Android 10–11) and four unused strings | `fullBackupContent` rules that exclude everything; strings removed |
| The form is longer than a small phone screen, so a Save that found errors could leave the error summary above the visible area | A rejected Save scrolls the form back to the summary (`rejectedSaves` in the UI state) |
| Closing the form after Save used `dropUnlessResumed`, which can drop the close when Save finishes while the screen is not resumed, leaving a saved form open | The app pops the form by its route, which is safe to call twice |
| Two navigation tests sent a system Back in the same frame as the tap or keystroke before it, so the Back reached the previous frame's back handlers | The test helper lets the screen settle first. Temporary tests with a bare NavHost, the app shell in a plain activity and `MainActivity` on its own showed the app handles Back correctly once the screen has been drawn. (A commit message on the branch first blamed the library versions; the investigation that followed showed the cause was the test's timing.) |
| `testDebugUnitTest` skipped the tests of the plain-Kotlin `core:model` module | CI and the README also run `:core:model:test` |
| Dialog screenshots were captured before the dialog had drawn | Wait for idle before capturing |

activity-compose was also raised from 1.11.0 to 1.13.0 and Navigation from 2.9.5 to 2.9.8 while the Back tests were investigated. They are the current releases that still build with AGP 8.13 and compileSdk 36, and they were kept.

## Independent review: findings and changes

Once the checks first passed, a reviewer who had not written the code read the source, tests, CI and this report. Every finding was fixed on the branch (commits `8af3af2` and `6214ff9`) and is covered by the runs above.

| Severity | Found | Change |
|---|---|---|
| High | This report said **Pass** while its evidence still read "pending" | Evidence filled in from the runs of the verified commit |
| Medium | The secrets scan could pass silently when `grep` failed, missed common patterns, was skipped by docs-only changes and never looked at history | Rewritten (`scripts/check_secrets.py`, same entry point `check-secrets.sh`): a scan that fails exits 2, more key, token and password patterns with a self-test, findings never print the value, `--history` checks every commit. Own workflow (`secrets.yml`) on every push and pull request, without path filters |
| Medium | A saved password stayed with its profile when the server, port, protocol, username or TLS setting changed, so it could be sent to a different proxy | Leaving the password empty keeps the saved one only while all of those are unchanged; otherwise the form asks for it again and the repository erases the stored one. A new sign-in profile needs a password |
| Medium | A profile saved without a name stored the server as its name, so a later server change left the old server as the name | Empty names are stored empty; the screens show the server instead |
| Low | Manifest checks ran on the debug build only and did not cover the backup rules | `scripts/check_release_manifest.py` checks the release build's merged manifest in CI: permission allowlist, backups and device transfers off, not debuggable, no cleartext opt-in, nothing exported without a permission except the launcher |
| Low | The shipped-library list called every library Apache-2.0 | `datastore-preferences-external-protobuf` is BSD-3-Clause; corrected here and in `release-runtime-classpath.txt` |
| Low | Reference screenshots were recorded but never compared | CI compares every screen with its committed image (`verifyAndRecordRoborazziDebug`). Its first run ([41097916205](https://github.com/bibekthapamgr/app/actions/runs/41097916205)) failed on exactly the six light screens the changes in this table touched (C2, C3, D3, D8, F1, G1); the second ([41099505894](https://github.com/bibekthapamgr/app/actions/runs/41099505894)) on the four matching dark ones, which the first run had not reached. Both sets were reviewed and committed, and a changed image no longer stops its other theme from being compared |
| Low | The username was written to saved instance state, and `toString` of the profile and form printed the username and password | Neither credential is in saved state any more; both are redacted in `toString` |
| Low | After process death the form lost the password but still allowed saving without one; an edit whose profile was gone looked unchanged | Saving needs the password again (see above); a restored edit whose profile is gone becomes an Add form that asks before discarding and saves under a new id |
| Low | Wrong or misleading copy: the memory-only banner, About, the delete dialog, and a Logs "Log settings" button that opened the general Settings | Copy corrected; the button is gone until log settings exist (M7) |
| Low | CI scripts printed logs that could contain workflow commands; `spike.yml` used movable action tags | Workflow commands are switched off around quoted output; `spike.yml` actions pinned to commit SHAs |
| Low | README promised "fails closed" without its limits | README states what is and is not carried, and that while the app is not connected only Android's Always-on VPN with "Block connections without VPN" keeps traffic blocked |
| Low | A double tap could open the form twice | The form opens with `launchSingleTop` (`NavigationTest.aDoubleTapOpensTheFormOnlyOnce`) |

Also tightened while doing this: no Gradle step runs unless the wrapper passed validation, and the build job keeps no GitHub token in its checkout.

## Dependency versions and licences

| | Version |
|---|---|
| Android Gradle Plugin / Gradle | 8.13.0 / 8.14.3 |
| Kotlin / Compose compiler plugin | 2.2.20 |
| Compose BOM | 2025.10.00 (Compose 1.9.3, Material 3 1.4.0) |
| activity-compose · navigation-compose · lifecycle | 1.13.0 · 2.9.8 · 2.9.4 |
| DataStore · coroutines · serialization | 1.1.7 · 1.10.2 · 1.9.0 |
| Tests | JUnit 4.13.2 · Robolectric 4.16 · Roborazzi 1.50.0 · Turbine · kotlinx-coroutines-test |
| Static checks | Spotless 8.0.0 with ktlint 1.7.1 · Android Lint (AGP 8.13) |

The 120 libraries in the release APK are listed in `docs/m2/release-runtime-classpath.txt`. 119 are Apache-2.0. One, `androidx.datastore:datastore-preferences-external-protobuf` (Google's protobuf-lite, repackaged for DataStore), is BSD-3-Clause, which requires its copyright notice in the app's open-source notices; that screen is added before release (M11). Test-only dependencies (JUnit EPL-1.0, Robolectric MIT, Roborazzi and Turbine Apache-2.0) do not ship.

**Toolchain currency.** These versions build and test cleanly, but they are about a year old. The current stable line is AGP 9.5 with Gradle 9.x; Navigation 2.10 already requires AGP 9.2 and compileSdk 37, and Gradle 8 only receives security fixes now. Moving to AGP 9 changes the Kotlin plugin setup and the Android DSL in every module, so it is proposed as a separate, CI-verified change at the start of M3, before Room and Keystore are added.

## Scope notes (deliberate, stated in the app)

- **No connecting.** Home shows the chosen proxy and a disabled Connect button with "Connecting arrives with the VPN service in a later milestone." The `ConnectionController` seam is ready for M5.
- **Profiles in memory only.** They last while the app process runs, and the Profiles screen says so. M3 adds Room and Keystore-encrypted passwords behind the same `ProfileRepository`. Credentials never go into saved instance state, a saved password is only kept for the proxy account it was saved for, and the Add/Edit screen sets `FLAG_SECURE`.
- **Settings** shows what works now: the theme (System default, Light, Dark, stored with DataStore) and About.
- **Logs** shows its empty state; nothing writes logs until the engine exists (M4–M7), and log settings arrive with logging.
- **About** states what this build does and what the finished app will carry (TCP and DNS through the proxy; UDP and ping blocked; local devices unreachable; IPv6 only if the proxy supports it).

## Limits of this evidence

- Tests run on the JVM (Robolectric, Android 15), not on an emulator or a phone. Device and emulator runs begin in M5, where routing must be proven on real hardware.
- Robolectric draws text with simplified metrics in the UI tests (they check what is on screen and where, not text width); the screenshots use native graphics and real fonts, and are compared with a small per-pixel colour tolerance (Roborazzi's default).
- The release manifest check reads the backup rule files from `app/src/main/res/xml`, the ones the manifest names; device-protected storage is not used in M2, so those domains are not listed.
- The release APK is unsigned: signing keys stay with the owner and are added for the Play release (M11).
- The debug APK (about 31 MB) is unshrunk and includes debug tooling; the release APK is the size that matters.
- `spike.yml` (the M1 spike) was only re-pinned in M2, not run: it runs on `main` or manually, and takes up to 30 minutes.

## How to check it yourself

- Open pull request #1 → **Checks**, or the run pages listed above. Each `android` run's **android-reports** artifact holds the APKs, the test and Lint reports and the screenshots; the run's annotations hold the test counts, APK sizes, permissions and libraries.
- The `secrets` runs of each push show the file and history scans.
- Locally, with Android Studio's JDK and SDK 36: the commands in `README.md` ("Build and test").
