# M12 — Support and bug reports

How a user reaches ProxyFlow's developer, what they are told to send and never to send, and what the owner does with
what arrives. Brief tasks 1 and 2 ("track privacy-respecting crash/error signals and user feedback"; "a support/bug-
report process that tells users not to share proxy passwords").

## 1. The channels, and what each gives

| Channel | What arrives | Privacy |
|---|---|---|
| **Google Play Console → Android vitals** (crashes, ANRs, the crash rate) | Stack traces of crashes and freezes from phones whose users share usage and diagnostics with Google, with the device model, Android version, app version and region | Google's collection, under the user's own Android setting; ProxyFlow adds no SDK. The messages ProxyFlow hands to Android are sanitized — no credential, server, site or payload (`ExceptionSecret` lint, M8) — so a stack trace holds no user secret. The privacy policy says so (`privacy_sent_crash`). |
| **Google Play reviews and the "Send feedback" of the listing** | Ratings and text; the owner may reply publicly | Public; the owner never asks for a password or server in a reply, and asks the reviewer to use the e-mail for anything private |
| **The support e-mail** on the listing and the privacy page (decision D10) | Anything the user writes; the in-app log export attached when asked | Private to the owner's mailbox; the auto-reply of §3 goes out first |
| **GitHub issues** (`.github/ISSUE_TEMPLATE/bug_report.yml`) | A form with the version, phone, proxy type, steps, and the exported log — behind a required checkbox "this report contains no proxy password, username, token or private server address" and a warning in bold at the top | Public; the owner redacts anything that slipped through with `scripts/release/redact_report.py` and **deletes the issue's or comment's revision** (§4 step 1: an edit keeps the original in the "edited" history); blank issues are off, and the chooser's contact link sends security reports to the private channel (there is no public security template) |
| **Security reports** | `SECURITY.md`: GitHub's private vulnerability reporting, or the e-mail with "Security" in the subject | Private; the owner's clock in `SECURITY.md` |

No channel of ProxyFlow's own collects anything: there is no in-app feedback form, no crash SDK, no analytics
(ADR-0016 §6; the Data safety declaration says "no collection", and this is why it stays true).

## 2. What a user is told

The same message everywhere — the issue template, the e-mail auto-reply, the listing's support text, and the app's
own Help → "Getting help" (`help_feedback_body`, pinned by `CopyClaimsTest`: "Never send your proxy password,
username or token", "the developer never needs them", "change it at your provider"; the app's text leaves out the
`my-proxy` advice, which is for a written report):

> **Never send your proxy password**, username or token — not even once; the developer never needs them, and if you
> have already sent one, change it at your provider now. Write `my-proxy` instead of your server's real name unless the
> problem depends on it. The in-app export (Logs → Export log) already removes passwords, server names and site names:
> send that, with your ProxyFlow version (About & privacy), your Android version and what you did.

## 3. The e-mail auto-reply (the owner sets it in the mailbox)

> Thanks for writing to ProxyFlow. This is an automatic reply; a person answers within 3 working days.
> Please check your message has no proxy password, username, token or server address in it — the developer never needs
> them, and a message that has one is deleted after the reply asks you to change the password at your provider.
> What helps: the ProxyFlow version (About & privacy), your phone and Android version, the proxy type (SOCKS5 or HTTP,
> with or without TLS to the proxy), what you did and what you saw, and the exported log (Logs → Export log), which
> already removes anything private.
> For a security problem, put "Security" in the subject: it is handled first (SECURITY.md in the repository).

## 4. What the owner does with a report

1. **Read for secrets first.** A password, username, token or server address in the report: run the text through
   `scripts/release/redact_report.py` before it is kept anywhere (an issue, a note, a register), delete the original
   mail after replying, and tell the reporter to change the password at the provider. The script says what it found.
   **On GitHub the edit is not enough**: an edited issue body or comment keeps every earlier revision, readable by
   anyone through the "edited" menu — so after editing, open that menu and **Delete revision** for each earlier
   revision (or delete the comment and post the redacted text anew), and treat the secret as public for the time it
   was there: the reporter changes the password whatever the owner did.
2. **Reproduce without the reporter's proxy**: the owner's own proxy, or the lab (`scripts/e2e_env.sh`) on an emulator
   of the reporter's Android version (`docs/m10/compatibility-matrix.md` names the images); a bug that reproduces only
   with the reporter's provider is a protocol-compatibility bug, handled as `docs/m12/triage.md` §3 says.
3. **Triage** (`docs/m12/triage.md`): severity and kind, an entry in the release's defect register, a label on the
   issue; a security kind goes to `docs/m12/incident-response.md` at once.
4. **Answer** within 3 working days — what was found, or what is still needed (never a password), and the version
   that will carry the fix or the document that explains the behaviour (many reports are the documented limits:
   UDP, ping, local devices, Android's own traffic — `docs/m6/routing-and-limitations.md`, linked from the issue
   template's contact links).
5. **Close** with the fixed version's number once it is on Play, and the regression test that guards it.

## 5. The signals the owner reads on a schedule (`docs/m12/maintenance-runbook.md` §2)

Weekly: Android vitals (the crash rate against the 0.2 % bar — 99.8 % crash-free in M10's terms — the user-perceived crash rate against Google's threshold, new crash clusters, ANRs), new reviews, new issues
and mail. A new crash cluster with a ProxyFlow frame is a defect the same day; a cluster in Android's or a library's
code with no ProxyFlow frame is noted with its device and version and watched. The crash rate over the last 28
days is written into `docs/m12/release-history.md` at each release.
