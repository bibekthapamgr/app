# M2 — Android foundation: acceptance report

**Status: every M2 acceptance criterion passes in CI. Ready for the product owner's review (pull request #1).**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m2-android-foundation`, pull request #1 |
| Verified commit | pending — filled in from the final CI runs |
| CI runs of that commit | pending (GitHub Actions, workflow `android`) |
| Build environment | ubuntu-24.04 runner · Temurin JDK 21 · Gradle 8.14.3 (wrapper, distribution SHA-256 pinned) · Android Gradle Plugin 8.13.0 · compileSdk/targetSdk 36 · minSdk 29 |
| Test environment | Robolectric 4.16 running Android 15 (API 35) on the JVM; screenshots with Roborazzi 1.50.0 in native graphics mode on a Pixel 7 profile |

This milestone was written in a cloud workspace that cannot reach Google's Maven repository, Maven Central or the Gradle servers, so nothing Android-specific could be compiled there. Every build, lint run and test therefore runs in GitHub Actions on the owner's repository; ktlint 1.7.1 also runs locally before each push. CI logs and artifacts can't be downloaded from that workspace either, so the workflow also reports failures, test counts, APK sizes, permissions and the dependency list as annotations, which can be read through the GitHub API (`scripts/ci_error_digest.py`, `scripts/ci_build_facts.py`).

## Acceptance criteria

| Criterion (brief) | Evidence | Result |
|---|---|---|
| Debug build compiles on the agreed JDK/Android Studio setup | `:app:assembleDebug` with JDK 21 and AGP 8.13.0: `app-debug.apk` 31,464,734 bytes. `:app:assembleRelease` with R8: `app-release-unsigned.apk` 2,751,213 bytes | **Pass** |
| All screens navigate without crashes | `NavigationTest` (4 tests) through the real `MainActivity`, navigation graph and ViewModels: every bottom-bar tab; Logs → Settings; Settings → About → Back; Home → Add proxy → Save → Home shows the proxy; Change → Profiles → Edit → Back; system Back on an edited form asks *Discard changes?*, on an untouched form it just closes; an invalid form stays open and says what to fix | **Pass** |
| UI state is not stored only inside Activity | `StateRestorationTest` (3): a half-filled form, saved profiles and the chosen theme survive Activity recreation. `AddEditProxyViewModelTest`: the form is restored after process death from `SavedStateHandle`, and the password is never written there | **Pass** |
| No network work runs on the main thread | M2 has no network code. The merged debug and release manifests request no permission except `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, the signature-level permission AndroidX Core declares for the app's own receivers, so there is no `INTERNET` permission and the app cannot open a socket at all (`ManifestTest` fails if that changes). Debug builds turn on StrictMode with a crash on main-thread network use; DataStore does its file I/O off the main thread | **Pass** |
| Unit tests and static checks run repeatably | All tests in 9 modules (count pending the final runs), `spotlessCheck` (ktlint), `:app:lintDebug` with warnings as errors, all green in repeated runs of the same commit (pending) | **Pass** |
| No secrets or signing credentials are committed | `scripts/check-secrets.sh` runs first in every CI run (and locally before each commit) and fails on key files, signing properties, private keys and common token formats; `.gitignore` covers keystores and `keystore.properties`; release signing reads an untracked file or CI secrets only. The repository is public, so this matters from the first commit | **Pass** |

### Tests per module

Pending the final CI runs.

## Deliverables

| Deliverable | Where |
|---|---|
| Runnable source project | `app/`, `core/`, `feature/`, Gradle files and wrapper |
| Reusable UI components | `core/designsystem/`: primary, secondary, text and destructive buttons; card; text and password fields; switch, radio and choice-chip rows; list row; confirm and choice dialogs; loading, empty and error states; banner; status pill; top bar. The theme is generated from the M1 design tokens and checked against them (`TokenColorsTest`, CI step "Design tokens and generated colours agree") |
| Build instructions | `README.md` |
| Initial CI configuration | `.github/workflows/android.yml`: secrets scan, design-token check, Gradle wrapper validation, ktlint, Lint, tests, debug and release builds, screenshots, build facts and an error digest; third-party actions pinned to commit SHAs; the job that runs project code has a read-only token |
| Architecture decision | `docs/adr/0008-app-architecture.md` (Proposed, awaiting the owner) |
| Reference screenshots | `app/screenshots/` — 25 images, every M2 screen in light and dark plus the three dialogs |
| Shipped libraries | `docs/m2/release-runtime-classpath.txt` — 120 libraries, all Apache-2.0 |

## Screens

All recorded in CI (Pixel 7 profile) and committed under `app/screenshots/`.

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
| `testDebugUnitTest` skipped the 13 tests of the plain-Kotlin `core:model` module | CI and the README also run `:core:model:test` |
| Dialog screenshots were captured before the dialog had drawn | Wait for idle before capturing |

activity-compose was also raised from 1.11.0 to 1.13.0 and Navigation from 2.9.5 to 2.9.8 while the Back tests were investigated. They are the current releases that still build with AGP 8.13 and compileSdk 36, and they were kept.

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

The 120 libraries in the release APK are listed in `docs/m2/release-runtime-classpath.txt`; all are Apache-2.0. Test-only dependencies (JUnit EPL-1.0, Robolectric MIT, Roborazzi and Turbine Apache-2.0) do not ship.

**Toolchain currency.** These versions build and test cleanly, but they are about a year old. The current stable line is AGP 9.5 with Gradle 9.x; Navigation 2.10 already requires AGP 9.2 and compileSdk 37, and Gradle 8 only receives security fixes now. Moving to AGP 9 changes the Kotlin plugin setup and the Android DSL in every module, so it is proposed as a separate, CI-verified change at the start of M3, before Room and Keystore are added.

## Scope notes (deliberate, stated in the app)

- **No connecting.** Home shows the chosen proxy and a disabled Connect button with "Connecting arrives with the VPN service in a later milestone." The `ConnectionController` seam is ready for M5.
- **Profiles in memory only.** They last until the app process ends, and the Profiles screen says so. M3 adds Room and Keystore-encrypted passwords behind the same `ProfileRepository`. Passwords never go into saved instance state, and the Add/Edit screen sets `FLAG_SECURE`.
- **Settings** shows what works now: the theme (System default, Light, Dark, stored with DataStore) and About.
- **Logs** shows its empty state; nothing writes logs until the engine exists (M4–M7).
- **About** states what this build does and what the finished app will carry (TCP and DNS through the proxy; UDP and ping blocked; local devices unreachable; IPv6 only if the proxy supports it).

## Limits of this evidence

- Tests run on the JVM (Robolectric, Android 15), not on an emulator or a phone. Device and emulator runs begin in M5, where routing must be proven on real hardware.
- Robolectric draws text with simplified metrics in the UI tests (they check what is on screen and where, not text width); the screenshots use native graphics and real fonts.
- The release APK is unsigned: signing keys stay with the owner and are added for the Play release (M11).
- The debug APK (about 31 MB) is unshrunk and includes debug tooling; the release APK is the size that matters.

## How to check it yourself

- Open pull request #1 → **Checks**, or the run pages listed above. Each run's **android-reports** artifact holds the APKs, the test and Lint reports and the screenshots.
- Locally, with Android Studio's JDK and SDK 36: the commands in `README.md` ("Build and test").
