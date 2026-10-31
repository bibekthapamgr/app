# ProxyFlow for Android

ProxyFlow is a free, original Android app that routes your apps' TCP traffic through your own proxy — HTTP CONNECT or SOCKS5, optionally over TLS — using Android's `VpnService`. It needs no account and has no backend.

What it promises, and where that ends (the single source for these claims is `docs/m1/02-traffic-support-matrix.md`):

- **While connected**, if the proxy stops working, traffic is blocked instead of being sent around the proxy. DNS lookups are resolved by the proxy.
- **Not carried:** UDP (QUIC, calls, games — refused so apps fall back to TCP), ping and other ICMP, and devices on the local network (printers, casting). IPv6 destinations work only if the proxy has IPv6.
- **When ProxyFlow is not connected** — before it starts, after the user disconnects, or when Android removes the VPN — apps use the normal network, unless Android's Always-on VPN with "Block connections without VPN" is turned on. The app will guide users to that setting (M8).

**Status**

- **M1 — requirements, feasibility, UI/UX:** delivered (`docs/m1/M1-acceptance-report.md`). The owner chose minSdk 29 (D3).
- **M2 — Android foundation:** builds, lints and passes every test in CI; awaiting the owner's review (`docs/m2/M2-acceptance-report.md`). This build sets up the app only: it cannot connect yet (the VPN service is M5) and keeps profiles only in memory (storage with Keystore encryption is M3). Both limits are stated in the app itself.

## Repository

| Path | Contents |
|---|---|
| `app/` | Application shell: `MainActivity`, navigation graph, bottom bar, manual DI (`AppContainer`), app-level tests and reference screenshots (`app/screenshots/`) |
| `core/model/` | Pure Kotlin: proxy profile model and form validation (no Android dependency) |
| `core/designsystem/` | Theme generated from the design tokens, icons, and the reusable components (buttons, cards, fields, dialogs, loading, empty and error states, banners, status pill) |
| `core/data/` | Repository interfaces and their M2 implementations (DataStore settings, in-memory profiles and logs, a connection controller that cannot connect yet) |
| `core/testing/` | Test fakes and the main-dispatcher rule |
| `feature/{home,profiles,settings,logs,about}/` | One module per area: stateless screens, ViewModels, navigation entry points, tests |
| `config/lint/` | Android Lint configuration |
| `gradle/libs.versions.toml` | Every dependency and plugin version |
| `scripts/` | `check-secrets.sh` (keys, signing files and credentials in the files or anywhere in the history; rules in `check_secrets.py`), `check_release_manifest.py` (permissions, backups and exported components of the release build), `ci_error_digest.py` and `ci_build_facts.py` (CI summaries) |
| `docs/m1/`, `docs/m2/` | Milestone deliverables and acceptance reports |
| `docs/adr/` | Architecture decision records 0001–0008 |
| `design/` | Screen boards, design tokens, contrast checker, Compose colour generator |
| `spike/` | M1 feasibility spike (Go engine prototype, lab harness, evidence runs) |
| `.github/workflows/` | `android.yml` (app checks and builds), `secrets.yml` (secrets scan on every push and pull request), `spike.yml` (M1 spike) |

## Environment

| Tool | Version |
|---|---|
| JDK | 17 or newer (CI and the M2 build use 21). Android Studio's bundled JDK works |
| Android SDK | Platform 36 (`platforms;android-36`), current build tools, platform tools |
| Android Studio | A release that supports the Android Gradle Plugin version in `gradle/libs.versions.toml` |
| Gradle | Use the wrapper (`./gradlew`); it downloads the pinned Gradle version |
| Python 3 | For the design-token tools (`design/tools/`) and the checks in `scripts/` |

