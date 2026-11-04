# M9 — UI regression test results

**Milestone:** M9 · **Commit:** `cef3b29d0eed5b6a9ea64e3f4460f9879832c048` (the verified commit: the M9 code, tests and screenshots; the commits after it change documents only) · **CI:** run 41796923594, attempts 1–3 · **Decisions:** ADR-0014 · **Integration:** `docs/m9/ui-integration.md`

The UI regression suite is everything that runs on each push and says whether a screen still shows what it should, every control still does what it says, and nothing a screen promises is untrue. This document lists what it covers and its results for the verified commit.

## 1. What runs

| Suite | Where | What it checks |
|---|---|---|
| Screen tests | Robolectric, every module with screens | Each state of each screen: what it says, which controls it offers, what each control calls; dialogs, sheets and menus; empty, loading and failure states |
| ViewModel tests | JVM | What each screen's state comes from (the repositories, the session, the tester), and what each action does to them |
| Navigation and state | Robolectric, the real app (`NavigationTest`, `StateRestorationTest`, `OnboardingFlowTest`) | Every screen opens and closes; Back; the form asks before discarding; first launch; rotation and process death |
| Screenshots | Robolectric with Roborazzi (`ScreenshotTest`) | Every screen drawn in both themes on a Pixel 7, dialogs, sheets and menus in one theme, and twelve screens on a 320 dp phone at 200 % font, compared pixel by pixel with `app/screenshots` |
| Accessibility | in every screenshot test (`Accessibility.kt`) | Every control TalkBack reaches has a name it reads out and is at least 48 × 48 dp |
| Copy | `CopyClaimsTest` | No string claims coverage of all traffic, anonymity, protection or guarantees; the limits and the privacy facts are stated where they must be |
| Manifest | `ManifestTest`, `scripts/check_release_manifest.py` | Permissions, the VPN service and the tile bound only by Android |
| Notices and policy | `scripts/licences.py`, `scripts/tests/test_privacy_policy.py` | The licences screen lists what the release build ships; the public privacy policy is the app's text |
| Device tests | emulators API 29, 34, 35, 36 | The real app, VPN and engine through the lab's proxies, including the tile through Android's system UI (`QuickTileEndToEndTest`) |

## 2. Results

From the verified commit's CI run, the same in each of its three attempts:

| Suite | Result |
|---|---|
| Screen, ViewModel, navigation and state tests (JVM and Robolectric) | 599 tests in 13 modules, none failed (per module: the acceptance report, Tests) |
| Screenshots | 115 images, each the same as its reference, pixel for pixel: 44 screens in both themes (88), 15 dialogs, sheets and menus in one, and 12 screens at 200 % |
| Accessibility | in all 71 screenshot tests, no control TalkBack reaches without a name it reads out or under 48 × 48 dp |
| Large fonts | each of the 12 screens at 200 % shows its main action |
| Copy | `CopyClaimsTest` passed |
| Manifest | `ManifestTest` and the release check (`scripts/check_release_manifest.py`, on the release APK) passed |
| Notices and policy | the notices' 135 entries — the release build's 127 libraries, the engine's 7 Go modules and Go itself — match what it ships; the public privacy policy is the app's text |
| Android Lint, ktlint, contrast | no finding (Lint's warnings are errors); the design tokens' colour pairs pass `design/tools/check_contrast.py` |
| Device tests | 66 on each of API 29, 34, 35 and 36, none failed: 61 run and `KillSwitchEndToEndTest`'s 5 steps, which the Gradle pass skips and the failure injection runs; the tile's 2 among them; the M5–M8 evidence passed on each level |

Before the verified commit, the runs failed on references the changes made out of date or new ones (recorded by the run, looked at, then committed) and on problems the new checks found — among them the touch-target check measuring the wrong bounds, the sheets' drag handle, an unnamed button, the notices not yet committed — and on the emulators on the tile's first device test, one emulator that never booted and one run's incomplete captures (the acceptance report, Before the verified commit, lists them all).

## 3. The screenshots

`app/screenshots/` — one file per screen and state; the board each one draws is its name's prefix (`design/screens/`). A1, B3b, E1c and G2–G4 have no board file of their own, and the plain `B6_` images are blocked states of the B6 family.

- **Both themes** (`_light`, `_dark`), 44 screens: onboarding A1–A3; Home B1, B2 (disconnected, after an interruption, with Android's kill switch on), B3 and B3b, B4 (and with problems), B5 (reconnecting, switching), B6 (engine stopped, reconnecting stopped), B6b (connect failed, Always-on VPN without a profile), B6d, B7; Profiles C1, C2 (the list, a test running); the form D1 (add, edit), D3, D4, D5–D7; Settings E1, E1b, E1c, the guide E2; Logs F1, F2 (all, errors); About G1 (and its links), G2, G3 (help, certificates), G4 (the licences, a licence's text); H3.
- **One theme**, 15 dialogs, sheets and menus: B6's Stop, B8 (light; and while connected, dark), B9, C2's menu, C2's switch question (connected, still checking), C3, D8, D9 (connected, still checking), E1's theme dialog, F2's Clear, F3, H1.
- **`large_font/`**, 12 screens at 200 % on a 320 dp phone: A2, A3, B2, B4, B6b, B8, C2, D1, D7, E1, F2, G2.

## 4. Small screens, large fonts and dark mode

What the 200 % set and both themes found in M9, and what changed for it:

- **200 % font, 320 dp.** The font is set as Android sets it (Robolectric's `@Config(fontScale)`), so dialogs and sheets scale too; each of the twelve screens is scrolled to its main action — or shows it where it stays (a floating or bottom button, the top bar) — which must then be on screen. Found and fixed: the profile card's ACTIVE label broke one letter per line under a long name (it now wraps under the name); the picker's Add proxy and Manage profiles could not wrap; the test line's icon sat between wrapped lines (it stays beside the first, the line measured as Compose draws it, its font scaling not linear at large scales). Left as it is, cosmetic: the picker's radio button sits beside the middle of its profile's lines rather than its name.
- **Dark mode.** Every screen is drawn in both themes; the design tokens' colour pairs are checked for contrast in CI (`design/tools/check_contrast.py`); status is never colour alone.
- **Not in M9:** landscape, tablets and foldables, right-to-left languages, and font scales between 100 % and 200 % — for M10's compatibility pass.
