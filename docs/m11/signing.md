# M11 — Release signing

How ProxyFlow's release is signed, where the key lives, what never goes into the repository, and what the release
workflow proves about the bundle before anyone uploads it. Brief task 6 ("Configure release signing securely; never
commit signing keys or passwords") and acceptance criterion "Release keys are stored securely and backed up
appropriately". Decisions in ADR-0016.

## 1. Two keys, and which one this repository holds

Google Play's **App Signing** (required for App Bundles) keeps the *app signing key* — the one phones see — on Google's
side; Google makes it when the app is created in Play Console with "Use Google-generated key" (the default, and the
choice here: the key that signs what users install is never on a laptop or a CI runner). The owner holds only the
**upload key**, which signs the bundles uploaded to Play; Play checks the upload signature, strips it, and re-signs with
the app signing key. A lost or leaked upload key is replaced through Play Console (an upload-key reset: Play support
needs the new certificate), without touching the key phones trust.

The upload key is the only key this project makes, and it is made on the owner's own computer, never in CI:

```
scripts/release/make_upload_key.sh            # default directory ~/ProxyFlow-release (PROXYFLOW_KEY_DIR to change)
```

The script refuses a directory inside a git checkout (judged on the nearest directory that exists, since the key's
usually does not yet) and refuses to overwrite an existing keystore; it needs `keytool`
(any JDK, or Android Studio's `jbr/bin`) and `openssl`; it asks for the password twice, silently, 12 characters or
more; and it makes `proxyflow-upload.p12` (PKCS12, alias `proxyflow-upload`, RSA 4096, SHA256withRSA, valid 50 years;
`chmod 600`, directory `700`), exports `upload-certificate.pem` (public: Play Console's "upload key certificate"), and
prints the certificate's SHA-256 fingerprint and the next steps. The password reaches `keytool` through the
environment, never on a command line another user of the computer could read from the process table. The
certificate's subject is `CN=ProxyFlow upload key, O=ProxyFlow, C=JP` — the app's name, no personal detail; Play shows
none of it.

## 2. Where the key and the passwords live, and where they never go

| What | Where | Never |
|---|---|---|
| `proxyflow-upload.p12` | The owner's computer outside any checkout; two offline backups (an encrypted USB stick, and one kept elsewhere) | The repository, a chat, an e-mail, a cloud folder shared with anyone |
| Its password | The owner's password manager, with the fingerprint beside it | The repository, a script, a shell history (`make_upload_key.sh` reads it with `read -s`) |
| The three GitHub secrets (§3) | The repository's Actions secrets: encrypted at rest, masked in logs, unreadable through the API; read only by a run the owner starts by hand | Any push's or pull request's run (the workflow hands them to no other event), and so any branch's build scripts; a fork's workflow (GitHub gives forks no secrets) |
| The fingerprint and `upload-certificate.pem` | Public: in Play Console, in this document's checklist, in the release notes | — (a certificate is public by nature) |

What keeps the key out of the repository: `.gitignore` refuses `*.jks`, `*.keystore`, `*.p12` and `keystore.properties`;
the secrets scan (`scripts/check-secrets.sh`, workflow `secrets.yml`) refuses, in every commit of the history and on
every push whatever changed, keystores by name (`.jks`, `.keystore`, `.p12`, `.pfx`, `.key`, with any suffix) and by
content (a JKS or PKCS12 file's first bytes, under any name), PEM private keys, a keystore as base64 (`MII…`, as the
secret is made), and passwords written as values (`storePassword=`, `*_PASSWORD=`, quoted or not, in any text file);
`make_upload_key.sh` refuses to make the key inside a checkout. The Gradle build (`app/build.gradle.kts`) reads the
signing configuration from an untracked `keystore.properties` or from the environment variables
`PROXYFLOW_KEYSTORE_FILE`, `PROXYFLOW_KEYSTORE_PASSWORD`, `PROXYFLOW_KEY_ALIAS`, `PROXYFLOW_KEY_PASSWORD`, and without
all four builds the release unsigned — it never carries a default password or a checked-in key.

## 3. The release workflow (`.github/workflows/release.yml`)

Runs on every push and pull request like `android.yml` (so every commit proves the whole path, with a key of the run),
and by hand (Actions → release → Run workflow, on `main`, with the fingerprint) for the bundle to upload. Three jobs:

1. **engine** — the Go packet engine's checks and AAR, shared with `android.yml` through `engine.yml`.
2. **bundle** — the key, the signed bundle, its verification, the APK sets:
   - *The key.* Only a run the owner starts by hand (`workflow_dispatch`) **on `main`** is handed the three secrets
     (`PROXYFLOW_KEYSTORE_BASE64` — `base64 -w0 proxyflow-upload.p12` —, `PROXYFLOW_KEYSTORE_PASSWORD`,
     `PROXYFLOW_KEY_PASSWORD`; the alias is the repository *variable* `PROXYFLOW_KEY_ALIAS`, or `proxyflow-upload` — a
     variable, since a secret's value is masked in every log line, which would hide the alias from the workflow's own
     errors); the keystore is decoded into `$RUNNER_TEMP` (outside the checkout, gone with the runner): mode
     **upload-key**. Such a run must be given the input `fingerprint`, and it must equal the key's certificate's SHA-256
     (read with `keytool`; the input is taken with or without spaces, a `SHA256:` label or lower case) — a bundle signed
     with any key but the one Play knows fails here, not at upload; a run with the secrets but no fingerprint fails too.
     A push's or pull request's run, and a hand-started run of any other branch, get no secret, whatever the branch or
     who pushed it — a branch's build scripts run with the signing environment, and must never see the upload key
     before review (reviews A-3, F-7) — and sign with a key
     made for the run alone (RSA 3072, 30 days, `CN=ProxyFlow run key, O=not for distribution`), its random password
     masked: mode **run-key**, which proves the path on every push and is never uploaded. In both modes the keystore must
     open with the password and hold the alias. The four values go to `signing.env` (mode 600) in `$RUNNER_TEMP`, loaded
     into the environment of the signing and bundletool steps alone; the passwords also to two mode-600 files for
     bundletool's `--ks-pass=file:`; all removed with the keystore at the end of the job whatever happened. No password
     is printed or put on a command line: the secrets are masked by GitHub, the run key's by `::add-mask::`, and
     `keytool` reads them with `-storepass:env`.
   - *The build.* `:app:bundleRelease :app:assembleRelease` with the signing environment: the R8-minified release, the
     same build `android.yml` checks.
   - *The version.* Read back from the APK with `aapt2 dump badging`: the application id must be
     `app.proxyflow.android`; the version name and code are handed to the smoke job to check on the device.
   - *The signatures.* The bundle: `jarsigner -verify` reports it verified, its `META-INF` holds exactly one signature
     block, and the certificate in that block (read with `openssl pkcs7`/`x509`) has the key's fingerprint. The APK:
     `apksigner verify --print-certs` reports exactly one signer, with the key's fingerprint. An APK named `*unsigned*`
     fails at once (Gradle did not see the configuration).
   - *M8's release checks* on the same build: `scripts/check_release_manifest.py` (permissions, backups, debuggable,
     cleartext, trust anchors, exported components, the `@xml` files, the engine's names in the dex).
   - *bundletool*, Google's, pinned to a release (`BUNDLETOOL_VERSION`) and to that jar's SHA-256 (`BUNDLETOOL_SHA256`;
     a download that differs fails), downloaded from `google/bundletool`'s releases with the run's token: `validate` on
     the bundle; `build-apks` — the full APK set (`release.apks`, every split, signed with the key: what Play hands a
     phone) and the universal APK (`universal.apk`, for a phone outside Play); `get-size total` for the download size
     per device.
   - *The artifact* `release-bundle-<mode>` (30 days): the `.aab`, `release.apks`, `universal.apk`, the mapping file,
     `signing-certificate.pem`, `bundle-validate.txt`, `download-size.txt` and `release-facts.txt` (mode, fingerprint,
     version, the event and ref the run was started from, commit, run and attempt, bundletool's version and hash, the
     native libraries' ABIs, the bundle's SHA-256 and size). The one to upload says `mode=upload-key` and
     `event=workflow_dispatch`, `ref=refs/heads/main`.
3. **smoke** — on API 29 and API 36 emulators, booted fresh (`-no-snapshot`), with the end-to-end lab of M5–M10 up:
   `scripts/m11_signed_smoke.sh` installs this device's split APKs from `release.apks` with bundletool `install-apks`
   (what Play does), then checks the package installed is `app.proxyflow.android` (`pm list packages`), that the
   installed app's certificate (`apksigner` on the pulled `base.apk`) is the key's, that it is not debuggable, and that
   its version name and code are the bundle job's; then uses the app through its own screens with
   `scripts/release/ui.py` (uiautomator: find a text, tap it, type into the labelled field; a dump that fails is retried
   and never read as the previous screen; a field below the fold is scrolled to) — the release build has no test hooks —
   onboarding, Add proxy (the lab's SOCKS5 proxy with sign-in), Save, Connect, the app's own VPN explainer, Android's
   VPN consent, Connected; one line from the shell client to `proxyflow.test`, reachable only through a proxy, must come
   back through the tunnel; the witness (reached only around the proxy) must be refused; Disconnect must show Not
   connected; then the lab's logs since the lab came up: the destination saw exactly one connection and Dante opened
   exactly one tunnel to it (the line: connections equal tunnels), the witness's log has no new line, the phone's uplink
   resolver was never asked for the destination's name. A step that fails stops the flow with an error, a screenshot
   and the screen's texts; the recording (`screenrecord`, up to 180 s — its size and, where `ffprobe` is on the runner,
   its length in a notice; the owner trims the Play videos to 90 s) is pulled and the device cleaned whatever happened;
   six screenshots (Home empty, the form, the app's VPN explainer, Android's consent dialog, Connected, Not connected);
   every tap, typed field and wait is a line of the log; artifact `signed-smoke-api-NN`. The emulator has a phone's
   screen (`pixel_5`).

The engine job runs for `android.yml` and for `release.yml` on the same push: the two AARs are built the same way,
not shared (review A-10; a trade-off for keeping the release workflow one file the owner can start by hand).

## 4. bundletool's hash

`BUNDLETOOL_SHA256` is `bundletool-all-1.18.3.jar`'s SHA-256 as downloaded from
`https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar` on 2026-11-07
(32,520,401 bytes): `a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29` — computed by review A from its own
download, and the same the workflow's `bundletool` notice printed in run 42361983214 (pinned). A new bundletool version
changes both lines together.

## 5. The owner's steps (once)

1. `scripts/release/make_upload_key.sh`; back the keystore up twice, offline; put the password and the fingerprint in
   the password manager.
2. The three repository secrets (the script prints them), and the variable `PROXYFLOW_KEY_ALIAS` only if the alias is
   not `proxyflow-upload`.
3. Actions → release → Run workflow on `main` at the release commit, with the fingerprint as the input; download
   `release-bundle-upload-key`, check `release-facts.txt` says `mode=upload-key`, `event=workflow_dispatch`,
   `ref=refs/heads/main` and the fingerprint, and upload the `.aab` to Play Console (closed testing first:
   `docs/m11/release-checklist.md`).
4. In Play Console → Setup → App signing: the app signing key is Google-generated; the upload key certificate shown
   there must have the same SHA-256 as `release-facts.txt`.

## 6. If the upload key is lost or leaked

Make a new one with `make_upload_key.sh` in a fresh directory, then in Play Console → Setup → App signing → "Request
upload key reset", attaching `upload-certificate.pem`; replace the three secrets; rotate nothing else — the app signing
key, and so what phones trust, is unchanged. A leaked key cannot sign an update users receive: Play re-signs every
upload with the app signing key, and refuses uploads from a key that is not the current upload key.
