# Changelog

Every release of ProxyFlow, newest first, in the form of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/):
what users see under *Added*, *Changed*, *Fixed*, *Security* and *Removed*, with the version name and code as Google
Play shows them and the date the release reached production. The Play "What's new" text is the first lines of each
section (`docs/m11/store-listing.md` §7 for 1.0.0); the regression results of each release are linked from
`docs/m12/release-history.md`. `scripts/release/check_release_ready.py` refuses a release whose version has no section
here, or whose section still says "Unreleased".

## [Unreleased]

## [1.0.0 (11)] — not yet on Google Play

The first release: closed testing on Google Play, then production (`docs/m11/release-checklist.md`).

### Added
- Help → Feedback says never to send a proxy password or username, and that the exported log already leaves out
  passwords, server names and site names (M12).
- HTTP CONNECT and SOCKS5 proxies, with a username and password and optional TLS to the proxy (the proxy's certificate
  and name checked).
- Apps' TCP connections and site names go through the proxy; UDP, QUIC and ping are blocked and never sent around it;
  IPv6 through a proxy that has it; the connection to the proxy itself and some of Android's own traffic stay outside.
- Profiles with passwords encrypted on the phone (Android Keystore), a connection test, several profiles and switching.
- Home with the status, the session's time and traffic, what is blocked; a notification and a Quick Settings tile.
- Reconnecting on its own when the proxy or the network goes away, with traffic held meanwhile; Android's kill switch
  explained, its state shown as Android reports it.
- A diagnostic log on the phone (24 hours, 5,000 entries, never a password, server or site name), with filters, search
  and a sanitized export.
- Onboarding that says what goes through the proxy and what does not before the first connect; About, help, the
  privacy policy and the open-source notices in the app.

### Security
- Credentials never logged, never in a crash report; TLS to the proxy verified by Android's trust manager; no plaintext
  fallback; taps through other apps' windows refused on Disconnect; every library checked against OSV at build time.

[Unreleased]: https://github.com/bibekthapamgr/app/compare/v1.0.0...HEAD
[1.0.0 (11)]: https://github.com/bibekthapamgr/app/releases/tag/v1.0.0
