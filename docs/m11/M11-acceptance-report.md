# M11 — Google Play Console and release: acceptance report

**Status: every M11 acceptance criterion this project can meet without the owner's Play Console account passes in CI, in all three attempts of one `release` run of the same commit, after three independent reviews of the work (A, B, C) and three rounds of re-review of their fixes (F, G, H), with every finding fixed or, where the behaviour is a deliberate trade-off, documented. Three criteria are the owner's by nature and wait on decision D10 — the Play account's testing requirement, the keys' backups, and the store assets and privacy policy being *live* — and the submission itself waits on the checklist's sign-off (`docs/m11/release-checklist.md`). The owner's steps are at the end.**

| | |
|---|---|
| Repository | `bibekthapamgr/app`, branch `m11-play-release`, pull request #10 (base: `m10-qa-performance`, stacked on #9, M10) |
| Verified commit | `75ce907` — the M11 workflows, scripts, assets, tests and the app's version and ABIs; the commits after it change documents only |
| CI runs of that commit | `release` run 42366997278: attempts 1, 2 and 3 green (engine; bundle; smoke API 29; smoke API 36) · `android` run 42366997269 green · `pages` run 42366997249 green · `secrets` runs 42366993005 and 42366997256 green |
| Build environment | as in M10: ubuntu-24.04 runners · Go 1.27.3 · gomobile · Android NDK r28c · Temurin JDK 21 · Gradle 9.8.2 · Android Gradle Plugin 9.5.0 with built-in Kotlin 2.4.21 · compileSdk/targetSdk 36 · minSdk 29 · bundletool 1.18.3, SHA-256 `a099cfa1…8e29` (pinned) |
| Test environment | the bundle job on the runner (keytool, jarsigner, openssl, apksigner, aapt2, bundletool); `pixel_5` emulators API 29 and API 36, `google_apis` x86_64, booted fresh (`-no-snapshot`), with the lab of M5–M10 (`scripts/e2e_env.sh`: Dante with sign-in, the destinations reachable only through a proxy, the witness, the logging uplink resolver) |
| Release | version 1.0.0 (11), `app.proxyflow.android`: the signed App Bundle (in CI, signed with a key of the run; the bundle to upload is the owner's hand-started run's, signed with the upload key), its APK set and universal APK, the store assets (`store/`), the listing (`docs/m11/store-listing.md`), the privacy policy page (`site/`, from `pages.yml`), the declarations, the checklist, the release notes |

The workspace this was written in cannot reach Google's Maven repository or run an emulator, so everything Android ran in GitHub Actions. Locally ran: the evidence and release scripts' 187 tests (`python3 -m unittest discover -s scripts/tests`), the listing checker, the store-asset checker and generator, the privacy page generator, the secrets scan (self-test, worktree, history), shellcheck and actionlint, `make_upload_key.sh` in throwaway directories (refused inside a checkout, a key made outside), and the bundle's signature checks on a jar signed with a throwaway key. Every figure in the M11 documents comes from the CI runs' annotations and logs, read back with `gh`.

## Acceptance criteria

