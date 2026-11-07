# ProxyFlow — Release notes

The notes for each release: the "What's new" text Play shows (≤ 500 characters; the fenced `listing-notes` block of
`docs/m11/store-listing.md` §7 is the one the claims checker reads, and this file keeps the history), and what changed
for the project. versionCode goes up by one per upload to Play, whether or not versionName changes.

## 1.0.0 (11) — first release

**What's new (Play):** see `docs/m11/store-listing.md` §7.

**What it is:** the M1–M10 product — HTTP CONNECT and SOCKS5 proxies with sign-in and optional TLS to the proxy;
apps' TCP connections and site names through the proxy; UDP and ping blocked and never sent around it; profiles with
Android-Keystore-encrypted passwords; a connection test; Home with status, statistics and diagnostics; a notification
and a Quick Settings tile; a local diagnostic log; reconnect; Android's kill switch explained; About, help, privacy
policy and open-source notices — on Android 10 to 16 (API 29–36), phone-first, English only.

**Known limitations (the listing and the app say them):** TCP only; UDP, QUIC, ICMP blocked; IPv6 only through a proxy
that supports it; some of Android's own traffic, work profiles, other users and tethering outside the VPN; the engine's
memory with many connections and data in flight past the 50 MB bar (M10-63, accepted for V1); the first translations
and the licence (D8) after V1.

**Signing:** the upload key of `docs/m11/signing.md`; Play App Signing with a Google-generated app signing key.

**Evidence:** `docs/m11/M11-acceptance-report.md`; M10's QA report, performance baseline and compatibility matrix.
