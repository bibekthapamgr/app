# M11 — Google Play store listing

The listing's text and assets, as entered in Play Console (Grow → Store presence → Main store listing), and what
checks them: `scripts/check_listing_claims.py` reads the fenced blocks below (`listing-title`, `listing-short`,
`listing-full`, `listing-notes`) and the screenshot captions in §4, and fails on any claim the app itself may not
make (the rules of `CopyClaimsTest`: coverage of all traffic or every app, anonymity, hiding identity, blanket
security, encrypting everything, protection, "no logs", games and calls, guarantees), on Play's metadata rules it can
check (the title's length, the short description's, the full description's, "best", "#1", "free", "top", emoji, words
in capitals), and on anything left to fill in (`‹…›`). It also requires the limitations to be stated: TCP only, UDP
blocked, what the proxy provider sees, no anonymity, and the use of Android's VPN feature (Play's VpnService policy
asks the listing to document it). Brief task 2 (name, package, icon, screenshots, descriptions, support contact) and
task 9 (no promise of unsupported UDP, complete anonymity or guaranteed coverage).

## 1. App details

| Field | Value | Where it comes from |
|---|---|---|
| App name (≤ 30 characters) | `ProxyFlow` | The brief's working title, kept: the searches of 2026-11-07 (§6) found no Android app or Play listing of that name; the trademark registers are the owner's to search; the owner confirms before creation (decision D10). |
| Package name | `app.proxyflow.android` | `gradle.properties` (`proxyflow.applicationId`); final once the app is created in Play Console, since Play never lets it change. |
| Default language | English (United States) | The app's only language in V1 (translations: decision D8). |
| Category | Tools | A proxy client is a tool; not "Communication". |
| Tags | Proxy, VPN, Privacy | Play's own tag list. "VPN" because the app uses Android's VPN feature and users search for it; the description says it is not a VPN service. |
| Contact e-mail | `‹the owner's support address — decision D10›` | Shown publicly on the listing; the privacy policy points to it. Never the owner's personal address unless the owner chooses it. |
| Website | `https://bibekthapamgr.github.io/app/` (GitHub Pages, decision D9) | Where the privacy policy is published (`docs/m11/declarations.md` §3). |
| Privacy policy URL | `https://bibekthapamgr.github.io/app/privacy.html` | The same page as the app's About & privacy → Privacy policy, generated from it. |
| Price | Free | No in-app purchases, no ads. |
| Content rating | IARC questionnaire: a utility; no user-generated content, no violence, no purchases | `docs/m11/declarations.md` §2. |

```listing-title
ProxyFlow
```

## 2. Short description (≤ 80 characters)

```listing-short
Your apps' TCP connections, through a proxy you already have.
```

## 3. Full description (≤ 4,000 characters)

```listing-full
ProxyFlow sends your apps' TCP connections through an HTTP (CONNECT) or SOCKS5 proxy you already have — one from a provider you pay, or a server of your own. It is free, has no account and no servers of its own, and does not sell or provide proxies.

HOW IT WORKS
ProxyFlow uses Android's VPN feature to receive your apps' traffic on this phone and hand it to your proxy. There is no VPN server: the connection goes from your phone to your proxy, and your proxy connects to the sites. While connected, Android shows its key icon, and only one VPN app can be on at a time.

WHAT GOES THROUGH YOUR PROXY
• Websites and most apps (TCP), including HTTPS, which stays encrypted end to end — ProxyFlow never decrypts it.
• Site names (DNS), looked up by your proxy rather than by your phone.
• IPv6 sites, when your proxy supports IPv6.

WHAT IS BLOCKED, NOT SENT AROUND YOUR PROXY
• UDP: calls, some games and QUIC fall back to TCP or do not work while connected. Ping is not answered.
• Printers and other devices on your local network are reached through your proxy, so usually not at all.
Blocked traffic is never sent around your proxy. If your proxy cannot be reached, apps stay offline until ProxyFlow reconnects or you disconnect.

WHAT STAYS OUTSIDE
Some of Android's own traffic (such as Google Play services), apps in a work profile or Private space, other users of the phone and tethered devices do not use the VPN. ProxyFlow's own connection to your proxy, and the lookup of your proxy's name, go over your normal network.

PROFILES AND SIGN-IN
Save each proxy you use as a profile: server, port, HTTP or SOCKS5, and a username and password if it needs one. Passwords are encrypted on this phone with a key in Android's Keystore and are only ever sent to the proxy they were saved for. Optional TLS to the proxy, where your provider offers it, encrypts the connection between your phone and the proxy; ProxyFlow checks the proxy's certificate and name. Test a profile before you use it.

STATUS, LOGS AND STATISTICS
The Home screen shows whether traffic is going through your proxy, what was sent and received, and what is blocked. A notification (optional) shows the status and lets you disconnect in one tap. A diagnostic log of connection events is kept on this phone only, for the time you choose, and never includes passwords, server or site names or page content.

WHAT PROXYFLOW IS NOT
It does not make you anonymous: your proxy provider can see which sites you open and any data that is not encrypted, and sites can still recognise you, for example when you sign in. Choose a provider you trust. It is not a VPN service and does not hide your IP address from your proxy.

PRIVACY
No account, ads or analytics. ProxyFlow sends nothing to its developer. Each time it connects, it asks your proxy to open one connection to connectivitycheck.gstatic.com (a Google service) to check the proxy works, and closes it without sending anything through it. The full privacy policy is in the app and at the link below.

REQUIREMENTS
Android 10 or later. A proxy of your own: ProxyFlow does not include one.
```

