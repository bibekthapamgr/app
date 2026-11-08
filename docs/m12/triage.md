# M12 — Triage

What a report becomes: its kind, its severity, where it is written down, and how fast it is answered. Brief task 3
("triage compatibility, connection and security bugs").

## 1. The kinds, and the first question for each

| Kind | Label | The first question | Then |
|---|---|---|---|
| **Security** — a leak around the proxy, a credential where it should not be, the kill switch not holding, a remote crash | `security` | Is it demonstrated (a capture, a witness, a log line) or suspected? | `incident-response.md` §2 at once; nothing of it in a public issue |
| **Routing** — an app or site does not work while Connected, or works when it should be blocked | `routing` | Is it a documented limit? UDP, QUIC, ping, local devices, IPv6 without a proxy that has it, Android's own traffic and work profiles (`docs/m6/routing-and-limitations.md`) | A documented limit → the answer links the document and the help page, and the issue is closed as "by design" with the count kept (§4); otherwise reproduce in the lab with a capture (`scripts/e2e_device.sh` has every check) |
| **Connection** — cannot connect, drops, reconnects, "proxy refused", slow | `connection` | Does the proxy answer at all from the phone's network? Which catalogue code does Home or the log show (`AUTH_FAILED`, `PROXY_UNREACHABLE`, `TLS_UNTRUSTED`, …: `docs/m1/04-ux-screens-and-error-states.md` §4, `docs/m7/logs-stats-reconnect.md`)? | The code names the cause: a provider or network problem (`AUTH_FAILED`, `PROXY_UNREACHABLE`, a certificate code) is answered with the help page's line for it and what to tell the provider; a ProxyFlow problem (a refusal from a proxy that works elsewhere, a reconnect that never ends) is a defect reproduced with the lab's gate and Dante's `block(` rules |
| **Protocol compatibility** — one provider's proxy does not work (SOCKS5 or HTTP CONNECT variants, TLS fronts, odd replies) | `compatibility` | What does the provider's proxy answer? (The reporter runs `curl -v --proxy <host:port> https://example.com` from a computer **without `-U`/`--proxy-user`** — the 407 reply and its `Proxy-Authenticate` line are what matter, and a run with the sign-in prints `Proxy-Authorization: Basic <the password, base64>` into what gets pasted — and pastes the reply with the host written as `my-proxy`; the owner runs the paste through `redact_report.py` anyway, which covers `-U` and the header) | `docs/m4/protocol-compatibility.md` says what ProxyFlow speaks; a deviation the RFCs allow is a defect; one they forbid is answered and the provider named in the compatibility notes, if the reporter agrees |
| **Device compatibility** — one phone, one Android version, one maker's battery manager | `device` | Which Android version, maker and model? Is it in `docs/m10/compatibility-matrix.md` §3–§4? | The emulator of that version first; the maker's power management is the checklist of `docs/m10/physical-phone-checklist.md`; a maker-specific kill is documented with the maker's setting that stops it (the help's "When Android stops ProxyFlow" page, `docs/m10/physical-phone-checklist.md`) |
| **Crash** (vitals or a report) | `crash` | Is there a ProxyFlow frame in the trace? | With a frame: a defect, the trace's sanitized message the key; without: watched, with the device and version noted |
| **UI, text, accessibility** | `ui` | Which screen, which language setting, which font scale? | The Roborazzi screenshot of that screen at that scale; `docs/m9/ui-integration.md` |
| **Question or request** | `question`, `enhancement` | — | Answered; a request goes to the next milestone's list, never into a patch release |

## 2. Severity and the clock

Severity as `incident-response.md` §1 (critical, high, medium, low), with the same clocks: critical and high on
`SECURITY.md`'s; medium into the next planned release; low when convenient. Every report is answered within 3
working days whatever its severity — with what was found, what is still needed (never a password), or the document
that explains the behaviour.

## 3. Where it is written

- A **defect** goes into the register of the release that will fix it (`docs/m<n>/defect-register.md` for a milestone,
  `docs/m12/defects/<version>.md` for a maintenance release, created from the M11 register's columns), with the issue
  number, the severity, the fix and the test that guards it; the issue carries the register id.
- A **by-design** answer goes into §4's count, so that a limit many users hit becomes a help text, an onboarding
  line, or a feature request for the next milestone.
- A **security** report is written only in the private advisory until disclosure.

## 4. What users hit most (kept by the owner; the top three decide the next release's help text)

| Limit or behaviour | Reports | Where it is explained | Changed |
|---|---|---|---|
| — | — | — | — |

## 5. Labels

`bug` (the template's default), `triage` (until §1 is answered), one kind label, `by-design`, `needs-info` (the
answer asked for something; closed after 14 days without a reply), `duplicate`, `security` (only ever on a private
advisory, never an issue), `maintenance` (the monthly review issue), `dependencies` (dependabot).