| Criterion | Evidence | Result |
|---|---|---|
| Signed AAB installs and runs correctly | The `release` run's bundle job: the App Bundle built and signed, `jarsigner` verifies it, its one signature block's certificate is the key's, the release APK has one signer with the key's certificate; M8's release checks pass on the same build. Its smoke jobs, on fresh API 29 and API 36 emulators: the bundle's APK set installed with bundletool as Play installs (3 split APKs), the installed package `app.proxyflow.android` with the key's certificate, not debuggable (dumpsys and the installed manifest), version 1.0.0 (11); then, through the app's own screens with no test hook — onboarding, a SOCKS5 proxy with sign-in added, Connect, the app's VPN explainer, Android's consent, **Connected** 1.4 to 6.7 s after the consent; one line from the shell to `proxyflow.test`, reachable only through a proxy, came back through the tunnel; the witness (reached only around the proxy) was refused; Disconnect showed Not connected; since the lab came up the destination saw 1 connection and Dante opened 1 tunnel to it, the witness heard nothing, the uplink resolver was never asked for the destination's name — in each of the three attempts, with every tap in the log, six screenshots and the screen recording (62–73 s on API 29, 109–112 s on API 36) as evidence (`signed-smoke-api-NN`, fourteen files) | **Pass** |
| Policy declarations match the actual app and its data practices | `docs/m11/declarations.md`: each answer with the string, manifest line or document it comes from — Data safety (no collection: nothing to the developer, the traffic ephemeral, the user's own server), app content, the foreground service of type `systemExempted` under Android's VPN-app exemption, the VpnService declaration (VPN as core functionality, with the justification; the smoke's recording and screenshots of the explainer and Android's consent as the videos' source), prominent disclosure before access, the target API (36). Decision D2 (the encryption sentence of Play's VpnService policy and plaintext profiles) is the owner's, with the recommendation to ask Play policy support first; the checklist does not let the submission proceed with it open | **Pass** (as written; D2 open for the owner) |
| Required testing/access requirements for the account are satisfied | Play's rule verified (2026-11-07): personal accounts created after 13 November 2023 need a closed test with 12 or more testers opted in for 14 consecutive days, then "Apply for production". The project cannot know the owner's account; `docs/m11/release-checklist.md` §1 asks (D10) and §4 is the closed-testing plan, with the crash-free bar (≥ 99.8 %, Android vitals) and the representative devices of M10 | **Owner's** (the plan and the rule are in the checklist) |
| Release keys are stored securely and backed up appropriately | `scripts/release/make_upload_key.sh` makes the upload key on the owner's computer, outside any checkout (refused inside one), RSA 4096, PKCS12, mode 600; the app signing key stays with Google (Play App Signing); the repository holds no key — `.gitignore`, and the secrets scan by name and by content (keystores under any name, as base64 wrapped or not, PEM keys, passwords as values in any form the reviews could think of), on every commit of the history; the release workflow is handed the three secrets only on a hand-started run on `main`, with the fingerprint checked, passwords never printed or on a command line, the key removed at the end. The two offline backups are the owner's (`docs/m11/signing.md` §5; the checklist §6) | **Pass** for what the project controls; the backups the owner's |
| Store assets and privacy policy are live and reviewed | The assets (`store/`: the 512 icon from the adaptive icon's safe zone, the 1024×500 feature graphic, eight 9:16 screenshots from the app's own) checked in CI against Play's specifications and against their sources and the listing; the listing checked for every claim the app may not make and the limitations it must state (and read by reviews C, F, G and H); the privacy policy page generated from the app's own strings and built on every push (`pages` run green), deployed from `main` once the owner turns Pages on and sets `PAGES_ENABLED`, `PROXYFLOW_SUPPORT_EMAIL` and `PROXYFLOW_DEVELOPER_NAME`. *Live* is the owner's act (the checklist §3, §5) | **Pass** as reviewed; live is the owner's |
| Submission is made only after release checklist sign-off | `docs/m11/release-checklist.md`: the owner's answers (D10), Play's requirements re-read on the day, the build's proof on the release commit (`mode=upload-key`, `event=workflow_dispatch`, `ref=refs/heads/main`, the fingerprint), internal then closed testing, the listing and declarations entered, the keys backed up, a sign-off with no open item | **Pass** (the checklist exists; the sign-off is the owner's) |

## The brief's tasks

