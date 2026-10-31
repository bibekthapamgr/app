# ADR-0006 — Credential storage with Android Keystore keys, a new key for every set of credentials

**Status:** Accepted 2026-10-31 (decision D4, delegated by the owner) · **Date:** 2026-10-30 · **Revised:** 2026-10-31 in M3 — a new Keystore key for every set of credentials instead of one app-wide key, the stored format, and the rules for keys that can't be used

## Context

Profiles hold proxy usernames and passwords (paid accounts). The brief requires Android-Keystore-backed encryption, no credentials in logs, and (M3) that deleting a profile removes its credential material. Jetpack Security's `security-crypto` (EncryptedSharedPreferences) is deprecated. Reconnects happen in the background, including while the phone is locked, so the key cannot require user authentication.

Deleting or overwriting a database row does not guarantee its old bytes are gone: SQLite free pages, the write-ahead log and flash wear-levelling can keep old ciphertext on the device. Whatever key sealed it must therefore be deleted when the credentials it protects are deleted or replaced. A key can also stop working (Android invalidates it, or its stored blob is damaged) while it still exists under its alias.

## Decision

1. **A new AES-256-GCM key for every set of credentials**, generated inside the Android Keystore under the alias `proxyflow_credentials_<keyId>` (`keyId` a random UUID): purposes encrypt/decrypt, GCM, no padding, randomized encryption required (the Keystore chooses every IV), `setUserAuthenticationRequired(false)`; StrongBox not requested (availability and latency vary). Key material never enters the app process and cannot be exported. The row stores which key it uses (`credentials_key`, database version 2, ADR-0009). Database version 1 had one key per profile, named after the profile; its rows keep that key (key id = profile id) until their credentials next change.
2. **Username and password are encrypted together** (both identify a paid account): a version byte, then each field as a 2-byte length and its UTF-8 bytes (`CredentialCodec`). Stored in the Room row as `credentials_ct` (ciphertext with the 16-byte GCM tag), `credentials_iv` (12 bytes), `credentials_format` (1) and `credentials_key`. The **associated data** `proxyflow/credentials/v1/<profileId>` binds each ciphertext to its profile and format: the same key and bytes presented as another profile's, or any changed byte, fail authentication instead of producing wrong credentials.
3. **Lifecycle** (`DefaultProfileRepository`; writes are serialized):
   - Save with sign-in and a typed password → sealed under a new key, the row written, then the previous key deleted: old ciphertext, wherever it lingers, can never be decrypted again. If writing the row fails, the new key is deleted instead and nothing changes.
   - Save with the password left empty → the saved ciphertext and key are kept only while protocol, server, port, username and TLS settings are unchanged; otherwise the password must be typed again, so a password is never sent to a proxy it was not saved for.
   - Sign-in turned off → the ciphertext is removed and its key deleted.
   - Delete → the row, then its key.
   - Duplicate → decrypted and sealed again under the copy's own new key; no key or ciphertext is ever shared between profiles.
   - **Sweep at start:** keys that no row uses (left when the app stopped between writing a row and deleting the old key, or when Android could not delete a key) are deleted.
4. **Plaintext handling.** Decrypted values are `CharArray`/`ByteArray` and are zeroed right after use. The edit form receives the decrypted username (as text for its field), never the password. Typed credentials live in ViewModel memory only, never in saved instance state. Decryption and key operations run on an IO dispatcher, never on the main thread. From M4, the engine receives the secret once at connect time; it keeps it as a Go `[]byte` (never a `string`), never logs or writes it, and zeroes it in `Stop()` (`docs/m1/06-architecture.md` §5; residual risk in threat I-12).
5. **Keys that can't be used, and recovery — no plaintext fallback.**
   - *Unreadable* (permanent): the key is missing (data restored onto another phone, Keystore reset, app data partly cleared), `UnrecoverableKeyException`, `KeyPermanentlyInvalidatedException` or any other `InvalidKeyException` when the cipher starts, GCM authentication failure (changed or moved ciphertext), an unknown format version, a wrong IV length, malformed plaintext. Android reports most Keystore failures at cipher start as `InvalidKeyException`; treating one that might have passed as permanent costs the user one re-entry, nothing more.
   - *Temporarily unavailable*: `ProviderException` or another `GeneralSecurityException` from the Keystore is tried once more; if it fails again, nothing is decided and nothing is changed. Reading for connecting reports it as temporary (not H3); the edit form says the profile can't be opened right now; saving with the password left empty, and Duplicate, fail with a message instead of guessing (a save that types a new password does not need the old one).
   - Unreadable credentials are never treated as empty or as "no sign-in": the profile keeps sign-in on, the edit form explains the problem (board H3) and asks for the username and password again, and saving seals them under a new key and deletes the unusable one. Connecting (M4/M5) reports `CREDENTIAL_UNREADABLE` and does not connect. Nothing is deleted automatically except keys no row uses.
