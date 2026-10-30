# ADR-0008 — App architecture: modules, MVVM, manual DI, navigation

**Status:** Proposed (M2) · **Date:** 2026-10-30

## Context

M2 asks for "a clean, buildable Android application foundation" with MVVM or a similarly clear UI/domain/data split, navigation for six screens, a reusable component set, and Room/DataStore "only where needed". ADR-0007 fixed the platform baseline (Kotlin, Compose, Material 3, minSdk 29, target/compile 36) and sketched the module list.

## Decision

| Item | Decision | Reason |
|---|---|---|
| Modules | `:app` (shell, DI, Activity) · `:core:model` (pure Kotlin/JVM: profile model, validation) · `:core:designsystem` (theme from the tokens, icons, components) · `:core:data` (repository interfaces and M2 implementations) · `:core:testing` (fakes, dispatcher rule) · `:feature:home`, `:feature:profiles`, `:feature:settings`, `:feature:logs`, `:feature:about` | Features cannot depend on each other, only on `core`; `:core:model` has no Android dependency, so validation is tested on the plain JVM. ADR-0007's `:core:ui` is named `:core:designsystem`; `:vpn`, `:engine-api`, `:engine` are added in M4–M5 |
| UI pattern | MVVM: each screen has a stateless `XxxScreen(state, callbacks)` and an `XxxRoute` that connects it to a `ViewModel` exposing one `StateFlow<XxxUiState>` | Screens are previewable and testable without ViewModels; ViewModels are testable without Android |
| Form state | `AddEditProxyViewModel` keeps every field except the username and password in `SavedStateHandle`. After process death the user enters them again; an edited profile's username comes back from the stored profile | Survives rotation and process death; credentials (ADR-0006) are never written to saved instance state, which Android may persist. `toString` of the profile and the form redacts both |
| Saved password scope | Leaving the password field empty keeps the saved password only while protocol, server, port, username and TLS settings are unchanged (`ProxyProfileInput.sameCredentialTargetAs`). Otherwise the form asks for it again and the repository erases the stored one; a new sign-in profile needs a password | A password belongs to one proxy account reached one way; it must never be sent to a proxy it was not saved for |
| Dependency injection | Manual: one `AppContainer` owned by `ProxyFlowApplication` builds the repositories and a single `ViewModelProvider.Factory` | Five ViewModels and four repositories do not justify Hilt's annotation processing and build time. Revisit in M5 if the VPN service needs many injected collaborators |
| Navigation | Navigation Compose with type-safe `@Serializable` destinations; bottom bar for Home · Profiles · Logs · Settings; Add/Edit and About full screen; tab switches save and restore each tab's state; the form opens with `launchSingleTop`, so a double tap does not stack two forms | Matches docs/m1/04 §2; compile-time-checked arguments |
| Leaving the proxy form | The app closes the form by popping `AddEditProxyDestination` **by route**, so a second close (Save finishing after a Back) does nothing. System Back is intercepted only while the form has unsaved changes (`BackHandler(enabled = dirty)` shows *Discard changes?*); otherwise NavHost's predictive back closes it | An earlier `dropUnlessResumed` close could drop the close of a Save that finished while the screen was not resumed, leaving a saved form on screen. Intercepting Back only when needed keeps the predictive back preview on an untouched form |
| Storage in M2 | Preferences DataStore for the theme only. Profiles are kept in memory (`InMemoryProfileRepository`) | "Room and DataStore only where needed": Room arrives in M3 together with Keystore encryption (ADR-0006), behind the same `ProfileRepository` interface |
| Connection seam | `ConnectionController` interface; M2's `UnavailableConnectionController` always reports *Not connected* and `canConnect = false`, and Home says connecting is not available yet | The UI never pretends to connect (brief: a Connected screen proves nothing) |
| Threading | No network code and no `INTERNET` permission in M2; DataStore does its own I/O off the main thread; debug builds enable StrictMode (log disk and network on the main thread, crash on main-thread network) | Acceptance criterion "No network work runs on the main thread" |
| Build | Gradle Kotlin DSL, version catalog, Java 17 bytecode compiled by any JDK ≥ 17 (no Gradle toolchain download), Spotless + ktlint, Android Lint with warnings as errors, R8 in release | Repeatable static checks; works offline once dependencies are cached |
| Tests | JVM unit tests (model, repositories, ViewModels with a test Main dispatcher) · Robolectric Compose UI tests (components, every screen, navigation through the real `MainActivity`) · Roborazzi screenshots of every screen in light and dark | Runs in CI without an emulator. Instrumented tests on emulators and devices start in M5, where they are needed to prove routing |

## Alternatives considered

- **Hilt:** standard and scales well, but adds KSP/kapt, generated code and build time for a graph this small. Switching later is mechanical because all construction is in `AppContainer`.
- **Single module:** simpler build, but no enforced boundary between UI, data and (later) the VPN service and engine, which the brief requires.
- **Convention plugins (`build-logic`):** remove the repeated Android blocks in module build files; deferred until the module count grows in M4–M5 to keep M2's build easy to read.
- **Room now:** would store profiles in M2 but either in plaintext (forbidden for passwords) or with the M3 crypto design half-built; in-memory storage keeps M2 honest and M3 focused.

## Consequences

- Profiles do not survive the app process in M2. The Profiles screen says so in a banner, and the M2 acceptance report lists it; M3 replaces the repository and removes the banner.
- Every ViewModel must be registered in `AppContainer.viewModelFactory`; a screen whose ViewModel is missing fails the navigation test.
- Feature modules expose public stateless screens so the app module can render them for screenshots.
- UI tests that send a system Back first let the screen settle (`pressBack()` in the app tests). A Back sent in the same frame as the tap or keystroke before it reaches the back handlers of the previous frame, which no person can do and which made two navigation tests fail until the cause was found (docs/m2/M2-acceptance-report.md).

## Evidence

- All M2 checks pass in GitHub Actions on pull request #1 of the project repository: ktlint, Android Lint with warnings as errors, every unit and Robolectric UI test, debug and release builds, the release manifest check, and every screen compared with its committed screenshot (docs/m2/M2-acceptance-report.md lists the runs).
- Resolved versions: activity 1.13.0 (back events dispatched through androidx.navigationevent 1.0.0), navigation 2.9.8, lifecycle 2.9.4, Compose 1.9.3 with Material 3 1.4.0 (BOM 2025.10.00), DataStore 1.1.7. Navigation 2.10 needs AGP 9.2 and compileSdk 37; that toolchain upgrade is proposed for the start of M3.
