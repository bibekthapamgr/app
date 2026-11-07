# M11 — Play Console declarations

Every declaration Play Console asks for, with the answer, where in the app or its documents the answer comes from, and
what evidence shows it is true. Brief task 4 (Data safety, app content, VpnService declarations, prominent disclosure
and consent) and acceptance criterion "Policy declarations match the actual app and its data practices". The owner
enters these in Play Console (Policy → App content); the checklist (`docs/m11/release-checklist.md`) has each as a
line to tick with the date it was entered. Play's forms change; where a question is not here, the answer is in the
same spirit: what the app does, nothing more.

Sources (read 2026-11-07): the VpnService policy, [support.google.com/googleplay/android-developer/answer/12564964](https://support.google.com/googleplay/android-developer/answer/12564964);
the foreground-service declaration, [answer/13392821](https://support.google.com/googleplay/android-developer/answer/13392821);
the target API requirement, [answer/11926878](https://support.google.com/googleplay/android-developer/answer/11926878);
the testing requirement for personal accounts, [answer/14151465](https://support.google.com/googleplay/android-developer/answer/14151465);
Android 14's foreground-service types, [developer.android.com/about/versions/14/changes/fgs-types-required](https://developer.android.com/about/versions/14/changes/fgs-types-required).

## 1. Data safety

| Question | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **No** | ProxyFlow has no servers and sends nothing to its developer: no account, analytics, crash reporting of its own, ads or SDKs that phone home (`docs/m4/dependency-review.md`, the release build's library list in CI's "Build facts", `privacy_sent_nothing`). The traffic and the names the app carries pass through it to the server the user chose and are kept nowhere — the form's exemption for data processed only ephemerally, in memory, for the user's request — and that server is the user's own, chosen destination, which the form's "sharing" definition leaves out as a user-initiated transfer; the one check connection each connect opens, to `connectivitycheck.gstatic.com` through the user's proxy, carries no data (`privacy_sent_check`). The form's own wording is re-read on the day (`docs/m11/release-checklist.md` §2), since the exemptions are its, not this document's. |
| Is all of the user data collected by your app encrypted in transit? | not asked when nothing is collected | — |
| Do you provide a way for users to request that their data is deleted? | not asked when nothing is collected; the app itself: delete a profile, Clear the log, uninstall (`privacy_stored_backup`) | — |
| Has your app been independently reviewed for security (MASA)? | No | Not done in V1. |
| Privacy policy URL | `https://bibekthapamgr.github.io/app/privacy.html` | `docs/m9/privacy-policy.md`, published by `pages.yml` from the app's own strings (§3). |

What the user should still know is in the policy, not the form: the proxy provider sees the sites, credentials go to
the proxy (in the clear without TLS to the proxy), Android's own crash reports may reach the developer through Play.

## 2. App content

| Declaration | Answer | Why |
|---|---|---|
| **Privacy policy** | the URL above | — |
| **Ads** | No, the app contains no ads | No ad SDK (dependency review). |
| **App access** | **"All or some functionality is restricted"**, with one instruction entry, "Proxy for review": the protocol (SOCKS5 or HTTP), server, port, username and password of a proxy the owner sets up for Play's review alone (never the owner's own paid proxy's credentials beyond that review; revoked after approval), and the note "ProxyFlow is a client for a proxy the user already has; without one, the app shows its screens and saves profiles but cannot connect. Add the proxy above under Add proxy, then Connect." | Play's reviewers must see the VPN flow, and the form is one answer or the other: "available without special access" leaves nowhere to put the proxy, and a reviewer who cannot connect rejects as "could not test" (review C-4). |
| **Content rating** (IARC questionnaire) | Utility/Productivity/Communication category; no violence, sexuality, language, controlled substances, gambling; no user-generated content or interaction; no sharing of location; no purchases | A proxy client. Expected rating: Everyone / PEGI 3 / USK 0. |
| **Target audience and content** | 18 and over (or 13 and over if the form's lowest adult band is that): not designed for children | A networking tool; choosing a children's band triggers the Families policy, which the app is not built for. |
| **News app** | No | — |
| **COVID-19 contact tracing and status apps** | No | — |
| **Data safety** | §1 | — |
| **Government apps** | No | — |
| **Financial features** | None | — |
| **Health** | None | — |
| **Advertising ID** | Does not use the advertising ID | No `AD_ID` permission (`scripts/check_release_manifest.py` lists the permissions: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SYSTEM_EXEMPTED, POST_NOTIFICATIONS, and the VpnService's BIND_VPN_SERVICE on the service). |
| **Foreground service permissions** | see §4 | — |
| **VPN** (the VpnService declaration) | see §5 | — |

## 3. The privacy policy page

The policy Play links to must be the one the app shows. The app's is its string resources (`feature/about`,
`PrivacyPolicyScreen.kt`); `scripts/privacy_policy.py` writes `docs/m9/privacy-policy.md` from them and CI fails if
the committed document differs (`scripts/tests/test_privacy_policy.py`); `scripts/release/privacy_page.py` writes
`site/privacy.html` and `site/index.html` from the same strings and CI checks the page's text against the document
(`scripts/tests/test_privacy_page.py`). `.github/workflows/pages.yml` builds the site on every push and deploys it from
`main` once the owner has turned GitHub Pages on (Settings → Pages → Build and deployment → Source: GitHub Actions) and
set the repository variable `PAGES_ENABLED` to `true` (Settings → Secrets and variables → Actions → Variables): until
then the deploy job is skipped. The page has no script and fetches nothing. Decision D9 (where the policy is published;
the support contact): GitHub Pages at `https://bibekthapamgr.github.io/app/privacy.html`, unless the owner names
another address, in which case the listing and this document change with it. A policy page that says "version X" is
regenerated with every release (the version line comes from `app/build.gradle.kts`), so the page always describes the
version on Play after the release's deploy.

## 4. Foreground service

ProxyFlow's `ProxyFlowVpnService` runs as a foreground service of type **`systemExempted`** with the permission
`FOREGROUND_SERVICE_SYSTEM_EXEMPTED` (`core/vpn/src/main/AndroidManifest.xml`; ADR-0010). Android 14's rules list
"VPN apps (configured using Settings > Network & internet > VPN)" among the apps that may use this type — a VpnService
the user has consented to is one — and Android refuses the type to any other app with
`ForegroundServiceTypeNotAllowedException`, which the device tests would have hit on every API 34–36 level (they did
not: `docs/m10/compatibility-matrix.md`).

Play's App content page asks, for apps targeting Android 14 or later, a declaration per foreground-service type with a
description, the user impact of the task being stopped, a demo video and a use case. For `systemExempted` the form may
not list the type at all (it is Android's exemption, not a Play use case); if it does, or if Play asks for the service in
the VPN declaration's video instead:

| Field | Answer |
|---|---|
| Type | `systemExempted` — the VPN app exemption |
| Description | "ProxyFlow's VpnService carries the user's apps' TCP connections to the proxy server the user configured, while the user has it connected. The service runs only between the user's Connect and Disconnect (or Android's revocation of the VPN), shows a persistent notification with the status and a Disconnect action, and stops when disconnected." |
| User impact if stopped | "If Android stopped the service, the VPN would end and apps would lose their connections through the proxy; with Android's 'Block connections without VPN' setting on, apps would be offline until the user reconnects. The user would see the app's 'VPN stopped' state and the notification would end." |
| Video | The signed-bundle smoke's recording (`release.yml`'s artifact `signed-smoke-api-36`, `smoke.mp4`, up to 180 s): the app opened, a proxy added, Connect, the app's explainer, Android's consent, Connected, a connection through the proxy, Disconnect. The owner trims it to Play's 90 seconds (from Connect on is enough) or records the same steps on a phone, where the notification shade can be pulled down to show the service's notification; then uploads it to an unlisted video location and gives the link. |
| Use case | "VPN / network tunnel chosen by the user" (or the closest listed; else entered by hand) |

## 5. The VpnService declaration

Play's VpnService policy allows VpnService to apps whose **core functionality is the VPN** (a secure device-level
tunnel to a remote server) and to a short list of other kinds; it requires the use to be documented in the listing, a
declaration form, encryption "from the device to the VPN tunnel endpoint", and prominent disclosure and consent where
personal or sensitive data is accessed; it forbids collecting data without disclosure and manipulating other apps'
traffic for monetization.

| Question | Answer | Where it is true |
|---|---|---|
| Is VPN the core functionality of the app? | **Yes**, with this justification in the form's text field: "ProxyFlow's one function is a device-level tunnel, through Android's VpnService, from the phone to a remote proxy server the user supplies (HTTP CONNECT or SOCKS5, optionally over TLS). The tunnel is the app; there is no other feature. ProxyFlow runs no VPN server of its own, and the listing says so, so that users do not mistake it for a VPN service they subscribe to." | The listing's "How it works" (`docs/m11/store-listing.md` §3); the onboarding's "The VPN permission" page (`how_vpn_body`); About & privacy. The two statements — core functionality yes, not a VPN service — are both true and are read together by a reviewer, which is why the justification says both (review C-12). |
| Video of the app opening and the VPN in use (≤ 90 s) | The signed-bundle smoke's `smoke.mp4` (§4), trimmed to 90 s by the owner, or the owner's own recording of the same steps on a phone. | `release.yml` artifact `signed-smoke-api-36`. |
| Does the VPN service collect or share user data? | **No.** Nothing is sent to the developer; the traffic goes to the user's own proxy; the log stays on the phone and names no site. | `privacy_sent_nothing`, `privacy_stored_log`; the Data safety answer (§1). |
| Prominent disclosure video (≤ 90 s), consent and non-consent flows | The onboarding's "How ProxyFlow works" page, shown before anything can connect: what goes through the proxy, what is blocked, that the provider sees the sites, the VPN permission; then Android's own consent dialog on the first Connect, with its Cancel (the app shows "VPN permission denied", H1 board, and nothing is routed). Recorded by the smoke; the non-consent flow is the owner's recording (tap Cancel in Android's dialog) or the `H1_vpn_permission_denied` screenshot. | `feature/onboarding` (A2 page); `H1_vpn_permission_denied_light.png`; `docs/m9/ui-integration.md`. |
| Does the app redirect or manipulate traffic from other apps for monetization? | **No.** No ads, no monetization, no injection; traffic goes to the user's proxy unchanged (TLS stays end to end; `about_carry_tls`). | `docs/m8/security-review.md`; the release build's library list. |
| Documented in the listing | Yes: "ProxyFlow uses Android's VPN feature to receive your apps' traffic on this phone and hand it to your proxy. There is no VPN server …" — `scripts/check_listing_claims.py` requires the sentence. | `docs/m11/store-listing.md` §3. |

**Encryption "from the device to the VPN tunnel endpoint" — decision D2 (ADR-0005, still the owner's).** For
ProxyFlow the tunnel endpoint is the user's proxy. A profile with *TLS to proxy* on meets the requirement to the letter:
the connection to the proxy is TLS 1.2+, the certificate chain and host name verified, no plaintext fallback (ADR-0005,
`TlsEndToEndTest`). A profile without it sends the proxy protocol in the clear (HTTPS inside it stays encrypted end to
end, but the proxy handshake, the SOCKS5 or Basic credentials and any plain-HTTP traffic are readable on the path), and
the app says so in the form (`edit_plaintext_body`, D4 board) and in the policy (`privacy_sent_credentials`). Whether
Play reads its sentence as forbidding such profiles is a policy question this project cannot settle by reading. The
options are ADR-0005's: (a) submit as is, with the declaration describing the TLS option and the plaintext warning, and
accept a possible rejection to answer; (b) make the Play build refuse to connect profiles without TLS to the proxy — a
build flag ADR-0005 named (`requireTlsToProxy`) that is **not implemented** (a follow-up of a few days: the form's
switch forced on, Connect refused for saved plaintext profiles with a reason, the strings, the tests, the screenshots);
(c) ask Play's policy support first, with the declaration text above, and choose on the answer. **Recommended: (c)
before the first upload** — the question goes with the app's creation, costs nothing, and the answer decides whether
(b) is needed; the checklist does not let the submission proceed with D2 open.

## 6. Prominent disclosure and consent

Play requires prominent disclosure (in the app, in normal use, needing an affirmative action, separate from other
disclosures) where personal or sensitive data is accessed. ProxyFlow accesses the user's network traffic — the
sensitive thing a VPN app touches — and discloses it before access: the onboarding's second page says what goes through
the proxy, what is blocked and that the provider sees the sites (`how_*` strings; the page cannot be skipped: Continue
is its only way on); the first Connect runs through Android's own VPN consent dialog, which is the affirmative action
Android requires of every VPN app and which ProxyFlow cannot and does not bypass (`B9_vpn_explainer`, the explainer
shown before it; `H1_vpn_permission_denied` when refused). The disclosure is not mixed with the notification
permission's page (a page of its own, A3) or with anything else. No other personal or sensitive data is accessed:
no location, contacts, files, camera, microphone, identifiers or advertising ID (the permission list above).

## 7. Target API and other submission-time requirements

| Requirement (read 2026-11-07) | ProxyFlow | Where |
|---|---|---|
| New apps and updates target Android 16 (API 36) or higher from 31 August 2026 (extension to 1 November 2026, now past) | targetSdk 36, compileSdk 36 | `gradle/libs.versions.toml`; the release workflow's "Release version" notice reads it from the APK |
| App Bundle, signed, with Play App Signing | `release.yml`, upload key only; Google-generated app signing key | `docs/m11/signing.md` |
| Personal developer accounts created after 13 November 2023: a closed test with at least 12 testers opted in continuously for 14 days, then "Apply for production" (review usually 7 days or less) | The owner's account's creation date decides (decision D10, question 1 of `docs/m11/release-checklist.md`); the closed-testing plan is `docs/m11/release-checklist.md` §4 | — |
| 64-bit native code | The engine AAR ships arm64-v8a and x86_64 (64-bit) and armeabi-v7a (32-bit phones); no x86 | `engine/build-aar.sh`; the bundle's `bundle-validate.txt` |
| No restricted permissions without a use case | None requested (no SMS, call log, location, accessibility, all-files) | `scripts/check_release_manifest.py` |
| Deceptive behaviour, metadata, families: none of the triggers | The listing says what the app is and is not; the claims checker | `scripts/check_listing_claims.py` |
