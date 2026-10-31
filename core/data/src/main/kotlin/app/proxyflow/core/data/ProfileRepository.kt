package app.proxyflow.core.data

import app.proxyflow.core.data.crypto.CredentialCipher
import app.proxyflow.core.data.crypto.CredentialCodec
import app.proxyflow.core.data.crypto.CredentialUnreadableException
import app.proxyflow.core.data.crypto.SealedCredentials
import app.proxyflow.core.model.CredentialState
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProfileNames
import app.proxyflow.core.model.ProfileValidator
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Saved proxy profiles, their encrypted credentials, and which profile is active. */
interface ProfileRepository {
    /** Every profile, oldest first. */
    val profiles: Flow<List<ProxyProfile>>

    /**
     * The profile the screens show as active: the one the user chose, or the
     * oldest one when there is no choice; null when there are no profiles.
     */
    val activeProfileId: Flow<String?>

    /**
     * Only the profile the user explicitly chose ("Use this proxy"), while it
     * exists; never a fallback. A connection started without the user present
     * (Always-on VPN, M5) uses this, so it never goes through a proxy the user
     * did not pick.
     */
    val chosenProfileId: Flow<String?>

    suspend fun get(id: String): ProxyProfile?

    /**
     * [id]'s settings and decrypted username for the edit form, never its password; null when it is gone.
     * @throws CredentialsUnavailableException when the Keystore failed twice in a row; nothing changed.
     */
    suspend fun loadForEditing(id: String): ProfileForEditing?

    /**
     * Validates [input] and saves it under [id], or as a new profile when [id] is
     * null or no longer exists. A non-empty password replaces the saved one and is
     * sealed under a new key; the old key is then deleted, so the old credentials
     * can never be decrypted again. An empty password keeps the saved one only
     * while protocol, server, port, username and TLS settings are unchanged
     * ([ProxyProfileInput.sameCredentialTargetAs]), so a password is never sent
     * to a proxy it was not saved for. Turning sign-in off deletes the key.
     *
     * @throws InvalidProfileException when [input] does not pass [ProfileValidator]; nothing is saved.
     * @throws CredentialsUnavailableException when the password was left empty and the Keystore
     *   failed twice in a row, so whether the saved one may be kept can't be decided; nothing is saved.
     */
    suspend fun save(id: String?, input: ProxyProfileInput): ProxyProfile

    /**
     * Copies [id] (settings, username and password) as a new profile named [name],
     * its credentials sealed under a key of its own; null when [id] is gone.
     * Credentials that can't be read are not copied: the copy asks for them, like the original.
     *
     * @throws InvalidProfileException when [name] is not a valid profile name.
     * @throws CredentialsUnavailableException when the Keystore failed twice in a row; nothing is saved.
     */
    suspend fun duplicate(id: String, name: String): ProxyProfile?

    /** Deletes the profile, its encrypted credentials and their Keystore key. */
    suspend fun delete(id: String)

    suspend fun setActive(id: String)

    /** The username and password for connecting (from M4). The caller wipes them as soon as they are sent. */
    suspend fun readCredentials(id: String): CredentialsRead

    /**
     * Like [readCredentials], for a connection that started with [expected]:
     * [CredentialsRead.ProfileChanged] when the stored profile no longer reaches the same
     * proxy the same way ([ProxyProfile.sameServerAs]), so credentials saved for another
     * server are never sent to this one. The profile and its credentials are read from the
     * same stored record, so a save in between can't mix one with the other.
     */
    suspend fun readCredentials(id: String, expected: ProxyProfile): CredentialsRead
}

data class ProfileForEditing(val profile: ProxyProfile, val input: ProxyProfileInput, val credentials: CredentialState)

/** The outcome of reading a profile's credentials for connecting. */
sealed interface CredentialsRead {
    data object NoSuchProfile : CredentialsRead

    data object NotRequired : CredentialsRead

    /** They can't be decrypted (ADR-0006); connecting reports `CREDENTIAL_UNREADABLE` (board H3). */
    data object Unreadable : CredentialsRead

    /** The Keystore failed twice in a row; the credentials may well be fine. Try again later. */
    data object TemporarilyUnavailable : CredentialsRead

    /** The profile's server, port, protocol, TLS or sign-in setting changed since the connection read it; nothing was decrypted. */
    data object ProfileChanged : CredentialsRead

    class Available(val username: CharArray, val password: CharArray) : CredentialsRead {
        fun wipe() {
            username.fill('\u0000')
            password.fill('\u0000')
        }

        override fun toString() = "Available(<redacted>)"
    }
}

