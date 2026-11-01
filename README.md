# ProxyFlow for Android

ProxyFlow is a free, original Android app that routes your apps' TCP traffic through your own proxy — HTTP CONNECT or SOCKS5, optionally over TLS — using Android's `VpnService`. It needs no account and has no backend.

What it promises, and where that ends (the single source for these claims is `docs/m6/routing-and-limitations.md`, which supersedes the device rows of `docs/m1/02-traffic-support-matrix.md`):

- **While connected**, if the proxy stops working, traffic is blocked instead of being sent around the proxy. Site names are resolved by the proxy, not looked up by the phone; an app's own encrypted DNS (or Android's Private DNS set to a provider) goes through the proxy too, which then sees addresses instead of names.
- **Not carried:** UDP (QUIC, calls, games — refused so apps fall back to TCP), ping and other ICMP, and devices on the local network (printers, casting). IPv6 destinations work only if the proxy has IPv6.
- **Outside the VPN:** ProxyFlow's own connection to the proxy (by design) and, by Android's rules, some of Android's own traffic and that of privileged preinstalled apps (on Android 15, Google Play services opened connections around the VPN), apps in a work profile, Private space or another user, and tethered devices.
- **When ProxyFlow is not connected** — before it starts, after the user disconnects, or when Android removes the VPN — apps use the normal network, unless Android's Always-on VPN with "Block connections without VPN" is turned on. The app will guide users to that setting (M8).

**Status**

- **M1 — requirements, feasibility, UI/UX:** delivered (`docs/m1/M1-acceptance-report.md`). The owner chose minSdk 29 (D3).
- **M2 — Android foundation:** delivered and merged (`docs/m2/M2-acceptance-report.md`).
- **M3 — proxy profiles and secure storage:** delivered and merged (`docs/m3/M3-acceptance-report.md`). Profiles are stored in Room; usernames and passwords are encrypted under an Android Keystore key made for each set of credentials (ADR-0006, ADR-0009).
- **M4 — HTTP CONNECT, SOCKS5 and the engine:** delivered and merged (`docs/m4/M4-acceptance-report.md`). The Go packet engine (`engine/`) carries TCP through SOCKS5 and HTTP CONNECT proxies, optionally over TLS checked by Android's trust manager.
- **M5 — Android VpnService integration:** in review (`docs/m5/M5-acceptance-report.md`, `docs/m5/vpn-service-integration.md`, ADR-0010). Connect asks for Android's VPN consent behind an explainer, checks the proxy before anything changes on the phone (a proxy that refuses the check never shows Connected), then runs the engine on the VPN interface and says Connected once Android routes apps into it; apps' TCP goes through the proxy, and once connected a failure holds traffic instead of sending it around the proxy. Proven on emulators against real proxies with packet captures. "Test connection" in the profile form still says testing isn't available (decision D6).

## Repository

