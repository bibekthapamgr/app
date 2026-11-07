# M11 — Release checklist and sign-off

The one document the submission waits on: nothing is uploaded to Google Play's production track until every line
below is ticked, with the date and by whom, and the owner has signed §7. Brief acceptance criterion "Submission is
made only after release checklist sign-off". Lines marked **CI** are proved by the workflows on the release commit;
lines marked **owner** are the owner's own acts in Play Console or on their computer, which this project cannot do or
see; lines marked **both** need an act and a proof.

The Play facts here were read on 2026-11-07 from Google's own pages (links in `docs/m11/declarations.md`); Play's
rules change, so §2 is re-read on the day of submission and the date updated.

## 1. The owner's answers (decision D10) — before anything is created in Play Console

| # | Question | Answer | Why it matters |
|---|---|---|---|
| 1 | Is the Play developer account a **personal** account, and was it created after **13 November 2023**? | ‹yes/no; creation date› | If yes, production is reachable only after a closed test with **12 or more testers opted in continuously for 14 days**, then "Apply for production" (review usually ≤ 7 days). An organization account, or a personal one from before that date, skips this. |
| 2 | The app's name stays **ProxyFlow**? | ‹yes / another name› | The owner's own trademark search (`docs/m11/store-listing.md` §6: this project found no Android app of the name, and a Chrome extension "ProxFlow" one letter away). The name is in the listing, the feature graphic, the app and its mark. |
| 3 | The package name **`app.proxyflow.android`** is final? | ‹yes› | Play never lets it change after creation. It is `proxyflow.applicationId` in `gradle.properties`; changing it means rebuilding and re-running M10's suites. |
| 4 | The **support e-mail** the listing shows, and the **developer name** as Play Console shows it | ‹address the owner controls›; ‹name› | Public on the listing; the privacy policy page names both. Goes into `docs/m11/store-listing.md` §1 (the ‹…› placeholder) and into the repository variables `PROXYFLOW_SUPPORT_EMAIL` and `PROXYFLOW_DEVELOPER_NAME` (Settings → Secrets and variables → Actions → Variables) before the listing is entered. |
| 5 | Where the **privacy policy** is published (D9) | ‹GitHub Pages, `https://bibekthapamgr.github.io/app/privacy.html`, or another address› | The URL goes into Play's Data safety and store listing; it must be live and show the policy before review. |
| 6 | Decision **D2** (plaintext proxies in the Play build) | ‹(a) submit as is / (b) require TLS to proxy / (c) ask Play policy support first — recommended› | `docs/m11/declarations.md` §5. Not open at submission. |
| 7 | Decision **D8** (licence) | ‹open-source licence, or closed› | The listing can link to the repository only if it is public and licensed; the open-source notices screen already credits the libraries either way. |
| 8 | The **twelve testers** | ‹names or a list the owner keeps; their Google accounts' e-mails or a Google Group› | For closed testing (§4); each must stay opted in 14 consecutive days. |

## 2. Play's requirements at submission (re-read on the day)

| Requirement | Read 2026-11-07 | ProxyFlow | Proof |
|---|---|---|---|
| Target API | Android 16 (API 36) or higher for new apps and updates since 31 August 2026 (extension to 1 November 2026, past) | targetSdk 36 | **CI** `release.yml` "Release version" notice |
| Format | Android App Bundle, signed; Play App Signing | `release.yml` upload-key mode | **CI** "Signatures" notice; `release-facts.txt` |
| App icon | 512×512, 32-bit PNG with alpha, ≤ 1024 KB | `store/icon-512.png` | **CI** `store_assets.py check` |
| Feature graphic | 1024×500, JPEG or 24-bit PNG, no alpha | `store/feature-graphic-1024x500.png` | **CI** |
| Phone screenshots | 2–8; PNG/JPEG; sides 320–3840; longer ≤ 2× shorter; 9:16 for recommendation placements | 8 at 1080×1920 | **CI** |
| Title ≤ 30, short description ≤ 80, full ≤ 4,000, release notes ≤ 500 characters; no "free", "best", "#1" or emoji in the title or the screenshots' captions | `docs/m11/store-listing.md` | **CI** `check_listing_claims.py` |
| Privacy policy URL live and matching the app | `pages.yml` from the app's strings | **both** CI builds the page; the owner turns Pages on and opens the URL |
| Data safety, app content, VpnService and foreground-service declarations | `docs/m11/declarations.md` | **owner** enters; §3 ticks |
| Personal-account closed testing (if §1.1 is yes) | 12 testers, 14 days, then apply | §4 | **owner** |