/** [ProfileRepository.save] was given a form that does not pass [ProfileValidator]. */
class InvalidProfileException(val errors: Map<ProfileField, ProfileError>) : IllegalArgumentException("invalid profile: ${errors.keys}")

/** The Android Keystore failed twice in a row, so the saved credentials could not be read just now. Nothing was changed. */
class CredentialsUnavailableException : IllegalStateException("the Keystore is not available right now")

/**
 * Profiles in a [ProfileStore] (Room in the app), credentials encrypted with
 * [CredentialCipher] (a new Android Keystore key for every set of credentials,
 * ADR-0006), the chosen profile in [ActiveProfileStore] (DataStore). Writes are
 * serialized, and every decryption and key operation runs on [cryptoDispatcher],
 * never on the main thread.
 */
class DefaultProfileRepository(
    private val store: ProfileStore,
    private val cipher: CredentialCipher,
    private val active: ActiveProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfileRepository {
    private val writes = Mutex()

    override val profiles: Flow<List<ProxyProfile>> = store.observeAll().map { records -> records.map { it.toProfile() } }

    override val chosenProfileId: Flow<String?> = combine(store.observeAll(), active.activeId) { records, chosen ->
        chosen?.takeIf { id -> records.any { it.id == id } }
    }.distinctUntilChanged()

    override val activeProfileId: Flow<String?> = combine(store.observeAll(), active.activeId) { records, chosen ->
        chosen?.takeIf { id -> records.any { it.id == id } } ?: records.firstOrNull()?.id
    }.distinctUntilChanged()

    override suspend fun get(id: String): ProxyProfile? = store.get(id)?.toProfile()

    override suspend fun loadForEditing(id: String): ProfileForEditing? {
        val record = store.get(id) ?: return null
        val profile = record.toProfile()
        if (!record.requiresSignIn) return ProfileForEditing(profile, ProxyProfileInput.from(profile), CredentialState.NotRequired)
        return when (val opened = open(record)) {
            is Opened.Available -> {
                val input = ProxyProfileInput.from(profile, String(opened.username))
                opened.wipe()
                ProfileForEditing(profile, input, CredentialState.Saved)
            }
            Opened.Unreadable -> ProfileForEditing(profile, ProxyProfileInput.from(profile), CredentialState.Unreadable)
            Opened.Unavailable -> throw CredentialsUnavailableException()
        }
    }

    override suspend fun save(id: String?, input: ProxyProfileInput): ProxyProfile = writes.withLock {
        val existing = id?.let { store.get(it) }
        val saved = if (existing != null && existing.requiresSignIn) open(existing) else null
        try {
            val savedUsername = (saved as? Opened.Available)?.username
            val keepsSavedPassword = existing != null &&
                savedUsername != null &&
                input.password.isEmpty() &&
                input.sameCredentialTargetAs(ProxyProfileInput.from(existing.toProfile(), String(savedUsername)))
            // Whether the saved password may be kept can't be decided without reading it: never guess.
            if (saved == Opened.Unavailable && input.requiresSignIn && input.password.isEmpty()) throw CredentialsUnavailableException()

            val errors = ProfileValidator.validate(input, savedPasswordKept = keepsSavedPassword)
            if (errors.isNotEmpty()) throw InvalidProfileException(errors)

            val profileId = existing?.id ?: newId()
            val now = clock()
            val profile = input.toProfile(profileId, createdAtMillis = existing?.createdAtMillis ?: now, updatedAtMillis = now)
            val previous = existing?.credentials
            val credentials = when {
                !input.requiresSignIn -> null
                keepsSavedPassword -> previous
                else -> seal(profileId, input.username.toCharArray(), input.password.toCharArray())
            }
            writeSealed(credentials, previous) {
                val record = ProfileRecord.of(profile, credentials)
                if (existing == null) store.insert(record) else check(store.update(record)) { "profile disappeared while saving" }
            }
            profile
        } finally {
            (saved as? Opened.Available)?.wipe()
        }
    }

    override suspend fun duplicate(id: String, name: String): ProxyProfile? = writes.withLock {
        val source = store.get(id) ?: return@withLock null
        val copyName = ProfileNames.truncate(name.trim(), ProfileValidator.MAX_NAME_LENGTH)
        ProfileValidator.nameError(copyName)?.let { throw InvalidProfileException(mapOf(ProfileField.Name to it)) }
        val copyId = newId()
        val now = clock()
        val credentials = if (!source.requiresSignIn) {
            null
        } else {
            when (val opened = open(source)) {
                // seal() wipes the decrypted arrays.
                is Opened.Available -> seal(copyId, opened.username, opened.password)
                // Not copied: the copy asks for them again, like the original does.
                Opened.Unreadable -> null
                Opened.Unavailable -> throw CredentialsUnavailableException()
            }
        }
        val copy = source.toProfile().copy(id = copyId, name = copyName, createdAtMillis = now, updatedAtMillis = now)
        writeSealed(credentials, previous = null) { store.insert(ProfileRecord.of(copy, credentials)) }
        copy
    }

    override suspend fun delete(id: String) = writes.withLock {
        val record = store.get(id)
        store.delete(id)
        record?.credentials?.let { deleteKeyQuietly(it.keyId) }
        if (active.activeId.first() == id) active.set(null)
    }

    override suspend fun setActive(id: String) {
        if (store.get(id) != null) active.set(id)
    }

    override suspend fun readCredentials(id: String): CredentialsRead {
        val record = store.get(id) ?: return CredentialsRead.NoSuchProfile
        return credentialsOf(record)
    }

    override suspend fun readCredentials(id: String, expected: ProxyProfile): CredentialsRead {
        val record = store.get(id) ?: return CredentialsRead.NoSuchProfile
        if (!record.toProfile().sameServerAs(expected)) return CredentialsRead.ProfileChanged
        return credentialsOf(record)
    }

    private suspend fun credentialsOf(record: ProfileRecord): CredentialsRead {
        if (!record.requiresSignIn) return CredentialsRead.NotRequired
        return when (val opened = open(record)) {
            is Opened.Available -> CredentialsRead.Available(opened.username, opened.password)
            Opened.Unreadable -> CredentialsRead.Unreadable
            Opened.Unavailable -> CredentialsRead.TemporarilyUnavailable
        }
    }

    /**
     * Deletes keys no stored credentials use: left behind when the app stopped
     * between replacing or deleting credentials and deleting their old key, or
     * keys Android could not delete at the time. Run at start.
     * @return how many keys were deleted.
     */
    suspend fun deleteOrphanedKeys(): Int = writes.withLock {
        val orphans = withContext(cryptoDispatcher) { cipher.keyIds() } - store.credentialKeyIds()
        orphans.forEach { deleteKeyQuietly(it) }
        orphans.size
    }

    /**
     * Runs [write], which stores [credentials] in place of [previous], then deletes
     * the key [previous] was sealed under. If [write] fails, the new key is deleted
     * instead and the stored data is as it was.
     */
    private suspend fun writeSealed(credentials: SealedCredentials?, previous: SealedCredentials?, write: suspend () -> Unit) {
        val newKey = credentials?.keyId?.takeIf { it != previous?.keyId }
        try {
            write()
        } catch (e: Exception) {
            newKey?.let { deleteKeyQuietly(it) }
            throw e
        }
        previous?.keyId?.takeIf { it != credentials?.keyId }?.let { deleteKeyQuietly(it) }
    }

    /** Encrypts under a new key, and wipes [username] and [password]. */
    private suspend fun seal(profileId: String, username: CharArray, password: CharArray): SealedCredentials =
        withContext(cryptoDispatcher) {
            val plaintext = CredentialCodec.encode(username, password)
            try {
                cipher.seal(profileId, plaintext)
            } finally {
                plaintext.fill(0)
                username.fill('\u0000')
                password.fill('\u0000')
            }
        }

    /** Decrypted credentials, or why there are none. A failure that may be temporary is tried once more (ADR-0006). */
    private sealed interface Opened {
        class Available(val username: CharArray, val password: CharArray) : Opened {
            fun wipe() {
                username.fill('\u0000')
                password.fill('\u0000')
            }
        }

        /** Can never be decrypted: a missing or invalidated key, changed data, an unknown format. */
        data object Unreadable : Opened

        /** The Keystore failed twice in a row in a way that may pass; nothing is known about the data. */
        data object Unavailable : Opened
    }

    private suspend fun open(record: ProfileRecord): Opened {
        val sealed = record.credentials ?: return Opened.Unreadable
        return withContext(cryptoDispatcher) {
            for (attempt in 1..2) {
                val plaintext = try {
                    cipher.open(record.id, sealed)
                } catch (e: CredentialUnreadableException) {
                    if (e.permanent) return@withContext Opened.Unreadable
                    continue
                }
                return@withContext try {
                    val (username, password) = CredentialCodec.decode(plaintext)
                    Opened.Available(username, password)
                } catch (e: IllegalArgumentException) {
                    Opened.Unreadable
                } finally {
                    plaintext.fill(0)
                }
            }
            Opened.Unavailable
        }
    }

    private suspend fun deleteKeyQuietly(keyId: String) {
        withContext(cryptoDispatcher) {
            // A key that can't be deleted now is deleted by deleteOrphanedKeys() at the next start.
            runCatching { cipher.deleteKey(keyId) }
        }
    }
}