| Task | Where |
|---|---|
| 1. Confirm Play Console account status, app creation eligibility and testing requirements | `docs/m11/declarations.md` §7 (the rules, read 2026-11-07, with sources); `docs/m11/release-checklist.md` §1 (the owner's account) |
| 2. Finalize original app name, package ID, icon, screenshots, descriptions and support contact | `docs/m11/store-listing.md` (name searched, §6; package; the texts); `store/` (the assets); the contact: D10 |
| 3. Create public privacy policy that accurately reflects actual behavior | `docs/m9/privacy-policy.md` and `site/privacy.html`, both from the app's strings (`scripts/privacy_policy.py`, `scripts/release/privacy_page.py`, tests); `pages.yml` |
| 4. Complete Data safety, app content, VpnService declarations and prominent disclosure/consent | `docs/m11/declarations.md` §1–§6 |
| 5. Verify current target API requirements at submission time | `docs/m11/declarations.md` §7; the release workflow fails unless the APK targets 36; the checklist re-reads the rule on the day |
| 6. Configure release signing securely; never commit signing keys or passwords | `docs/m11/signing.md`; `scripts/release/make_upload_key.sh`; `release.yml`; `scripts/check_secrets.py` |
| 7. Build and test signed AAB on clean devices | `release.yml` bundle and smoke jobs; `scripts/m11_signed_smoke.sh`, `scripts/release/ui.py` |
| 8. Run internal/closed testing and address issues before production submission | `docs/m11/release-checklist.md` §4 (the owner's, with the project's phone checklist of M10) |
| 9. Verify store listing does not promise unsupported UDP, complete anonymity or guaranteed coverage | `scripts/check_listing_claims.py` in CI; `docs/m11/store-listing.md` §3 says UDP blocked, not anonymous, what is outside |

## Deliverables

| Deliverable | Where |
|---|---|
| Signed AAB | `release` run artifact `release-bundle-run-key` (CI's proof); the one to upload: the owner's hand-started run's `release-bundle-upload-key` (`docs/m11/signing.md` §5) |
| Store listing assets | `store/` (icon, feature graphic, screenshots, `assets.json`), made by `scripts/release/store_assets.py` |
| Privacy policy | `docs/m9/privacy-policy.md`; `site/privacy.html` from `pages.yml` |
| Declaration checklist | `docs/m11/declarations.md`; `docs/m11/release-checklist.md` |
| Release notes | `docs/m11/release-notes.md`; the Play text in `docs/m11/store-listing.md` §7 |
| Decisions | ADR-0016 (`docs/adr/0016-google-play-release.md`); D10 in `docs/m1/01-scope.md` |
| Defects | `docs/m11/defect-register.md` |

## Reviews

Each review is an independent reading of the code, the runs' logs and annotations, with its own probes (throwaway keys and repositories, sentences against the checker, the key step of the workflow run locally in every mode); each finding is in `docs/m11/defect-register.md` with its fix and the test or run that guards it.

| Review | Of | Found |
|---|---|---|
| A | Release signing and the release workflow | Fixed (one high: the key script's checkout check passed a new directory; the upload key on every push; the optional fingerprint; the unpinned bundletool; passwords on command lines) or documented (the engine job twice per push) |
| B | The signed-bundle smoke and the UI driver | Fixed (one critical: the app's VPN explainer was not tapped, so the smoke could never have reached Connected; the tunnel count without a baseline; stale dumps; the form below the fold) or documented (the fixture tree) |
| C | The listing, the assets, the privacy page, the declarations, the checklist | Fixed (one high: the listing checker's negation rule; App access; the contact; the icon's size; the Data safety reason) or documented (the sample profile name) |
| F | A's and C's fixes | Fixed (the tests that had not been written; the denial rule's clauses; the secrets scan's wrapped base64; the upload key on `main` alone; the pictures' hashes) |
| G | B's and F's fixes, and the first green run | Fixed (the checker's subordinate clauses and marketing forms; the secrets scan's next ring; the smoke's log lines and the dialogs' screenshots; the recording checked) or documented (the lab password typed) |
| H | G's fixes | Fixed (a regression of the denial rule; more forms, and the checker's limit stated; the secrets scan's next ring; the installed manifest read by aapt2; ffprobe) |

73 findings were filed in M11 (M11-1 to M11-73): 1 critical, 6 high, 22 medium, 43 low, 1 informational; 67 fixed (M11-72 confirmed by its run), 3 kept as documented trade-offs (M11-13, the engine job twice per push; M11-25, the UI tests' fixture; M11-60, the lab password typed into the form), and 3 closed by this report (M11-31, M11-53, M11-66).

## Open

- **Decision D2** (plaintext proxies against Play's VpnService encryption sentence): the owner's, recommended (c) — ask Play policy support before the first upload; the checklist blocks submission while open; option (b)'s build flag is not implemented (a follow-up of a few days if chosen).
- **Decision D10**: the Play account's type and creation date, the name (ProxyFlow: no Android app of the name found; a Chrome extension "ProxFlow" one letter away; the trademark registers the owner's to search), the package name final, the support e-mail and the developer name (the listing and the privacy page), the privacy policy's address (GitHub Pages by default), the twelve testers.
- **The owner's steps** (`docs/m11/signing.md` §5, `docs/m11/release-checklist.md`): make the upload key and its two backups; the three repository secrets and the variables; turn Pages on; create the app in Play Console; the hand-started `release` run on `main` with the fingerprint; internal testing on the owner's phone with `docs/m10/physical-phone-checklist.md`; closed testing; the listing and declarations; sign-off; production.
- **M12**: launch and maintenance — the first translations and the licence (D8), the memory trade-off M10-63, the owner's phone findings, what closed testing finds.

## How to check

- CI evidence: `release` run 42366997278's attempts — the bundle job's annotations (the signing key, the version, the signatures, bundletool, the release manifest, the release bundle with its native libraries) and the smoke jobs' (the install, the package, Connected, the line, the witness, Disconnect, the lab's logs, the recording), their artifacts `release-bundle-run-key` and `signed-smoke-api-NN` (six screenshots, `smoke.mp4`, the installed APK, its certificates and its manifest); `android` run 42366997269 (the build job: 651 JVM tests, Lint with the ABI filter, the listing and asset checks, OSV, the release manifest; the eight device jobs green; the screenshots job); `pages` run 42366997249 (the site built); `secrets` runs.
- Locally: `python3 -m unittest discover -s scripts/tests`; `python3 scripts/check_listing_claims.py`; `python3 scripts/release/store_assets.py check` (and `make` to regenerate); `python3 scripts/release/privacy_page.py --out site`; `scripts/check-secrets.sh --self-test`, `--worktree`, `--history`; `scripts/release/make_upload_key.sh` with `PROXYFLOW_KEY_DIR` set to a throwaway directory.
- The owner's own: the hand-started `release` run's `release-facts.txt` (`mode=upload-key`, `event=workflow_dispatch`, `ref=refs/heads/main`, the fingerprint equal to the certificate's in Play Console → Setup → App signing).
