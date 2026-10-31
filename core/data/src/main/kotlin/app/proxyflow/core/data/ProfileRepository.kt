package app.proxyflow.core.data

import app.proxyflow.core.data.crypto.CredentialCipher
import app.proxyflow.core.data.crypto.CredentialCodec
import app.proxyflow.core.data.crypto.CredentialUnreadableException
import app.proxyflow.core.data.crypto.SealedCredentials
import app.proxyflow.core.model.CredentialState
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
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

    /** The profile the user chose, or the oldest one when that one is gone; null when there are none. */
    val activeProfileId: Flow<String?>

    suspend fun get(id: String): ProxyProfile?

    /** [id]'s settings and decrypted username for the edit form, never its password; null when it is gone. */
    suspend fun loadForEditing(id: String): ProfileForEditing?

    /**
     * Validates [input] and saves it under [id], or as a new profile when [id] is
     * null or no longer exists. A non-empty password replaces the saved one. An
     * empty password keeps the saved one only while protocol, server, port,
     * username and TLS settings are unchanged
     * ([ProxyProfileInput.sameCredentialTargetAs]), so a password is never sent
     * to a proxy it was not saved for. Turning sign-in off deletes the profile's
     * key, which makes its old credentials unreadable.
     *
     * @throws InvalidProfileException when [input] does not pass [ProfileValidator]; nothing is saved.
     */
    suspend fun save(id: String?, input: ProxyProfileInput): ProxyProfile

    /** Copies [id] (settings, username and password) as a new profile named [name]; null when [id] is gone. */
    suspend fun duplicate(id: String, name: String): ProxyProfile?

    /** Deletes the profile, its encrypted credentials and its Keystore key. */
    suspend fun delete(id: String)

    suspend fun setActive(id: String)

    /** The username and password for connecting (from M4). The caller wipes them as soon as they are sent. */
    suspend fun readCredentials(id: String): CredentialsRead
}

data class ProfileForEditing(val profile: ProxyProfile, val input: ProxyProfileInput, val credentials: CredentialState)

/** The outcome of reading a profile's credentials for connecting. */
sealed interface CredentialsRead {
    data object NoSuchProfile : CredentialsRead

    data object NotRequired : CredentialsRead

