# 04 — UX: screens, states and error catalogue

**Milestone:** M1 · **Status:** Draft for product-owner review · **Design file:** "ProxyFlow V1 — Screens & Error States" (Claude Design artifact; link in the M1 acceptance report)

This document is the specification the design file implements. Every error state maps to an engine error code from the spike (`spike/engine/adapters/errors.go`), so M4–M9 can wire states without re-deciding copy or behaviour.

## 1. Principles

1. **Truthful status.** "Connected" means the engine has a working tunnel to the proxy (last proxy handshake succeeded), not merely that the VPN interface exists. Status is never shown by colour alone (icon + text + colour).
2. **Fail closed, and say so.** When something breaks while connected, traffic is blocked, never sent around the proxy; the UI states "Traffic is paused" and offers the fix.
3. **Plain language first, code second.** Each error shows a human title and next step; the stable code (e.g. `AUTH_FAILED`) appears in the details/log for support.
4. **Honest limits.** UDP, ping and some IPv6 behaviour are explained where they matter (onboarding, Home "protection" line, About).
5. **Privacy by default.** Logs omit destinations unless the user turns that on; credential fields are hidden from screenshots/recents (`FLAG_SECURE` on Add/Edit).
6. **Accessible.** WCAG 2.2 AA colours (see `design/tokens/contrast-report.md`), 48 dp touch targets, TalkBack labels for every icon button, text reflow at 200 % font scale, motion respects "Remove animations".

## 2. Navigation

Bottom navigation with four destinations — **Home · Profiles · Logs · Settings**. About & Privacy opens from Settings. Add/Edit Proxy is a full-screen route (from the Profiles FAB, the Home empty state, or the Home profile picker). First-launch onboarding runs once.

## 3. Screen inventory and states

| ID | Screen | States designed |
|---|---|---|
| A1–A3 | Onboarding | Welcome · How it works & limits · Notifications explainer (Android 13+) |
| B1 | Home — no profile | Empty state with "Add proxy" |
| B2 | Home — disconnected | Selected profile card, big Connect button |
| B3 | Home — connecting | Progress ring, step text, Cancel |
| B4 | Home — connected | Timer, ↑/↓ counters, active connections, protection summary, Disconnect |
| B5 | Home — reconnecting | Amber state, "traffic paused", attempt counter |
| B6a–B6d | Home — errors | B6a sign-in failed while connected (blocked) · B6b proxy unreachable at connect, also the layout for every pre-flight failure (not connected, not blocked) · B6c certificate not trusted while connected (blocked) · B6d VPN removed by Android (not blocked) |
| B7 | Home — offline | Waiting for network (still blocking) |
| B8 | Profile picker sheet | List with active marker, "Add proxy" |
| B9 | VPN permission explainer sheet | Before the system dialog |
| B10 | Notification | Connected / Reconnecting / Error variants with Disconnect action |
| B11 | Quick Settings tile | Connected · Blocked · Off · No profile |
| C1–C3 | Profiles | Empty · List with test results · Delete confirmation |
| D1, D3–D9 | Add/Edit proxy | Empty (D1) · Validation errors (D3) · TLS-off warning (D4) · Testing (D5) · Test passed (D6) · Test failed (D7) · Unsaved-changes dialog (D8) · Reconnect-with-changes dialog when editing the active profile (D9). A filled, valid form appears in D4–D7, so there is no separate "valid" board (D2 retired). |
| E1, E1b | Settings | E1: system kill switch off ("Set up") · E1b: system kill switch on, status notifications off ("Turn on") |
| E2 | System lockdown guide | Always-on VPN + "Block connections without VPN" steps |
| F1–F3 | Logs | Empty · List with filters · Entry detail sheet |
| G1 | About & Privacy | Version, privacy summary, limitations, licences |
| H1–H3 | Blocking dialogs | VPN permission denied · Another VPN is always-on · Credential unreadable |

Dark theme is designed for B2, B4, B6a and C2 (all other screens follow the same tokens). The design file has 43 boards: 36 clickable phone screens, the error catalogue, the notification and Quick Settings sheets, and four dark-theme boards.

## 4. Error-state catalogue

**Level** — *Proxy*: affects all traffic → global state (Home banner, notification). *Target*: one destination refused → Logs only (the tunnel stays up). *System*: Android-level condition.

