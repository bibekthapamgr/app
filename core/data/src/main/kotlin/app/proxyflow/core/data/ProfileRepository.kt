package app.proxyflow.core.data

import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Saved proxy profiles and which one is active. */
interface ProfileRepository {
    val profiles: Flow<List<ProxyProfile>>
    val activeProfileId: Flow<String?>

    suspend fun get(id: String): ProxyProfile?

    /**
     * Saves a validated form under [id], or under a new id when [id] is null.
     * A non-empty [ProxyProfileInput.password] replaces the stored password. An
     * empty one keeps it (the form never shows a saved password), but only while
     * the protocol, server, port, username and TLS settings are unchanged
     * ([ProxyProfileInput.sameCredentialTargetAs]); otherwise the stored password
     * is erased, so it is never sent to a proxy it was not saved for. Turning
     * sign-in off erases it too.
     */
    suspend fun save(id: String?, input: ProxyProfileInput): ProxyProfile

    suspend fun delete(id: String)

    suspend fun setActive(id: String)
}

/**
 * M2 profile store. It lives as long as the app process because the
 * application owns it, not an Activity. M3 replaces it with Room plus a
 * Keystore-encrypted credential store (ADR-0006) behind the same interface.
 */
class InMemoryProfileRepository(private val newId: () -> String = { UUID.randomUUID().toString() }) : ProfileRepository {
    private val mutex = Mutex()
    private val passwords = mutableMapOf<String, CharArray>()
    private val profileList = MutableStateFlow<List<ProxyProfile>>(emptyList())
    private val activeId = MutableStateFlow<String?>(null)

    override val profiles: StateFlow<List<ProxyProfile>> = profileList.asStateFlow()
    override val activeProfileId: StateFlow<String?> = activeId.asStateFlow()

    override suspend fun get(id: String): ProxyProfile? = profileList.value.firstOrNull { it.id == id }

    override suspend fun save(id: String?, input: ProxyProfileInput): ProxyProfile = mutex.withLock {
        val profileId = id ?: newId()
        val existing = profileList.value.firstOrNull { it.id == profileId }
        when {
            !input.requiresSignIn -> passwords.remove(profileId)?.fill(ZERO)
            input.password.isNotEmpty() -> passwords.put(profileId, input.password.toCharArray())?.fill(ZERO)
            existing == null || !input.sameCredentialTargetAs(ProxyProfileInput.from(existing)) -> passwords.remove(profileId)?.fill(ZERO)
        }
        val saved = input.toProfile(profileId, hasPassword = profileId in passwords)
        val current = profileList.value
        val index = current.indexOfFirst { it.id == profileId }
        profileList.value = if (index < 0) current + saved else current.toMutableList().also { it[index] = saved }
        if (activeId.value == null) activeId.value = profileId
        saved
    }

    override suspend fun delete(id: String) = mutex.withLock {
        passwords.remove(id)?.fill(ZERO)
        profileList.value = profileList.value.filterNot { it.id == id }
        if (activeId.value == id) activeId.value = profileList.value.firstOrNull()?.id
    }

    override suspend fun setActive(id: String) = mutex.withLock {
        if (profileList.value.any { it.id == id }) activeId.value = id
    }

    private companion object {
        const val ZERO = '\u0000'
    }
}
