# Security policy

ProxyFlow carries other apps' traffic to a proxy the user chose, so the problems that matter most are the ones a
user cannot see: traffic or a name getting around the proxy, a credential leaving the phone or showing up where it
should not, the kill-switch promises not holding, or a crafted packet or proxy reply breaking the engine. Those are
reported privately, and they are answered by a named person on a stated clock.

## Reporting

- **Privately, never as a public issue or pull request:** GitHub → Security → *Report a vulnerability*
  (`https://github.com/bibekthapamgr/app/security/advisories/new`), or the support address on ProxyFlow's Google Play
  page with "Security" in the subject.
- **Never include a proxy password, username, token or private server address.** The owner never needs them; a
  report that would need them to reproduce is reproduced with the owner's own proxy or the test lab
  (`scripts/e2e_env.sh`). If you pasted one anyway, change it at your provider now and say so in the report.
- What helps: the ProxyFlow version (About & privacy), Android version and phone, the proxy type (SOCKS5 or HTTP
  CONNECT, with or without TLS to the proxy), what you did, what you saw, and — for a routing claim — how you observed
  it (a packet capture at the router, a witness server that answered, a provider's log).

## The owner and the clock

| | |
|---|---|
| Named owner | The repository owner (`bibekthapamgr`), who signs the releases and holds the upload key; there is no other maintainer in V1 |
| Acknowledgement | Within **3 days** of the report |
| First assessment (severity, affected versions, whether a fix or a halt is needed) | Within **7 days** |
| Fix for a **critical** finding (a leak around the proxy while connected, a credential exposed, the kill switch not holding, a crash a remote peer can cause with a crafted reply or packet) | A fixed release to Play's internal track within **14 days**, then the staged rollout of `docs/m12/rollback.md`; while it is being fixed, the current rollout is halted and the known-issue notice is published (`docs/m12/incident-response.md`) |
| Fix for **high** (a sanitizer missing a field, TLS checks weakened, a weakness without a demonstrated leak) | Within **30 days** |
| **Medium and low** | The next planned release; recorded in the changelog |
| Disclosure | After the fixed release has reached 100 % of the rollout, in the changelog and the GitHub advisory, crediting the reporter if they wish; a reporter who wants to publish earlier is asked to wait until users have the fix, and told the dates |

Severities, and the process from the first message to the closing note, are in `docs/m12/incident-response.md`.

## Supported versions

Only the latest release on Google Play is supported; a fix is a new version, since Play cannot roll back
(`docs/m12/rollback.md`). The oldest Android supported is Android 10 (API 29).

## What is in scope

ProxyFlow's app and engine, its build and release workflows, and its documents. Not in scope: the user's proxy
provider, Android itself, and the networks between — though a finding about how ProxyFlow *handles* a hostile
proxy or network is very much in scope.

## How findings are handled afterwards

Every accepted finding gets a defect entry (`docs/m12/release-history.md` points to the register of the release that
fixed it), a regression test where one can be written (`docs/m12/qa-process.md`), and — when it changed what the app
keeps or sends — a privacy policy and Play declaration review (`docs/m12/policy-review.md`).