| Code | Level | Title (EN) | Message | Primary action | Secondary | While connected |
|---|---|---|---|---|---|---|
| `PROXY_UNREACHABLE` | Proxy | Can't reach your proxy | ProxyFlow couldn't connect to {host}:{port}. Check the address and port, and that the proxy is online. | Retry | Edit profile | Reconnecting (B5), traffic blocked |
| `PROXY_DNS_FAILED` | Proxy | Proxy address not found | {host} couldn't be found. Check the spelling or use the proxy's IP address. | Edit profile | Retry | Reconnecting, blocked |
| `AUTH_REQUIRED` | Proxy | Sign-in required | This proxy needs a username and password. | Add credentials | — | Error (B6), blocked |
| `AUTH_FAILED` | Proxy | Username or password rejected | The proxy didn't accept your credentials. Check them with your proxy provider. *(HTTP Digest-only proxies: "This proxy only supports Digest sign-in, which V1 doesn't support.")* | Edit credentials | — | Error, blocked |
| `TLS_UNTRUSTED` | Proxy | Certificate not trusted | The proxy's certificate isn't from a trusted authority. ProxyFlow only connects to proxies with a valid certificate. | Edit profile | Learn more | Error, blocked |
| `TLS_HOSTNAME` | Proxy | Certificate doesn't match | The certificate isn't valid for {sni}. Check the server name in the profile. | Edit profile | — | Error, blocked |
| `TLS_EXPIRED` | Proxy | Certificate expired | The proxy's certificate is expired or not yet valid. Check your phone's date and time, or contact your provider. | Retry | Date & time settings | Error, blocked |
| `TLS_HANDSHAKE` | Proxy | Secure connection failed | The proxy didn't answer with TLS. It may not support TLS on this port. | Edit profile | — | Error, blocked |
| `PROTOCOL_ERROR` | Proxy | This doesn't look like a {SOCKS5/HTTP} proxy | The server answered in an unexpected way. Check the protocol and port. *(405/501: "This HTTP proxy doesn't allow tunnels (CONNECT).")* | Edit profile | — | Error, blocked |
| `HANDSHAKE_TIMEOUT` | Proxy | Proxy isn't responding | The proxy accepted the connection but didn't respond in time. | Retry | Edit profile | Reconnecting, blocked |
| `TARGET_NOT_ALLOWED` | Target | Blocked by your proxy | {proxy} refused a connection to {destination or "a site"} (port {port}). | — | — | Logs only |
| `TARGET_UNREACHABLE` | Target | Site unreachable via proxy | The proxy couldn't reach {destination}. | — | — | Logs only |
| `TARGET_REFUSED` | Target | Connection refused | {destination} refused the connection. | — | — | Logs only |
| `TARGET_UNSUPPORTED` | Target | Not supported by proxy | The proxy doesn't support this kind of destination. | — | — | Logs only |
| `PROXY_GENERAL_FAILURE` | Target | Proxy error | The proxy reported a general failure for one connection. | — | — | Logs only |
| `STALE_DNS_MAPPING` | Target | Old address used | An app used an address from a previous session; it will retry. | — | — | Logs only |
| `CONNECTION_LIMIT` | Target | Too many connections | ProxyFlow reached its connection limit; new connections are refused until others close. | — | — | Logs (warning) |
| `APP_HANDSHAKE_FAILED` | Target | App closed the connection | An app gave up before its connection was ready. | — | — | Logs (debug) |
| `INTERNAL` | Target | Internal error on one connection | One connection hit an internal error and was closed. Other traffic is unaffected. | — | — | Logs only (the engine recovers per connection) |
| `VPN_PERMISSION_DENIED` | System | VPN permission needed | Android needs your permission for ProxyFlow to route apps through your proxy. | Try again | — | n/a |
| `VPN_REVOKED` | System | Disconnected by Android | Another VPN app was turned on or the permission was removed. Your apps now use the normal network. | Reconnect | — | Disconnected (not blocked — Android removed the VPN) |
| `ALWAYS_ON_OTHER_APP` | System | Another app is your Always-on VPN | Turn off Always-on VPN for that app in Android settings, then try again. | Open VPN settings | — | n/a |
| `OFFLINE` | System | You're offline | ProxyFlow will connect when a network is available. Traffic stays blocked meanwhile. | — | Disconnect | Waiting (B7), blocked |
| `CREDENTIAL_UNREADABLE` | System | Re-enter your password | The saved password can't be read on this phone any more, so it isn't used. Enter it again. | Edit profile | — | n/a |
| `ENGINE_STOPPED` | System | Protection stopped unexpectedly | The packet engine stopped while connected. Traffic stays blocked until you restart it or disconnect. | Restart | Copy diagnostics | Error, blocked |
| `PROFILE_DELETED` | System | Profile deleted | The profile this connection used was deleted, so its proxy isn't used any more. Traffic stays blocked until you stop the connection. *(M5: only if the profile disappears other than through "Disconnect and delete", which disconnects first.)* | Stop and use normal network | — | Blocked |

