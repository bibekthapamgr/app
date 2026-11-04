# M9 — UI regression test results

**Milestone:** M9 · **Commit:** filled in from the verified CI run · **Decisions:** ADR-0014 · **Integration:** `docs/m9/ui-integration.md`

The UI regression suite is everything that runs on each push and says whether a screen still shows what it should, every control still does what it says, and nothing a screen promises is untrue. This document lists what it covers and its results for the verified commit.

## 1. What runs

| Suite | Where | What it checks |
|---|---|---|
| Screen tests | Robolectric, every module with screens | Each state of each screen: what it says, which controls it offers, what each control calls; dialogs, sheets and menus; empty, loading and failure states |
| ViewModel tests | JVM | What each screen's state comes from (the repositories, the session, the tester), and what each action does to them |
| Navigation and state | Robolectric, the real app (`NavigationTest`, `StateRestorationTest`, `OnboardingFlowTest`) | Every screen opens and closes; Back; the form asks before discarding; first launch; rotation and process death |
| Screenshots | Robolectric with Roborazzi (`ScreenshotTest`) | Every screen drawn in both themes on a Pixel 7, dialogs, sheets and menus in the light theme, and twelve screens on a 320 dp phone at 200 % font, compared pixel by pixel with `app/screenshots` |
| Accessibility | in every screenshot test (`Accessibility.kt`) | Every control TalkBack reaches has a name it reads out and is at least 48 × 48 dp |
| Copy | `CopyClaimsTest` | No string claims coverage of all traffic, anonymity, protection or guarantees; the limits and the privacy facts are stated where they must be |
| Manifest | `ManifestTest`, `scripts/check_release_manifest.py` | Permissions, the VPN service and the tile bound only by Android |
| Notices and policy | `scripts/licences.py`, `scripts/tests/test_privacy_policy.py` | The licences screen lists what the release build ships; the public privacy policy is the app's text |
| Device tests | emulators API 29, 34, 35, 36 | The real app, VPN and engine through the lab's proxies, including the tile through Android's system UI (`QuickTileEndToEndTest`) |

## 2. Results

Filled in from the verified commit's CI run.

## 3. The screenshots

`app/screenshots/` — one file per screen and state, `_light` and `_dark`; `large_font/` the 200 % set. The board each one draws is its name's prefix (`design/screens/`).