    /** They can't be decrypted (ADR-0006); connecting reports `CREDENTIAL_UNREADABLE` (board H3). */
    data object Unreadable : CredentialsRead

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

/**
 * Profiles in a [ProfileStore] (Room in the app), credentials encrypted with
 * [CredentialCipher] (one Android Keystore key per profile, ADR-0006), the
 * chosen profile in [ActiveProfileStore] (DataStore). Writes are serialized,
 * and every decryption and key operation runs on [cryptoDispatcher], never
 * on the main thread.
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

    override val activeProfileId: Flow<String?> = combine(store.observeAll(), active.activeId) { records, chosen ->
        chosen?.takeIf { id -> records.any { it.id == id } } ?: records.firstOrNull()?.id
    }.distinctUntilChanged()

    override suspend fun get(id: String): ProxyProfile? = store.get(id)?.toProfile()

    override suspend fun loadForEditing(id: String): ProfileForEditing? {
        val record = store.get(id) ?: return null
        val profile = record.toProfile()
        if (!record.requiresSignIn) return ProfileForEditing(profile, ProxyProfileInput.from(profile), CredentialState.NotRequired)
        val opened = open(record) ?: return ProfileForEditing(profile, ProxyProfileInput.from(profile), CredentialState.Unreadable)
        val (username, password) = opened
        password.fill('\u0000')
        val input = ProxyProfileInput.from(profile, String(username))
        username.fill('\u0000')
        return ProfileForEditing(profile, input, CredentialState.Saved)
    }

    override suspend fun save(id: String?, input: ProxyProfileInput): ProxyProfile = writes.withLock {
        val existing = id?.let { store.get(it) }
        val saved = existing?.takeIf { it.requiresSignIn }?.let { open(it) }
        try {
            val keepsSavedPassword = existing != null &&
                saved != null &&
                input.password.isEmpty() &&
                input.sameCredentialTargetAs(ProxyProfileInput.from(existing.toProfile(), String(saved.first)))
            val errors = ProfileValidator.validate(input, savedPasswordKept = keepsSavedPassword)
            if (errors.isNotEmpty()) throw InvalidProfileException(errors)

            val profileId = existing?.id ?: newId()
            val now = clock()
            val profile = input.toProfile(profileId, createdAtMillis = existing?.createdAtMillis ?: now, updatedAtMillis = now)
            val credentials = when {
                !input.requiresSignIn -> null
                keepsSavedPassword -> existing?.credentials
                else -> seal(profileId, input.username.toCharArray(), input.password.toCharArray())
            }
            val record = ProfileRecord.of(profile, credentials)
            if (existing == null) store.insert(record) else check(store.update(record)) { "profile disappeared while saving" }
            // Sign-in turned off: without its key, the old ciphertext can never be read again.
            if (!input.requiresSignIn) deleteKeyQuietly(profileId)
            profile
        } finally {
            saved?.let { (username, password) ->
                username.fill('\u0000')
                password.fill('\u0000')
            }
        }
    }

    override suspend fun duplicate(id: String, name: String): ProxyProfile? = writes.withLock {
        val source = store.get(id) ?: return@withLock null
        val copyId = newId()
        val now = clock()
        // Unreadable credentials are not copied; the copy then asks for them like the original does.
        val credentials = if (source.requiresSignIn) {
            open(source)?.let { (username, password) ->
                seal(copyId, username, password)
            }
        } else {
            null
        }
        val copy = source.toProfile().copy(
            id = copyId,
            name = name.trim().take(ProfileValidator.MAX_NAME_LENGTH),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        store.insert(ProfileRecord.of(copy, credentials))
        copy
    }

    override suspend fun delete(id: String) = writes.withLock {
        store.delete(id)
        deleteKeyQuietly(id)
        if (active.activeId.first() == id) active.set(null)
    }

    override suspend fun setActive(id: String) {
        if (store.get(id) != null) active.set(id)
    }

    override suspend fun readCredentials(id: String): CredentialsRead {
        val record = store.get(id) ?: return CredentialsRead.NoSuchProfile
        if (!record.requiresSignIn) return CredentialsRead.NotRequired
        val (username, password) = open(record) ?: return CredentialsRead.Unreadable
        return CredentialsRead.Available(username, password)
    }

    /**
     * Deletes keys whose profile no longer exists, left behind when the app
     * stopped between deleting a profile and deleting its key. Run at start.
     * @return how many keys were deleted.
     */
    suspend fun deleteOrphanedKeys(): Int = writes.withLock {
        val orphans = withContext(cryptoDispatcher) { cipher.profilesWithKeys() } - store.ids()
        orphans.forEach { deleteKeyQuietly(it) }
        orphans.size
    }

    /** Encrypts and wipes [username] and [password]. */
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

    /**
     * The decrypted username and password, or null when they can't be read.
     * A failure that may be temporary is tried once more (ADR-0006).
     */
    private suspend fun open(record: ProfileRecord): Pair<CharArray, CharArray>? {
        val sealed = record.credentials ?: return null
        return withContext(cryptoDispatcher) {
            var result: Pair<CharArray, CharArray>? = null
            for (attempt in 1..2) {
                val plaintext = try {
                    cipher.open(record.id, sealed)
                } catch (e: CredentialUnreadableException) {
                    if (e.permanent) break else continue
                }
                result = try {
                    CredentialCodec.decode(plaintext)
                } catch (e: IllegalArgumentException) {
                    null
                } finally {
                    plaintext.fill(0)
                }
                break
            }
            result
        }
    }

    private suspend fun deleteKeyQuietly(profileId: String) {
        withContext(cryptoDispatcher) {
            // A key that can't be deleted now is deleted by deleteOrphanedKeys() at the next start.
            runCatching { cipher.deleteKey(profileId) }
        }
    }
}