*Codes not emitted by the engine (`VPN_*`, `ALWAYS_ON_OTHER_APP`, `OFFLINE`, `CREDENTIAL_UNREADABLE`, `PROFILE_DELETED`) are produced by the VPN service / storage layers in M3/M5; `ENGINE_STOPPED` is reported by the engine when its packet loop ends without being stopped (the TUN closed or failed, M4) and acted on by the VPN service. The engine's own split is `IsProxyLevel()` in `engine/adapters/errors.go`: 10 proxy-level codes, 10 others; only proxy-level codes count towards the escalation rule. One of the others, `CANCELED` (M4), is internal: a connection still being set up when the engine stops or resets its flows; it is counted, never shown and never logged as a failure.*

*Pre-flight check (M5, ADR-0010 §4): `TARGET_NOT_ALLOWED`, `PROXY_GENERAL_FAILURE` and `TARGET_UNSUPPORTED` from the check mean the proxy did not open its tunnel, so the attempt fails (B6b) with these texts instead of the per-connection ones: "Your proxy refused the connection" — "{host}:{port} refused to open a test connection. Providers answer this way when a plan has ended or your network's address isn't allowed. Check your account with your proxy provider."; "Proxy error" — "{host}:{port} answered the test connection with an error. Check your account with your proxy provider."; "Not supported by your proxy" — "The proxy doesn't support the connections ProxyFlow needs: tunnels to a site by its name." `TARGET_UNREACHABLE` and `TARGET_REFUSED` pass the check: the proxy works, only the check's destination was out of its reach.*

Escalation rule: a proxy-level code from **3 consecutive** dials (or any auth/TLS code once) switches Home to the error state; the first success clears it. Target-level codes never change the global state.

Before the VPN is established (the pre-flight check in B3), any proxy-level code is shown in the B6b layout with that code's title and message, and nothing is blocked — the phone's network was never switched over (`03-user-journeys.md`, J5). The "While connected" column applies once connected.

## 5. Form validation (Add/Edit)

| Field | Rule | Message |
|---|---|---|
| Server | required; valid hostname (LDH, ≤253) or IPv4/IPv6 literal | "Enter a hostname or IP address" |
| Port | 1–65535 | "Port must be between 1 and 65535" |
| Username | ≤255 bytes (SOCKS5); no ":" (HTTP Basic) | "Usernames can't contain ':' for HTTP proxies" |
| Password | requires username; ≤255 bytes (SOCKS5) | "Add a username for this password" |
| TLS server name | optional; valid hostname | "Enter a valid server name or leave empty" |
| Name | optional; ≤40 chars; defaults to server | — |

Plaintext warning (TLS off): *"Without TLS, your username, password and the names of sites you open can be seen by anyone on the network between this phone and the proxy."* Shown inline whenever TLS is off; emphasised when credentials are set. (See ADR-0005 for the Google Play policy implication.)

## 6. Copy and localisation

English is the source language. Strings are externalised from M2; Nepali and Japanese are candidate first translations (owner decision). Error titles stay under 40 characters to survive translation; layouts tested at 200 % font scale.

## 7. Review checklist (self-review done in M1; owner review pending)

- [x] Every journey step in `03-user-journeys.md` has a designed screen or state (the independent review found four gaps — J1 notifications off, J7 Quick Settings tile, J9 kill switch on, J11 reconnect with changes — now covered by E1b, B11 and D9)
- [x] Every engine and system error code has a designed presentation (25 codes; levels match `IsProxyLevel()`)
- [x] Status never colour-only; text contrast ≥ 4.5:1 in both themes
- [x] Destructive actions confirmed (delete profile, disconnect while blocked)
- [x] Credentials masked by default, reveal toggle, screen protected from capture
- [x] Limitations visible before first connect (onboarding A2) and in About
- [ ] Product-owner review of copy and flows
- [ ] Usability check with 3–5 target users (M9)
