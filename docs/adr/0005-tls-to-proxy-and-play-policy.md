# ADR-0005 — TLS to proxy, trust anchors and the Play encryption requirement

**Status:** Proposed (owner decisions D2, D7) · **Date:** 2026-10-30

## Context

1. Plain SOCKS5 and HTTP CONNECT send the proxy password, destination names and any non-HTTPS payload in clear text (E05 shows all three in the uplink capture).
2. Google Play's VpnService policy says apps "must encrypt the data from the device to the VPN tunnel endpoint". For ProxyFlow the tunnel endpoint is the user's proxy.
3. Some providers and most self-hosted setups can offer TLS on the proxy port (e.g. stunnel in front of a SOCKS5/HTTP proxy, or HTTPS proxies).
4. Go's own root-certificate loading on Android (checked against Go 1.27.3 sources) reads the legacy system directory and the **user-installed** CA directory, and does not read the updatable Conscrypt CA store. Android apps targeting API 24+ do not trust user-installed CAs by default.

## Decision

1. **TLS-to-proxy is a first-class, per-profile option** for both SOCKS5 and HTTP CONNECT: TLS 1.2 minimum, SNI = proxy host (override allowed), full chain validation and hostname verification, no "trust anyway" button, no fallback to plaintext.
2. **Trust decisions are made by Android**, not Go: the engine calls back into Kotlin with the presented chain and host; Kotlin validates with the platform default `X509TrustManager` (system CAs only, honouring platform distrust rules and network security config) and returns a result code (`TLS_UNTRUSTED`, `TLS_HOSTNAME`, `TLS_EXPIRED`). Go's root loading is not used in production builds.
3. **Plaintext profiles:** the product carries an inline warning (D4 board) and a build flag `requireTlsToProxy`. Whether the **Play build** sets it is the owner's decision **D2**: (a) allow plaintext with the warning; (b) require TLS in the Play build and ship plaintext support only in a non-Play build; (c) ask Play policy support first, then choose. Recommendation: (c), with the declaration describing the TLS option and the user-supplied endpoint.
4. Self-signed or private-CA proxies are not supported in V1 (B6c says so); V1.1 candidate: per-profile certificate-fingerprint pinning shown and confirmed by the user.

## Alternatives considered

- **Use Go's `crypto/x509` roots:** wrong trust set on Android (includes user CAs, misses updatable roots). Rejected.
- **Export system anchors from `AndroidCAStore` to Go at start:** simpler than a callback and avoids per-handshake JNI, but loses platform-specific distrust logic and future CT enforcement. Acceptable fallback if the callback proves too slow (it runs once per proxy connection, not per app flow).
- **Always require TLS:** most purchased residential/ISP proxies offer no TLS endpoint, so the app would be unusable for its main audience.

## Consequences

- TLS profiles hide credentials, destinations and payload from the local network; only the proxy's SNI is visible (E04 valid cases).
- Play approval risk for plaintext profiles remains until D2 is settled; both builds stay possible.
- The engine API carries a `VerifyChain` callback (`06-architecture.md` §5).

## Evidence

E04 (six cases: valid SOCKS5+TLS, valid HTTP+TLS with auth, expired, hostname mismatch, untrusted issuer, TLS to a plaintext port), E05 (plaintext exposure), unit `TestTLSToProxyValidation`, `TestTLSToPlaintextProxy`. On-device validation with the platform trust manager in M6.
