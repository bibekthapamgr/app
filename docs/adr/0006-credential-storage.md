# ADR-0006 — Credential storage with an Android Keystore key

**Status:** Proposed (owner/tech-lead decision D4) · **Date:** 2026-10-30

## Context

Profiles hold proxy usernames and passwords (paid accounts). The brief requires Android-Keystore-backed encryption and no credentials in logs. Jetpack Security's `security-crypto` (EncryptedSharedPreferences) is deprecated (all APIs in 1.1.0). Reconnects happen in the background, including while the phone is locked, so the key cannot require user authentication.

## Decision

1. One **AES-256-GCM** key generated in the **Android Keystore** (`KeyGenParameterSpec`: purposes encrypt/decrypt, GCM, no padding, `setUserAuthenticationRequired(false)`, randomized encryption required; StrongBox not requested — latency and availability vary).
2. Username and password are encrypted **together** (both identify a paid account) and stored in Room as one ciphertext (`credentials_ct`) + 12-byte IV + key version, with the **profile id as associated data**, so ciphertext cannot be moved between profiles. The username is decrypted for display on the edit screen.
3. Decryption for connecting happens only in the VPN layer at start. The secret crosses into the engine once as bytes and Kotlin zeroes its `CharArray`/`ByteArray` copy right after `Start()`. Because every new app connection signs in to the proxy, the engine has to keep the secret for the connected session: as a Go `[]byte` (never a `string`, which cannot be wiped), never logged or written to disk, zeroed in `Stop()` (`06-architecture.md` §5; residual risk in threat I-12).
4. **No plaintext fallback.** If the key is lost or invalidated (restore to another phone, Keystore reset), decryption fails → `CREDENTIAL_UNREADABLE` → the user re-enters the password (H3).
5. Backups disabled for all app data (`allowBackup=false`, data-extraction rules excluding everything).
6. Credentials are never logged, never put in notifications, never included in "Copy diagnostics", and the Add/Edit screen uses `FLAG_SECURE`.

## Alternatives considered

| Option | Assessment |
|---|---|
| **Keystore AES-GCM wrapper (chosen)** | ~100 lines, no third-party crypto dependency, easy to review and test |
| Tink AEAD with `AndroidKeysetManager` + DataStore | Google's currently recommended direction; misuse-resistant API and key rotation built in; adds a dependency. Acceptable alternative if the team prefers a library (D4) |
| EncryptedSharedPreferences | Deprecated; rejected |
| Store plaintext, rely on app sandbox | Violates the brief; rejected |
| Key requiring user authentication / biometrics | Breaks background reconnect and Always-on start; rejected for V1 |

## Consequences

- Credentials cannot be recovered after a device restore — by design; UX covered by H3.
- Keystore failures on some OEM devices must be handled gracefully (retry once, then H3); M3 adds instrumentation tests on at least three OEMs.

## Evidence

Design only in M1; M3 deliverables: unit tests for AAD binding and tamper detection, instrumentation test that the database contains no plaintext secret, manual backup/restore check.