6. **Backups** stay off for all app data (`allowBackup="false"`; data-extraction rules exclude everything from cloud backup and device transfer). Keystore keys are never backed up either, so even a restored database could only ever give unreadable credentials.
7. **Never exposed:** credentials are never logged, never put in notifications, analytics or "Copy diagnostics"; `toString()` of the form input and of decrypted credentials is redacted; the password field offers no Copy or Cut (Compose), and the username field's Copy and Cut never reach the clipboard (Cut still removes the selection); Paste works in both; the Add/Edit screen uses `FLAG_SECURE`.

## Alternatives considered

| Option | Assessment |
|---|---|
| **A new Keystore AES-GCM key for every set of credentials (chosen)** | Replacing or deleting credentials deletes the only key that can read them, so leftovers are unreadable; a broken key is never reused; ~200 lines, no third-party crypto dependency |
| One key per profile, kept for the profile's life | Deleting a profile shreds its ciphertext, but after a password change the old ciphertext stays readable, and a key Android invalidated would be reused under the same alias. Tried first in M3; replaced after the independent review |
| One app-wide Keystore key (M1 design) | A deleted profile's leftover ciphertext stays decryptable |
| Tink AEAD with `AndroidKeysetManager` | Misuse-resistant API and key rotation built in; adds a dependency, and shredding per set of credentials would need one keyset each |
| EncryptedSharedPreferences | Deprecated; rejected |
| Plaintext, relying on the app sandbox | Violates the brief; rejected |
| Key requiring user authentication or biometrics | Breaks background reconnect and Always-on start; rejected for V1 |

## Consequences

- One Keystore entry per profile that signs in (users keep a handful to a few dozen profiles); a password change briefly holds two keys.
- Credentials cannot be recovered after a restore onto another phone or a Keystore reset; the user types them again (H3), by design.
- Keystore behaviour varies between manufacturers. M3 tests the real Android Keystore on emulators in CI (Android 10 / API 29, the minimum, and Android 15 / API 35). Tests on physical phones from several manufacturers belong to M10 (QA and compatibility).

## Evidence

M3 (`docs/m3/M3-acceptance-report.md` lists the CI runs):

- JVM: `CredentialCipherTest` (round trip; the same key and bytes refused as another profile's, so the associated data is what binds them; a new key and IV for every seal; tampering detected; deleted, rejected and unrecoverable keys are permanent; a failed seal leaves no key), `CredentialCodecTest`, `ProfileRepositoryTest` (only ciphertext stored; a changed password leaves the old ciphertext unreadable; a rejected key is replaced when the sign-in is typed again; a failed write leaves no new key; temporary Keystore failures never copy or drop credentials; deleting removes the key; sign-in off; duplicate under its own key; the sweep).
- Robolectric with the real Room database: `StoredProfilesTest` (profiles and credentials survive a restart; neither a full export of the database nor its files — while open, with the write-ahead log, and after closing — contain the username or password in UTF-8 or UTF-16, while the server is found), `DatabaseMigrationTest` (version-1 credentials still open after the migration to version 2).
- Emulators with the Android Keystore (`device` job): `KeystoreOnDeviceTest` (key in the Keystore and not exportable; the same bytes refused as another profile's; credentials readable after a restart of the database and Keystore objects; no file in the app's data directory holds them in clear, while the server is found; a key the Keystore refuses for AES-GCM is replaced when the sign-in is typed again; deleting the profile deletes its key).
