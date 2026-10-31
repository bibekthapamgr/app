# ADR-0006 — Credential storage with per-profile Android Keystore keys

**Status:** Accepted 2026-10-31 (decision D4, delegated by the owner) · **Date:** 2026-10-30 · **Revised:** 2026-10-31 in M3 — one key per profile instead of one app-wide key, the stored format, and the rules for invalidated keys

## Context

Profiles hold proxy usernames and passwords (paid accounts). The brief requires Android-Keystore-backed encryption, no credentials in logs, and (M3) that deleting a profile removes its credential material. Jetpack Security's `security-crypto` (EncryptedSharedPreferences) is deprecated. Reconnects happen in the background, including while the phone is locked, so the key cannot require user authentication.

Deleting a database row does not guarantee its bytes are gone: SQLite free pages, the write-ahead log and flash wear-levelling can keep old ciphertext on the device. With one app-wide key (the M1 design), such leftovers stay decryptable for as long as the app is installed.

## Decision

1. **One AES-256-GCM key per profile**, generated inside the Android Keystore under the alias `proxyflow_credentials_<profileId>`: purposes encrypt/decrypt, GCM, no padding, randomized encryption required (the Keystore chooses every IV), `setUserAuthenticationRequired(false)`; StrongBox not requested (availability and latency vary). Key material never enters the app process and cannot be exported. Deleting a profile deletes its key, which makes every copy of that profile's ciphertext permanently undecryptable, wherever old bytes remain (crypto-shredding).
2. **Username and password are encrypted together** (both identify a paid account): a version byte, then each field as a 2-byte length and its UTF-8 bytes (`CredentialCodec`). Stored in the Room row (ADR-0009) as `credentials_ct` (ciphertext with the 16-byte GCM tag), `credentials_iv` (12 bytes) and `credentials_format` (1). The **associated data** `proxyflow/credentials/v1/<profileId>` binds each ciphertext to its profile and format: a ciphertext copied to another row, or any changed byte, fails authentication instead of producing wrong credentials.
3. **Lifecycle** (`DefaultProfileRepository`; writes are serialized):
   - Save with sign-in and a typed password → sealed under the profile's key (created on first use), fresh IV every time.
   - Save with the password left empty → the saved ciphertext is kept only while protocol, server, port, username and TLS settings are unchanged; otherwise the password must be typed again, so a password is never sent to a proxy it was not saved for.
   - Sign-in turned off → the ciphertext is removed and the key deleted.
   - Delete → the row, then the key. If the app stops in between, the **orphan-key sweep** at the next start deletes keys whose profile no longer exists.
   - Duplicate → decrypted and sealed again under the copy's own key; no ciphertext is ever shared between profiles.
4. **Plaintext handling.** Decrypted values are `CharArray`/`ByteArray` and are zeroed right after use. The edit form receives the decrypted username, never the password. Typed credentials live in ViewModel memory only, never in saved instance state. Decryption and key operations run on an IO dispatcher, never on the main thread. From M4, the engine receives the secret once at connect time; it keeps it as a Go `[]byte` (never a `string`), never logs or writes it, and zeroes it in `Stop()` (`docs/m1/06-architecture.md` §5; residual risk in threat I-12).
5. **Invalidated keys and recovery — no plaintext fallback.**
   - *Permanent* failures make the credentials **unreadable**: no key for the profile (data restored to another phone, Keystore reset, app data partly cleared), `KeyPermanentlyInvalidatedException` or other `InvalidKeyException`, `UnrecoverableKeyException`, GCM authentication failure (tampered or moved ciphertext), an unknown format version, a wrong IV length, malformed plaintext.
   - *Possibly temporary* failures (`ProviderException` or another `GeneralSecurityException` from the Keystore) are **tried once more**; if the second attempt fails too, that read is unreadable and nothing is deleted.
   - Unreadable credentials are never treated as empty or as "no sign-in": the profile keeps sign-in on, the edit form explains the problem (board H3) and asks for the username and password again, and saving seals them under a new key. Connecting (M4/M5) reports `CREDENTIAL_UNREADABLE` and does not connect. Nothing is deleted automatically.
6. **Backups** stay off for all app data (`allowBackup="false"`; data-extraction rules exclude everything from cloud backup and device transfer). Keystore keys are never backed up either, so even a restored database could only ever give unreadable credentials.
7. **Never exposed:** credentials are never logged, never put in notifications, analytics or "Copy diagnostics"; `toString()` of the form input and of decrypted credentials is redacted; credential fields put nothing on the clipboard (Copy and Cut do nothing there; Paste works); the Add/Edit screen uses `FLAG_SECURE`.

## Alternatives considered

| Option | Assessment |
|---|---|
| **One Keystore AES-GCM key per profile (chosen)** | Deleting a profile shreds every copy of its ciphertext; ~150 lines, no third-party crypto dependency, easy to review and test |
| One app-wide Keystore key (M1 design) | One alias and no orphan keys, but a deleted profile's leftover ciphertext stays decryptable; replaced in M3 |
| Tink AEAD with `AndroidKeysetManager` | Misuse-resistant API and key rotation built in; adds a dependency, and per-profile shredding would need one keyset per profile |
| EncryptedSharedPreferences | Deprecated; rejected |
| Plaintext, relying on the app sandbox | Violates the brief; rejected |
| Key requiring user authentication or biometrics | Breaks background reconnect and Always-on start; rejected for V1 |

## Consequences

- One Keystore entry per profile that signs in (users keep a handful to a few dozen profiles).
- Credentials cannot be recovered after a restore to another phone or a Keystore reset; the user types them again (H3), by design.
- Keystore behaviour varies between manufacturers. M3 tests the real Android Keystore on emulators in CI (Android 10 / API 29, the minimum, and Android 15 / API 35). Tests on physical phones from several manufacturers belong to M10 (QA and compatibility).

## Evidence

M3 (`docs/m3/M3-acceptance-report.md` lists the CI runs):

- JVM: `CredentialCipherTest` (round trip, associated data binds the profile, tampering detected, unknown format, lost key is permanent), `CredentialCodecTest`, `ProfileRepositoryTest` (only ciphertext stored, keep-password rule, deleting removes the key, sign-in off, duplicate under its own key, lost keys read as unreadable never as empty, one retry, orphan sweep).
- Robolectric with the real Room database: `StoredProfilesTest` (profiles and credentials survive a restart; neither a full export of the database nor its files contain the username or password in UTF-8 or UTF-16; a deleted profile leaves nothing decryptable).
- Emulators with the Android Keystore (`device` job): `KeystoreOnDeviceTest` (key in the Keystore and not exportable, credentials readable after a restart, no file in the app's data directory holds them in clear, deleting the profile deletes its key).