Point the build at your SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=/path/to/Android/sdk` (Android Studio writes it; it is git-ignored).

## Build and test

```bash
./gradlew :app:assembleDebug            # debug APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest :core:model:test  # all JVM, ViewModel and Robolectric UI tests
./gradlew spotlessCheck                 # Kotlin formatting (ktlint); spotlessApply fixes it
./gradlew :app:lintDebug                # Android Lint for the app and every module it uses
./gradlew :app:assembleRelease          # R8-shrunk release APK (unsigned without signing config)
./gradlew :app:verifyRoborazziDebug     # compare every screen with its copy in app/screenshots/
./gradlew :app:recordRoborazziDebug     # re-record app/screenshots/ after a UI change you meant
python3 scripts/check_release_manifest.py  # after assembleRelease: permissions, backups, exports
scripts/check-secrets.sh                # files you would commit; --history scans every commit
```

`scripts/check-secrets.sh` also works as a pre-commit hook: `ln -s ../../scripts/check-secrets.sh .git/hooks/pre-commit`.

The same checks run in CI (`.github/workflows/android.yml`). Tests run on the JVM with Robolectric, so no emulator is needed; on-device instrumentation tests start in M5, where routing must be proven on real devices.

What CI adds for reviewers:

- Every Gradle step runs even when an earlier one failed, so one run reports every problem; none runs unless the Gradle wrapper passed validation. On failure, `scripts/ci_error_digest.py` collects compiler errors, ktlint findings, failed tests (with a description of the screen for UI tests) and Lint issues into annotations, readable on the run page and through the GitHub API even where logs can't be downloaded.
- `scripts/ci_build_facts.py` reports test counts per module, APK sizes, the permissions of the merged manifests and the shipped libraries as notices.
- The release build's merged manifest must pass `scripts/check_release_manifest.py`: only allow-listed permissions (none but androidx.core's own receiver permission in M2), backups and device transfers off, not debuggable, no cleartext opt-in, nothing exported without a permission except the launcher activity.
- Every screen is compared with its committed image (`verifyAndRecordRoborazziDebug`); a changed screen fails the run and its new image is recorded. On pull requests a separate job publishes the images on the branch `ci-screenshots/<branch>`, also after a failed run; copy them into `app/screenshots/` when the change is meant. That job is the only one with a token that can push, and it never pushes to the pull request itself.
- `secrets.yml` runs the secrets scan on every push and pull request, whatever changed, over the current files and every commit; findings name the file and line, never the value.
- Each run's **android-reports** artifact holds the APKs, test and Lint reports, screenshots and, for changed screens, Roborazzi's comparison images.

### Application ID

The application ID is provisional: `app.proxyflow.android` (debug builds add `.debug`). Change it before the first Play upload in `gradle.properties` (`proxyflow.applicationId`) or per build with `-Pproxyflow.applicationId=com.example.proxyflow`. The Kotlin package (`app.proxyflow`) does not need to change with it.

### Release signing

Signing keys and passwords are never committed. To sign release builds locally, copy `keystore.properties.example` to `keystore.properties` (git-ignored) and fill it in, or set `PROXYFLOW_KEYSTORE_FILE`, `PROXYFLOW_KEYSTORE_PASSWORD`, `PROXYFLOW_KEY_ALIAS` and `PROXYFLOW_KEY_PASSWORD` (CI secrets). Without them, `assembleRelease` produces an unsigned APK. Keep the keystore outside the repository.

### Design tokens

`design/tokens/proxyflow.tokens.json` is the source of truth for colours. After changing it:

```bash
python3 design/tools/check_contrast.py          # WCAG 2.2 AA check of every required pair
python3 design/tools/generate_compose_colors.py # rewrites core/designsystem/.../theme/Color.kt
```

`TokenColorsTest` fails if `Color.kt` and the tokens disagree.

## Run the M1 spike

```bash
cd spike
sudo -E HEV_TAG=2.19.3 FUZZTIME=30s GO_TEST_REPEAT=20 ./run_spike.sh   # Linux + root; package list in spike/README.md
```

## Licence

To be decided by the product owner (D8). Third-party components and their licences: `docs/m1/08-dependency-review.md` (spike) and `docs/m2/` (app dependencies).
