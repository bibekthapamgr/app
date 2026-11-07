# M11 — Defect register

**Milestone:** M11 · Every defect M11's work, its reviews and its CI runs found, with its severity, where it was found, what was done and the evidence. Severity as in M10's register: **critical** (a key or password exposed, a bundle that could be uploaded unsigned or wrongly signed, a leak around the proxy), **high** (a release-blocking failure, a security weakness, a declaration untrue of the app), **medium** (a check that fails without a defect or passes when it shouldn't; a listing sentence that misleads), **low** (cosmetic, rare, or a configuration V1 doesn't ship). A release candidate has none open above low.

Found by: M11's own CI runs; three independent reviews — **A**, release signing and the release workflow; **B**, the signed-bundle smoke and the UI driver; **C**, the listing, the assets, the privacy page, the declarations and the checklist — and the re-reviews of their fixes.

## CI runs

| # | Severity | Found | Defect | Fix | Evidence | Status |
|---|---|---|---|---|---|---|
| M11-1 | medium | `release` run 42358825814 (b6c3ecb), bundle job | The bundle's signature check ended with no message: `grep -c` with no match exits 1 under `set -e`, so the step died before its own error line. The count was 0: the App Bundle's `META-INF` had no `.RSA` block as the check looked for it (to be read from the next run's listing) | The `META-INF` listing printed before the check; the count taken with `|| true`; the version step's minSdk read by `grep -o` (it printed empty) | Run 42359705613 | Fixed (676cac9); the cause of the empty count pending that run |
| M11-2 | medium | `release` run 42359705613 (676cac9), bundle job | The version step died the same way: `aapt2 dump badging` printed no `sdkVersion` line for this APK, the `grep -o` pipeline for minSdk exited 1 under `pipefail`, and the step ended before its notice. Also seen there: the APK ships native code for `x86` too (`native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'`) | minSdk read from the manifest's own attribute (`aapt2 dump xmltree`) with `|| true`; the target SDK checked to be 36 explicitly (Play's requirement), the step failing with that reason and no other | The next run | Fixed |
