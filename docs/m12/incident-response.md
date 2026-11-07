# M12 — Incident response

What happens when ProxyFlow is found to do what it promises not to — send traffic or a name around the proxy, expose a
credential, let the kill switch fail, crash from a packet — or when a release breaks connecting for many users.
Brief task 7 ("incident-response runbook") and acceptance "critical security reports have a named owner and response
process". The owner is `SECURITY.md`'s; the clocks are its.

## 1. What counts as an incident, and its severity

| Severity | What | Examples | First action |
|---|---|---|---|
| **Critical** | A security promise broken for users of the release on Play, or the release unusable for most | A TCP connection or a DNS query leaves the phone around the proxy while Connected or Reconnecting; a credential readable off the phone, in a log, a crash report, a file or on the wire without TLS where the user chose TLS; the kill-switch guidance wrong in a way that leaves users unprotected; a crafted proxy reply or packet crashes the engine (remote denial of service); Connect fails for most users after an update | Halt the rollout within the hour (`rollback.md` §3); the known-issue notice; the fix on the 14-day clock |
| **High** | A security weakness without a demonstrated leak, or a loss of function for many | TLS checks weaker than documented; a sanitizer missing a field; a reconnect that could, under a sequence not yet seen, let a connection out; profiles lost or unreadable after an update; the connection test or the notification broken | Fix on the 30-day clock; a halt if the rollout is still staged |
| **Medium** | A defect that misleads or degrades | A log line that names a server; a wrong limitation in the help; a crash in a rare path; battery drain | The next release |
| **Low** | Cosmetic | — | When convenient |

A report's severity is set at the first assessment (within 7 days of the report; within the day for anything that
looks critical) and recorded in the release's defect register with the id the report gets there.

## 2. The process

1. **Receive** (private channel, `SECURITY.md`): acknowledge within 3 days — the reporter is thanked, told the
   severity will be assessed within 7 days, and asked to change any password that was in the report.
2. **Assess**: reproduce with the owner's proxy or the lab (never the reporter's credentials); for a routing claim, a
   packet capture at the uplink and the witness (`scripts/e2e_env.sh`, `scripts/m8_failure_injection.sh` give the
   method); the severity by §1; which versions on Play are affected; whether a halt is needed now.
3. **Contain** (critical): halt the staged rollout (`rollback.md` §3); if every user on Play is affected, the
   known-issue notice (§3) goes out the same day on the listing's "What's new" cannot (Play has no banner), so on the
   support page (`site/index.html`) and the repository's README, and as a reply to the reporter; the user's
   mitigation is named (usually: disconnect and use the proxy another way, or turn on Android's "Block connections
   without VPN" if the problem is a leak around the proxy when it should be blocked).
4. **Fix** on a branch from the release's tag (`rollback.md` §4): the defect's regression test first (the lab, a
   capture, the failure-injection harness — a test that fails on the tag and passes with the fix), then the fix,
   then the whole suite three times green, then the signed-bundle smoke.
5. **Release** the fix with the next versionCode as a staged rollout, watched at each step (`rollback.md` §2); the
   changelog's *Security* section names what was fixed, without the exploit's details until 100 %.
6. **Disclose** after 100 %: the GitHub security advisory (CVE if the reporter or the owner wants one), the
   changelog, credit to the reporter if wished; the reporter told the dates throughout.
7. **Record**: the defect register of the fixing release; `release-history.md`'s rollout column; a line in §5 below
   for the next reader; and, if what the app keeps or sends changed, the policy review (`policy-review.md`).

## 3. The known-issue notice (the template)

> **ProxyFlow 1.0.0 (11): known issue, 2026-MM-DD.** ‹What a user may see, in one sentence.› ‹Who is affected: all
> users / users with TLS to proxy / users on Android 14.› **What to do now:** ‹disconnect ProxyFlow and use your proxy
> another way until the fix / turn on Android's "Block connections without VPN" / nothing — it is cosmetic›. A fix is
> being prepared; it will arrive as version ‹x.y.z› through Google Play. Nothing you typed into ProxyFlow has left
> your phone unless it says so here: ‹it has not / your proxy password may have been visible to … — change it at
> your provider›.

Where: the support page (`site/index.html`, `pages.yml`'s deploy), the README's status line, the reply to every
report of the problem; and the Play listing's "What's new" of the fixing release says what was fixed.

## 4. A vulnerability in a library (OSV, govulncheck, a Play notice)

Reachable from the network or another app (the engine's parsers, TLS, the SOCKS5 and HTTP CONNECT adapters, Room's
SQLite, the Compose and AndroidX code that handles intents and notifications): severity by §1, usually high; a
fixed release on the 30-day clock, sooner if an exploit is known. Not reachable: the bump in the next planned
release, the allowlist entry with the reason meanwhile (`maintenance-runbook.md` §3.1).

## 5. Incidents

| Date | Id | Severity | What | Versions | Halted | Fixed in | Disclosed |
|---|---|---|---|---|---|---|---|
| — | — | — | None yet | — | — | — | — |

## 6. The drill (before the first production release, then yearly)

The owner runs the process once on a pretend incident — "a line reached the witness on API 34" — with the lab:
the acknowledgement written, the severity set, the halt done on the internal track (Play Console → Release → halt),
a branch from the tag, a test that fails (`scripts/m8_failure_injection.sh`'s witness check inverted on purpose),
a version bump, the signed-bundle smoke, the notice drafted. The date and what took longest go in this table:

| Date | Took longest | Changed afterwards |
|---|---|---|
| ‹before production› | | |