## 3. The build and its proof (the release commit)

| | Line | By | Date | Proof |
|---|---|---|---|---|
| ☐ | The release commit is on `main` (PR #10 merged after PR #9 and #8), its `android` run green in three attempts | | | run id |
| ☐ | Its `release` run (hand-started on `main`, input `fingerprint` = the upload certificate's) green: `bundle` and both `smoke` jobs | | | run id, `release-facts.txt`: `mode=upload-key`, `event=workflow_dispatch`, `ref=refs/heads/main`, the fingerprint |
| ☐ | The bundle's certificate fingerprint equals the upload certificate's in Play Console → Setup → App signing | | | both values written here: ‹…› |
| ☐ | `secrets` run green on the commit (no key, password or token in the repository or its history) | | | run id |
| ☐ | The variables `PROXYFLOW_SUPPORT_EMAIL` and `PROXYFLOW_DEVELOPER_NAME` set; the `pages` run green without its "no contact address" warning; the privacy policy URL opens, names the address and the developer, and says "describes ProxyFlow version 1.0.0 (11)" | | | URL opened on a phone |
| ☐ | The release's version is 1.0.0 (11); the next upload's versionCode will be 12 | | | `app/build.gradle.kts` |
| ☐ | `docs/m11/M11-acceptance-report.md` fact-checked against the runs above | | | |

## 4. Closed testing (internal first)

| | Line | By | Date | Proof |
|---|---|---|---|---|
| ☐ | **Internal testing** track: the signed bundle uploaded, the owner's own phone installs it from Play (the app updates over the test-signed M10 APK only after uninstalling it: another certificate) | | | Play Console release id |
| ☐ | `docs/m10/physical-phone-checklist.md` run on the owner's phone with the Play-installed build and the owner's own proxy; its table in `docs/m10/compatibility-matrix.md` §3 filled | | | the checklist's results |
| ☐ | **Closed testing** track created, the testers' list (e-mail list or Google Group) added, the opt-in link sent to the 12 or more testers; each tester opted in | | | tester count on the track's page |
| ☐ | Day 14 (consecutive) reached with 12 or more still opted in — the date written here: ‹…› | | | the track's page |
| ☐ | Crash-free sessions ≥ 99.8 % and no ANR in Play Console → Quality → Android vitals over the test; every crash with a ProxyFlow frame filed in `docs/m11/defect-register.md` and fixed or accepted | | | vitals screenshot or export |
| ☐ | Tester feedback read; defects filed and resolved; a new build uploaded to the track for each fix (versionCode +1 each) | | | |
| ☐ | The representative devices of `docs/m10/compatibility-matrix.md` §4 covered as far as the testers' phones allow (makers' battery management, Android 10 and 14 on ARM, a low-memory phone); the gaps named | | | |
| ☐ | (Personal accounts) "Apply for production" submitted with the closed test's summary; the answer received | | | date, answer |

## 5. Play Console: listing and declarations

| | Line | By | Date | Proof |
|---|---|---|---|---|
| ☐ | App created: name, default language en-US, app (not game), free | | | |
| ☐ | Main store listing: title, short and full description pasted from `docs/m11/store-listing.md` §2–§3 (no edits; an edit goes to the document first and through the checker) | | | |
| ☐ | Graphics: icon, feature graphic, the 8 screenshots in order | | | |
| ☐ | Contact details: the support e-mail (§1.4), the website, the privacy policy URL | | | |
| ☐ | App content: privacy policy, ads = no, app access with a review proxy (revoked after approval), content rating questionnaire, target audience, news = no, data safety = no collection, advertising ID = no, government = no, financial = none, health = none | | | `docs/m11/declarations.md` §1–§2 |
| ☐ | Foreground service declaration (if Play asks for `systemExempted`): description, impact, video link, use case | | | §4 of the declarations |
| ☐ | VpnService declaration: core functionality = yes; videos (app and VPN in use ≤ 90 s; prominent disclosure with consent and refusal ≤ 90 s); data = no; manipulation = no | | | §5 of the declarations; the smoke's `smoke.mp4` or the owner's recording |
| ☐ | D2 decided and, if (b), the TLS-required build is what was uploaded | | | |
| ☐ | Store listing preview checked on a phone (Play Console's preview): line breaks, bullets, the screenshots' captions legible | | | |
| ☐ | Countries: all, or the owner's choice; no country where a proxy client is unlawful for the owner to distribute (the owner's call) | | | |

## 6. Keys and backups (`docs/m11/signing.md`)

| | Line | By | Date |
|---|---|---|---|
| ☐ | The upload key made with `scripts/release/make_upload_key.sh` outside any checkout; the directory is `700`, the keystore `600` | | |
| ☐ | Two offline backups of `proxyflow-upload.p12`, in two places; the password and the fingerprint in the password manager | | |
| ☐ | Play App Signing: Google-generated app signing key; the upload certificate registered; its SHA-256 in Play Console equals the key's | | |
| ☐ | The three GitHub secrets set (and the variable `PROXYFLOW_KEY_ALIAS` if the alias is not `proxyflow-upload`); a hand-started `release` run says `mode=upload-key` and the fingerprint matches | | |
| ☐ | The keystore and the password are nowhere in the repository, its history, CI logs, chats or e-mail (`secrets` run green; a search of the owner's mailbox and chat for the file name) | | |

## 6a. After launch, the channels that must be open (M12)

- [ ] **GitHub private vulnerability reporting is on**: repository Settings → Code security (and analysis) → *Private
      vulnerability reporting* → Enable. It is off by default, and `SECURITY.md` and the issue chooser send reporters
      to `…/security/advisories/new`, which outsiders cannot use until it is on. Checked by opening that address
      signed out.
- [ ] GitHub Pages is on (Settings → Pages → Source: GitHub Actions) and the repository variable `PAGES_ENABLED` is
      `true`, so the privacy policy and a known-issue notice (`docs/m12/incident-response.md` §3) deploy from `main`.
- [ ] The support mailbox's auto-reply is the text of `docs/m12/support.md` §3.
- [ ] The second person of `docs/m12/maintenance-runbook.md` §1.1 is named below, and has the Play Console user, the
      key backup, the repository admin and the mailbox access.
- [ ] The drill of `docs/m12/incident-response.md` §6 and `docs/m12/rollback.md` §6 is done and dated.
- [ ] A calendar reminder on the 8th of each month: "the issue *Maintenance review YYYY-MM* exists" (§2 of the runbook).

## 7. Sign-off

Submission to **production** is allowed when every line in §1–§6a is ticked and the open items below are empty.

| Role | Name | Date | Signature |
|---|---|---|---|
| Owner | | | |

Second person (`docs/m12/maintenance-runbook.md` §1.1): named on ‹date› — the name and how to reach them are not
written in this public repository; they are in the owner's password manager beside the key's password.

Open items at sign-off (must be empty): ‹…›

Submitted to production on: ‹date›, release ‹versionName (versionCode)›, bundle SHA-256 ‹from release-facts.txt›.
