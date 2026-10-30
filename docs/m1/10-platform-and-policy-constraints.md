# 10 — Platform and Google Play policy constraints

**Milestone:** M1 · **Status:** Research summary (checked 29–30 Oct 2026) — re-check every item at M11, policies change

## 1. Google Play VpnService policy

From the Play Console Help "VpnService" policy page ([source](https://support.google.com/googleplay/android-developer/answer/12564964?hl=en)):

| Requirement (paraphrased; quotes marked) | ProxyFlow position |
|---|---|
| Only apps whose **core functionality** is VPN-like may create a device-level tunnel to a remote server (exceptions listed for parental control, enterprise, security, network tools, browsers, carriers) | Core function is tunnelling apps' traffic to the user's proxy — fits; describe it that way in the declaration |
| "All apps that use the VpnService must complete the declaration form"; changes in API use require resubmission | Complete in M11; resubmit if the routing model changes |
| Document the use of VpnService in the Play listing | Listing states: routes apps' TCP traffic to the user's own HTTP/SOCKS5 proxy; lists what is blocked |
| Apps "must encrypt the data from the device to the VPN tunnel endpoint" | **Conflict for plaintext SOCKS5/HTTP profiles.** TLS-to-proxy profiles comply. Owner decision D2 / ADR-0005 |
| No collection of personal and sensitive data without prominent disclosure and consent; no redirecting/manipulating other apps' traffic for monetization (e.g. ads) | ProxyFlow collects nothing and has no ads; traffic is sent only to the proxy the user configured |
| Follow all other policies (Ad Fraud, Permissions, Malware) | Standard |

Precedent, not a guarantee: proxy-tunnel apps that accept plain HTTP and SOCKS5 proxies are listed on Google Play today — Oxylabs' setup guide for Super Proxy (Android and iOS) shows plain HTTP/SOCKS5 profiles with username and password ([source](https://developers.oxylabs.io/integrations/proxy-integrations/super-proxy)). Whether a new app with the same design passes review is uncertain; ADR-0005 keeps both outcomes buildable.

**Data safety form (M11):** ProxyFlow itself collects and shares no data. Traffic goes to the proxy the user configured, at the user's direction. The exact categorisation must be confirmed against the Data safety guidance before submission.

## 2. Target API level

From Android Developers "Meet Google Play's target API level requirement" ([source](https://developer.android.com/google/play/requirements/target-sdk)), as of the page's 28 Oct 2026 update:

- Since **31 Aug 2026**, new apps and app updates for phones/tablets must target **Android 16 (API 36)** or higher (extension possible to 1 Nov 2026).
- Existing apps must target API 35+ to stay available to new users on newer Android versions.

ProxyFlow: `targetSdk = 36`, `compileSdk = 36` now; move to the then-current requirement before M11 (the yearly requirement usually rises each August).

## 3. Foreground service type (Android 14+)

From the Android 14 behaviour-change page "Foreground service types are required" ([source](https://developer.android.google.cn/about/versions/14/changes/fgs-types-required?hl=en)):

- Type `systemExempted`, permission `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`; eligible users include "VPN apps (configured using Settings > Network & Internet > VPN)"; no runtime prerequisite; an ineligible app gets `ForegroundServiceTypeNotAllowedException`.
- ProxyFlow uses `systemExempted` for `ProxyFlowVpnService` and verifies on devices in M5 that it is accepted right after `VpnService.prepare()` consent; fallback `specialUse` (with its Play Console justification) if any device rejects it (ADR-0007).

## 4. Notifications

Android 13+ requires the `POST_NOTIFICATIONS` runtime permission. The status notification is requested in onboarding (A3). If denied, the VPN still works and Android still shows its own VPN key icon; Settings shows "Status notifications off".

## 5. `VpnService` behaviour that shapes the design

| Behaviour | Design consequence |
|---|---|
| User consent through `VpnService.prepare()`; only one VPN app active at a time | B9 explainer; `VPN_REVOKED` when another VPN starts (B6d) |
| Always-on VPN and "Block connections without VPN" (lockdown) are user settings; API 29+ exposes `isAlwaysOn()` / `isLockdownEnabled()` | Lockdown is the only protection when the app is not running (E11); guide E2; status in E1 |
| `addDisallowedApplication()` excludes apps; the VPN app itself must reach its server | App excludes itself; only proxy endpoint + proxy hostname lookup leave the phone (E16) |
| `allowBypass()` lets apps opt out of the VPN | Never called |
| `setUnderlyingNetworks()` tells the system which network the VPN uses (metered state, connectivity) | Updated on every default-network change |
| A `VpnService` applies to the Android user/profile that started it | Work profile and other users not covered (traffic matrix §3) |
| Tethering clients are not routed through `VpnService` by default | Documented as not covered |

## 6. Android version distribution and minSdk

Google's distribution data through December 2025, as reported by Android Headlines on 30 Jan 2026 ([source](https://www.androidheadlines.com/2026/01/android-version-distribution-numbers-2025-2026-market-share.html)): Android 16 7.5 %, 15 19.3 %, 14 17.2 %, 13 13.9 %, 12 11.4 %, 11 13.7 %, 10 7.8 %, 9 4.5 %, 8.1 2.3 %, 8.0 0.8 %, older 1.6 %.

| minSdk | Share of active devices (sum of the above) | What it buys |
|---|---|---|
| 26 (Android 8.0) | ≈ 98.4 % | Maximum reach |
| 29 (Android 10) | ≈ 90.8 % | `isAlwaysOn()` / `isLockdownEnabled()` for an honest kill-switch status; `getConnectionOwnerUid()` for per-app attribution in Logs; smaller device test matrix |

Recommendation: 29 (owner decision D3, ADR-0007).

## 7. Credential storage library status

`androidx.security:security-crypto` (EncryptedSharedPreferences, MasterKey, EncryptedFile): all APIs deprecated in 1.1.0 with no further releases planned; the recommended direction is DataStore plus Tink, with keys protected by the Android Keystore ([Include Security, Aug 2026](https://blog.includesecurity.com/2026/08/encryptedsharedpreferences-is-dead-heres-what-you-should-use-instead/)). ProxyFlow does not use it (ADR-0006).

## 8. Local-proxy exposure seen in the ecosystem

Detection apps now look for the local proxy ports that many VPN/proxy clients open. RKNHardering on F-Droid, built on the Russian RKN methodology, has a "Bypass Check" that "scans localhost for open SOCKS5/HTTP proxies … and detects underlying network leaks", and a separate check that determines the apparent public IP ([source](https://f-droid.org/en/packages/com.notcvnt.rknhardering/)). Our own reasoning, not a claim from that source: any app on the phone can also connect to an unauthenticated local proxy port, send traffic through it and learn the exit IP, using the user's paid proxy. ProxyFlow opens **no** local listener (ADR-0002).

## 9. Branding and listing

- Do not name or imitate Super Proxy (or any other app) in the name, icon, screenshots or listing text. "Super Proxy" is mentioned only in internal planning documents as category context.
- Trademark search for "ProxyFlow" and the icon before M11.
- Listing must not claim "all traffic", "anonymous" or "encrypted" without the TLS qualifier (traffic matrix §5).
