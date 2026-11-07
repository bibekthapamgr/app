# M11 — Google Play Console and release: acceptance report

**Status: ‹filled at acceptance›**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m11-play-release`, pull request #10 (base: `m10-qa-performance`, stacked on #9, M10) |
| Verified commit | ‹sha› — the M11 code, workflows, scripts, assets and tests; the commits after it change documents only |
| CI runs of that commit | `release` run ‹id›: attempts 1, 2 and 3 green (engine, bundle, smoke API 29, smoke API 36) · `android` run ‹id› green · `pages` run ‹id› green · `secrets` runs ‹ids› green |
| Build environment | as in M10: ubuntu-24.04 runners · Go 1.27.3 · gomobile · Android NDK r28c · Temurin JDK 21 · Gradle 9.8.2 · Android Gradle Plugin 9.5.0 with built-in Kotlin 2.4.21 · compileSdk/targetSdk 36 · minSdk 29 · bundletool 1.18.3 (SHA-256 pinned) |
| Test environment | the bundle job on the runner; `pixel_5` emulators API 29 and API 36, `google_apis` x86_64, booted fresh (`-no-snapshot`), with the lab of M5–M10 (`scripts/e2e_env.sh`) |
| Release | version 1.0.0 (11): the signed App Bundle (run-key mode in CI; the upload-key bundle is the owner's hand-started run's), its APK set and universal APK, the store assets, the listing, the privacy policy page, the declarations, the checklist, the release notes |

The workspace this was written in cannot reach Google's Maven repository or run an emulator, so everything Android ran in GitHub Actions. Locally ran: the evidence and release scripts' tests (`python3 -m unittest discover -s scripts/tests`), the listing checker, the store-asset checker, the privacy page generator, the secrets scan (self-test, worktree, history), shellcheck and actionlint, `make_upload_key.sh` in a throwaway directory, and the bundle signature checks on a jar signed with a throwaway key. Every figure in the M11 documents comes from the CI runs' annotations and logs, read back with `gh`.
