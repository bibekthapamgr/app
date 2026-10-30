# M2 — Android foundation: acceptance report

**Status: NOT COMPLETE — the source is written, but it has not been compiled or tested yet.**

The cloud workspace this milestone is built in cannot reach Google's Android SDK and Maven servers (`dl.google.com`, `maven.google.com`) or Maven Central and the Gradle servers: every connection is refused by the workspace's network policy. Without the Android SDK and the AndroidX/Compose libraries nothing Android-specific can be compiled, so none of the acceptance criteria below can be shown to pass yet. Per the brief, M2 is not reported complete on the strength of written code.

What could be checked offline (with the Kotlin 2.0.21 compiler and JUnit 4.13.2 that ship inside the local Gradle installation):

| Check | Result |
|---|---|
| `:core:model` compiles; `ProfileValidatorTest` | 13/13 tests pass |
| `:core:data` (all but the DataStore class) compiles; the three in-memory repository and connection-controller tests from `RepositoryTest` | 3/3 pass (run with `runBlocking`, since `kotlinx-coroutines-test` is not available offline) |
| `scripts/check-secrets.sh` on the whole repository | OK; also shown to fail on a planted private key, a literal signing password and a force-added `.jks` |
| Design tokens: WCAG 2.2 AA contrast of every required pair, including the new derived Material 3 roles | 0 failures (`design/tokens/contrast-report.md`) |
| Import order, unused imports, lines over 140 characters (a stand-in until ktlint can run) | 0 problems |

## Acceptance criteria

| Criterion (brief) | How it will be shown | Result |
|---|---|---|
| Debug build compiles on the agreed JDK/Android Studio setup | `./gradlew :app:assembleDebug` with JDK 21 and Android SDK 36; APK size and merged manifest recorded | **Not run** |
| All screens navigate without crashes | `NavigationTest` (Robolectric, real `MainActivity`): every tab, Logs → Settings, Settings → About → Back, Home → Add proxy → Save → Home, Change → Profiles → Edit → Back, unsaved-changes dialog, invalid form | **Not run** |
| UI state is not stored only inside Activity | `StateRestorationTest`: half-filled form, saved profiles and the chosen theme survive Activity recreation; `AddEditProxyViewModelTest`: the form is restored after process death from `SavedStateHandle`, the password never is | **Not run** |
| No network work runs on the main thread | No network code exists in M2; `ManifestTest` proves the app requests no permissions (so no socket can be opened); StrictMode in debug crashes on main-thread network; DataStore does its I/O off the main thread | **Not run** |
| Unit tests and static checks run repeatably | `testDebugUnitTest` (85 tests in 9 modules), `spotlessCheck`, `:app:lintDebug` with warnings as errors — each run several times in a row | **Not run** |
| No secrets or signing credentials are committed | `scripts/check-secrets.sh` (local and in CI); `.gitignore` covers keystores and `keystore.properties`; release signing only from an untracked file or environment variables | Script passes (see above); CI **not run** |

## Deliverables

| Deliverable | Where | State |
|---|---|---|
| Runnable source project | `app/`, `core/`, `feature/`, Gradle files | Written, not compiled |
| Reusable UI components | `core/designsystem/` — buttons (primary, secondary, text, destructive), card, text and password fields, switch, radio and choice-chip rows, list row, confirm and choice dialogs, loading, empty and error states, banner, status pill, top bar; theme generated from the M1 tokens | Written, not compiled |
| Build instructions | `README.md` | Written; versions to be confirmed by the first build |
| Initial CI configuration | `.github/workflows/android.yml` (secrets scan, token check, wrapper validation, ktlint, Lint, tests, debug and release builds; actions pinned to commit SHAs) | Written, not run |
| Architecture decision | `docs/adr/0008-app-architecture.md` | Proposed |

## Scope notes (deliberate, stated in the app)

- **No connecting.** Home shows the selected profile and a disabled Connect button with the text "Connecting arrives with the VPN service in a later milestone." The connection seam (`ConnectionController`) is in place for M5.
- **Profiles in memory only.** Profiles last until the app process ends; the Profiles screen says so. M3 adds Room and Keystore-encrypted passwords behind the same `ProfileRepository` interface. Passwords are never written to saved instance state, and the Add/Edit screen sets `FLAG_SECURE`.
- **Settings shows only what works:** the theme (System default, Light, Dark, stored with DataStore) and About. Kill switch, reconnect and log settings arrive with the features they control.
- **Logs** shows the empty state (F1); nothing writes to the log until the engine exists. The "deleted after 24 hours" promise from the design is left out of the copy until M7 implements it.
- **About** states what this build does and what the finished app will carry (TCP and DNS through the proxy; UDP and ping blocked; LAN devices not reachable; IPv6 only if the proxy supports it). Links to the privacy policy, licences and help are added when those pages exist (M9/M11).

## Dependency versions

`gradle/libs.versions.toml` pins Android Gradle Plugin 8.13.0, Kotlin 2.2.20, Compose BOM 2025.10.00, Navigation 2.9.5, Lifecycle 2.9.4, DataStore 1.1.7, Robolectric 4.16, Roborazzi 1.50.0, Spotless 8.0.0 and ktlint 1.7.1, among others. These were chosen offline and **must be checked against the current releases** as the first step once the network is open; the catalog will be updated and this section will list the final versions and their licences.

## What happens next

1. Network access to `dl.google.com`, `maven.google.com`, Maven Central and the Gradle servers is opened for the workspace.
2. Install Android SDK 36, confirm current stable versions, generate the Gradle wrapper.
3. Build, fix compile errors, run `spotlessApply`/`spotlessCheck`, Lint, all tests (three times in a row), debug and release builds; record Roborazzi screenshots of every screen in light and dark.
4. Fill in the results above, have the work checked by an independent reviewer, and only then report M2 complete.
