# M12 — The policy review

When and how the privacy policy, the Data safety form and the VpnService declaration are read against the app, so
that they stay true. Brief task 8 ("review privacy policy and Play declarations whenever data behavior changes")
and acceptance "policy and dependency reviews are scheduled".

## 1. When

- **Whenever the data behaviour changes** — mechanically: `scripts/check_data_behavior.py` fails every build when
  any of its ten items differs from `docs/m12/data-behavior.lock` (the manifests' permissions and services, the
  connectivity-check destination, the Room schemas, the DataStore keys, the log's retention, the `privacy_*`
  strings, the policy document, the declarations document, the dependency review). The build stays red until the
  review below is done and the lock rewritten with who reviewed and what changed (`--update --reviewed-by --note`).
  Adding a library is such a change (the dependency review's list changes): a library that phones home is a data
  behaviour.
- **Every quarter** (January, April, July, October), from the monthly maintenance issue, whether or not anything
  changed: Google's forms and policies move (the Data safety form's categories, the VpnService policy, the
  User Data policy), and the review is of the app against *today's* forms.
- **At every target API bump** (`maintenance-runbook.md` §3.3): a new Android changes what a VpnService, a
  foreground service or a notification may do, and the declarations' wording with it.

## 2. What is read, against what

| Document | Read against | What a difference means |
|---|---|---|
| `docs/m9/privacy-policy.md` (= the app's `privacy_*` strings = `site/privacy.html`) | The manifests (permissions, services), `VpnSession.kt`'s destinations (`PREFLIGHT_TARGET`), what `LogRepository` and the exporter write, `ProfileRepository`'s encryption, the libraries on the release classpath (`docs/m4/dependency-review.md`, the build's "RELEASE RUNTIME CLASSPATH" facts) | The policy says less than the app does → a policy change, a release, the Play forms updated; the policy says more → the text corrected (`scripts/privacy_policy.py` regenerates the document; the tests keep the three copies equal) |
| Play Console → App content → **Data safety** | The same, and the form's *current* categories and exemptions | A category the app now touches (an identifier, a new destination) → the form answered anew, the policy's section too |
| Play Console → App content → **VPN** (the VpnService declaration) | The current VpnService policy text; the app's use (core functionality, the videos, no manipulation, no collection) | A new requirement → the declaration redone, and D2's answer re-read |
| Play Console → App content → **Foreground service** | Android's current rules for `systemExempted` and the VPN exemption | — |
| `docs/m11/declarations.md` | All of the above | The document updated to what was entered, with the date |
| `docs/m11/store-listing.md` | The app's limits (`docs/m6/routing-and-limitations.md`) | `check_listing_claims.py` keeps the claims out; the review keeps the limits in |

## 3. How it is recorded

- The lock: `python3 scripts/check_data_behavior.py --update --reviewed-by "<name>" --note "<date>: <what changed, what was read>"`,
  committed with the change that caused it (the diff shows the reviewer the items that moved).
- A quarterly review that changed nothing: the lock's note updated with the date and "quarterly review, no change"
  (an `--update` with the same fingerprints), so the review's date is in the repository.
- A review that changed the policy: the privacy policy's version line follows the release that carries it; the
  changelog's section names the change under *Changed* ("Privacy policy: …"); the Play forms re-submitted with the
  release; `docs/m11/declarations.md` updated.

## 4. The review's questions (the short list)

1. Does ProxyFlow send anything to anyone but the user's proxy? (Today: one check connection to
   `connectivitycheck.gstatic.com` *through the proxy*; nothing to the developer.)
2. Does it keep anything new on the phone, for longer, or in the clear?
3. Does any library on the release classpath collect, report or phone home? (Each new library's documentation read
   for "analytics", "telemetry", "crash", "advertising", "identifier".)
4. Does the app ask a new permission, or use a feature with a Play declaration (location, SMS, accessibility,
   all-files, exact alarms, foreground-service types)?
5. Did Android's own behaviour change what the app's traffic or crash reports include?
6. Does the listing or the help still say every limit (`check_listing_claims.py`'s required list)?

## 5. Review log

| Date | By | Trigger | Changed |
|---|---|---|---|
| 2026-11-08 | M11's reviews C, F, G, H (the declarations and the policy against the app), recorded at M12 | The first lock | Nothing: the behaviour as the policy 1.0.0 (11), the Data safety and VpnService declarations describe it |