| Path | Contents |
|---|---|
| `app/` | Application shell: `MainActivity`, navigation graph, bottom bar, manual DI (`AppContainer`), app-level tests and reference screenshots (`app/screenshots/`); `src/androidTest/` holds the VPN's end-to-end tests (real service, engine and proxies on emulators) |
| `core/model/` | Pure Kotlin: proxy profile model and form validation (no Android dependency) |
| `core/designsystem/` | Theme generated from the design tokens, icons, and the reusable components (buttons, cards, fields, dialogs, loading, empty and error states, banners, status pill) |
| `core/data/` | Repositories: profiles in Room (`db/`) with credentials sealed by per-profile Android Keystore keys (`crypto/`), settings and the active profile in DataStore, in-memory logs, the `ConnectionController` interface the screens use, and a connection tester that cannot test yet. `src/androidTest/` runs on emulators (Android Keystore) |
| `core/data/schemas/` | Exported Room schema for every database version (checked in CI, used by the migration tests) |
| `core/engine/` | The packet engine for Android: unpacks `engine/build/pfengine.aar` and wraps it (`ProxyEngine`, `EngineSecret`, `AndroidEnginePlatform` with the platform trust manager). `src/androidTest/` runs the real engine on emulators against mock proxies |
| `core/vpn/` | The VPN layer (ADR-0010): `ProxyFlowVpnService`, `VpnSession` (the connection state machine), `VpnRuntime` and the app's `ConnectionController`, the connection notifications, the lifecycle log (tag `ProxyFlowVpn`). JVM tests drive the session with fakes |
| `engine/` | The packet engine in Go (gVisor netstack, SOCKS5 / HTTP CONNECT / TLS adapters, fake-IP DNS, the gomobile API) and `build-aar.sh`; see `engine/README.md` |
| `core/testing/` | Test fakes (in-memory profile store, software keys standing in for the Keystore on the JVM, a fake connection tester) and the main-dispatcher rule |
| `feature/{home,profiles,settings,logs,about}/` | One module per area: stateless screens, ViewModels, navigation entry points, tests |
| `config/lint/` | Android Lint configuration |
| `gradle/libs.versions.toml` | Every dependency and plugin version |
| `scripts/` | `check-secrets.sh` (keys, signing files and credentials in the files or anywhere in the history; rules in `check_secrets.py`), `check_release_manifest.py` (permissions, backups, the VPN service and exported components of the release build), `ci_error_digest.py` and `ci_build_facts.py` (CI summaries), and the end-to-end lab (M5, M6): `e2e_env.sh` (proxies, TLS fronts, DNS servers, destinations and sinks on the CI runner), `e2e_pki.py` (the run's test CA and certificates), `e2e_servers.py`, `e2e_lab_check.py` (the lab checked from the runner), `e2e_device.sh` (inside the emulator job), `m5_evidence.py` and `m6_evidence.py` (logs and packet captures checked) |
| `docs/m1/` … `docs/m6/` | Milestone deliverables and acceptance reports |
| `docs/adr/` | Architecture decision records 0001–0011 |
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
| Go | 1.27.3, for `engine/` (tests need nothing else; `engine/build-aar.sh` also needs the Android SDK and installs the pinned NDK) |

Point the build at your SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=/path/to/Android/sdk` (Android Studio writes it; it is git-ignored).

## Build and test

```bash
(cd engine && go test -race ./...)      # the Go engine: adapters, mock proxies, netstack, lifecycle, leaks
engine/build-aar.sh                     # engine/build/pfengine.aar, needed by :core:engine (Go, JDK, Android SDK)
./gradlew :app:assembleDebug            # debug APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest :core:model:test  # all JVM, ViewModel and Robolectric UI tests
./gradlew :core:data:connectedDebugAndroidTest  # Keystore and storage tests on a connected phone or emulator
./gradlew :core:engine:connectedDebugAndroidTest  # the engine on a phone or emulator (needs the AAR)
./gradlew :app:connectedDebugAndroidTest   # the VPN end to end; needs the proxies (docs/m5/vpn-service-integration.md §5)
./gradlew spotlessCheck                 # Kotlin formatting (ktlint); spotlessApply fixes it
./gradlew :app:lintDebug                # Android Lint for the app and every module it uses
./gradlew :app:assembleRelease          # R8-shrunk release APK (unsigned without signing config)
./gradlew :app:verifyRoborazziDebug     # compare every screen with its copy in app/screenshots/
./gradlew :app:recordRoborazziDebug     # re-record app/screenshots/ after a UI change you meant
python3 scripts/check_release_manifest.py  # after assembleRelease: permissions, backups, exports
scripts/check-secrets.sh                # files you would commit; --history scans every commit
```

`scripts/check-secrets.sh` also works as a pre-commit hook: `ln -s ../../scripts/check-secrets.sh .git/hooks/pre-commit`.

The same checks run in CI (`.github/workflows/android.yml`). Most tests run on the JVM with Robolectric. What needs Android itself runs on emulators in CI (API 29, 34, 35 and 36): the Android Keystore (`core/data`), the engine (`core/engine`), and since M5 the VPN end to end (`app/src/androidTest`), against real proxies on the runner, with the emulator's uplink captured and checked afterwards (`scripts/m5_evidence.py`; since M6 also DNS, TLS to the proxy, IPv6, UDP and ping, `scripts/m6_evidence.py`).

After changing a Room entity, build `core/data` (any of the commands above) and commit the regenerated file in `core/data/schemas/` together with a migration and its test (ADR-0009).

What CI adds for reviewers:

- Every Gradle step runs even when an earlier one failed, so one run reports every problem; none runs unless the Gradle wrapper passed validation. On failure, `scripts/ci_error_digest.py` collects compiler errors, ktlint findings, failed tests (with a description of the screen for UI tests) and Lint issues into annotations, readable on the run page and through the GitHub API even where logs can't be downloaded.
- `scripts/ci_build_facts.py` reports test counts per module, APK sizes, the permissions of the merged manifests and the shipped libraries as notices.
- The release build's merged manifest must pass `scripts/check_release_manifest.py`: only allow-listed permissions (none but androidx.core's own receiver permission in M2), backups and device transfers off, not debuggable, no cleartext opt-in, nothing exported without a permission except the launcher activity.
- Every screen is compared with its committed image (`verifyAndRecordRoborazziDebug`); a changed screen fails the run and its new image is recorded. On pull requests a separate job publishes the images on the branch `ci-screenshots/<branch>`, also after a failed run; copy them into `app/screenshots/` when the change is meant. That job is the only one with a token that can push, and it never pushes to the pull request itself.
- The Room schema generated from the entities must equal the committed one in `core/data/schemas/`; the generated file is published with the screenshots (`ci-screenshots/<branch>`).
- The `engine` job checks the Go modules against the checksum database (it rebuilds `go.sum` from nothing and compares), runs gofmt, vet, the engine tests with the race detector and a 30-second fuzz run of every parser, and builds the AAR — only from verified modules, after govulncheck passed over the engine and gomobile's runtime package — reporting its size, hashes, page alignment, linked modules and the govulncheck verdict. The `build` and `device` jobs use that AAR.
- The `device` job runs `core/data`'s and `core/engine`'s instrumented tests on Android emulators (API 29, the minimum, and API 35) — the real Android Keystore, and the real engine against mock proxies — and reports every test and its result as a notice.
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
