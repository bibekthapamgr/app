package app.proxyflow.core.testing

import app.proxyflow.core.data.ActiveProfileStore
import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.DefaultProfileRepository
import app.proxyflow.core.data.ProfileRecord
import app.proxyflow.core.data.ProfileStore
import app.proxyflow.core.data.crypto.AesGcmCredentialCipher
import app.proxyflow.core.data.crypto.SecretKeyStore
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProxyProfileInput
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Profile records in memory, ordered like the Room store (oldest first). */
class InMemoryProfileStore : ProfileStore {
    val records = MutableStateFlow<List<ProfileRecord>>(emptyList())

    override fun observeAll(): Flow<List<ProfileRecord>> = records

    override suspend fun get(id: String): ProfileRecord? = records.value.firstOrNull { it.id == id }

    override suspend fun ids(): Set<String> = records.value.map { it.id }.toSet()

    override suspend fun credentialKeyIds(): Set<String> = records.value.mapNotNull { it.credentials?.keyId }.toSet()

    override suspend fun insert(record: ProfileRecord) = records.update { list ->
        require(list.none { it.id == record.id }) { "duplicate id" }
        (list + record).sortedWith(compareBy({ it.createdAtMillis }, { it.id }))
    }

    override suspend fun update(record: ProfileRecord): Boolean {
        var found = false
        records.update { list ->
            list.map { if (it.id == record.id) record.also { found = true } else it }
        }
        return found
    }

    override suspend fun delete(id: String) = records.update { list -> list.filterNot { it.id == id } }
}

/**
 * AES-256 keys made in software, standing in for the Android Keystore on the
 * JVM, where it does not exist. The same instance keeps its keys, like the
 * Keystore keeps them across app restarts. Never used by the app itself.
 */
class SoftwareSecretKeyStore : SecretKeyStore {
    private val keys = mutableMapOf<String, SecretKey>()

    @Synchronized
    override fun create(alias: String): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also {
        keys[alias] =
            it
    }

    /** Puts [key] under [alias], for example a key the cipher will reject, as Android does with an invalidated one. */
    @Synchronized
    fun put(alias: String, key: SecretKey) {
        keys[alias] = key
    }

    @Synchronized
    override fun get(alias: String): SecretKey? = keys[alias]

    @Synchronized
    override fun delete(alias: String) {
        keys.remove(alias)
    }

    @Synchronized
    override fun aliases(): Set<String> = keys.keys.toSet()
}

class InMemoryActiveProfileStore(initial: String? = null) : ActiveProfileStore {
    val state = MutableStateFlow(initial)
    override val activeId: Flow<String?> = state

    override suspend fun set(id: String?) {
        state.value = id
    }
}

/**
 * The app's real repository rules on in-memory storage and software keys, for
 * ViewModel and UI tests. Profile ids are "p1", "p2"…, key ids "k1", "k2"…; time
 * starts at 1 000 and moves one second per write.
 */
class TestProfiles(
    val store: InMemoryProfileStore = InMemoryProfileStore(),
    val keys: SoftwareSecretKeyStore = SoftwareSecretKeyStore(),
    val active: InMemoryActiveProfileStore = InMemoryActiveProfileStore(),
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) {
    private var nextId = 0
    private var nextKey = 0
    private var now = 0L

    val cipher = AesGcmCredentialCipher(keys, newKeyId = { "k${++nextKey}" })

    val repository = DefaultProfileRepository(
        store = store,
        cipher = cipher,
        active = active,
        clock = { 1_000L * ++now },
        newId = { "p${++nextId}" },
        cryptoDispatcher = dispatcher,
    )
}

/** A tester that answers [result] and records what it was asked. */
class FakeConnectionTester(var result: ConnectionTestResult = ConnectionTestResult.Unavailable) : ConnectionTester {
    val requests = mutableListOf<Pair<String?, ProxyProfileInput>>()

    override suspend fun test(profileId: String?, input: ProxyProfileInput): ConnectionTestResult {
        requests += profileId to input
        return result
    }
}