## 4. Screenshots (phone, 9:16, 1080×1920, made by `scripts/release/store_assets.py` from the app's committed screenshots)

| # | Source (`app/screenshots/`) | Caption |
|---|---|---|
| 1 | `B4_home_connected_light` | Your apps' TCP connections, through your own proxy |
| 2 | `D1_add_proxy_light` | HTTP CONNECT or SOCKS5, with a username and password if needed |
| 3 | `A2_onboarding_how_it_works_light` | What goes through, what is blocked — said before you connect |
| 4 | `B2_home_disconnected_light` | Status at a glance, connect in one tap |
| 5 | `C2_profiles_list_light` | A profile for each proxy you use |
| 6 | `F2_logs_light` | A diagnostic log kept on this phone only |
| 7 | `B4_home_connected_dark` | Dark theme |
| 8 | `G2_privacy_policy_light` | The privacy policy, in the app |

Each is the committed screenshot with Android's status bar cropped away (Play's rule on screenshots: no notification bar
content), on a plain background under its caption; nothing is added to the screen itself, so what the pictures show is
what the app shows. No device frame with a maker's branding; no "Free", "Best" or award wording (Play's metadata policy).

## 5. Assets

| Asset | File | Specification (Play, read 2026-11-06) |
|---|---|---|
| App icon | `store/icon-512.png` | 512×512, 32-bit PNG with alpha, ≤ 1024 KB; Play applies its own rounding. The app's adaptive icon (the mark on brand teal), drawn from the same geometry. |
| Feature graphic | `store/feature-graphic-1024x500.png` | 1024×500, 24-bit PNG without alpha (or JPEG). The mark, the name and the short description on brand teal. |
| Phone screenshots | `store/screenshots/1-…png` to `8-…png` | 2 to 8; PNG or JPEG; sides 320–3840 px; the longer at most twice the shorter; 9:16 for recommendation placements. |
| Tablet screenshots | none in V1 | The app is phone-first (M1); Play then shows the phone screenshots on tablets and may limit tablet promotion. |
| Promo video | none | Optional. |

`scripts/release/store_assets.py check` verifies every committed asset against the row above; CI runs it.

## 6. The name: original, and searched

"ProxyFlow" is the brief's working title, and the app, its mark and its text are original work of this project. On
2026-11-07 web searches for "ProxyFlow" (with "Android", "app", "proxy", "VPN", "Google Play") found no Android app,
Play listing or product of that name for a proxy or VPN client. The nearest name found is **ProxFlow**, a Chrome
extension (a browser proxy switcher, in the Chrome Web Store) — a different platform and store, but a name one letter
away, which the owner should weigh. The trademark registers (USPTO, EUIPO, the owner's own country's) were not searched
from here: their search pages cannot be queried from this workspace, so that search — or a professional one — is the
owner's before the listing goes public (decision D10). This is a search, not legal advice. Whether the package name
`app.proxyflow.android` is unused on Play shows at app creation: Play refuses a package name already in use.

## 7. Release notes (≤ 500 characters per language, Play's "What's new")

```listing-notes
First release.
• HTTP CONNECT and SOCKS5 proxies, with sign-in and optional TLS to the proxy.
• Apps' TCP connections and site names go through your proxy; UDP and ping are blocked, never sent around it.
• Profiles with passwords encrypted on this phone; a connection test; status, statistics and a local diagnostic log.
• No account, no ads, no analytics, no servers of its own.
```

## 8. What is left to the owner (decision D10)

The contact e-mail, the confirmation of the name, and — after the first upload — the listing's final review in Play
Console's preview on a phone, where line breaks and the bullet characters render as Play shows them.
