package app.proxyflow.core.data

import app.proxyflow.core.data.crypto.SealedCredentials
import app.proxyflow.core.data.db.ProfileDao
import app.proxyflow.core.data.db.ProfileEntity
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.TlsMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A profile as stored: its settings plus the encrypted credentials, if any, and its last connection test ([lastTest],
 * M9), which belongs to these settings and credentials only.
 */
data class ProfileRecord(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val tlsMode: TlsMode,
    val tlsServerName: String?,
    val requiresSignIn: Boolean,
    val credentials: SealedCredentials?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastTest: ProfileTest? = null,
) {
    fun toProfile() = ProxyProfile(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        tlsMode = tlsMode,
        tlsServerName = tlsServerName,
        requiresSignIn = requiresSignIn,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
    )

    companion object {
        fun of(profile: ProxyProfile, credentials: SealedCredentials?, lastTest: ProfileTest? = null) = ProfileRecord(
            id = profile.id,
            name = profile.name,
            protocol = profile.protocol,
            host = profile.host,
            port = profile.port,
            tlsMode = profile.tlsMode,
            tlsServerName = profile.tlsServerName,
            requiresSignIn = profile.requiresSignIn,
            credentials = credentials,
            createdAtMillis = profile.createdAtMillis,
            updatedAtMillis = profile.updatedAtMillis,
            lastTest = lastTest,
        )
    }
}

/**
 * Where profile records are kept. The rules (validation, encryption, the active
 * profile) live in [DefaultProfileRepository], so the Room store used by the
 * app and the in-memory store used by ViewModel tests behave the same.
 */
interface ProfileStore {
    /** All records, oldest first. */
    fun observeAll(): Flow<List<ProfileRecord>>

    suspend fun get(id: String): ProfileRecord?

    suspend fun ids(): Set<String>

    /** The keys the stored credentials are sealed under. */
    suspend fun credentialKeyIds(): Set<String>

    suspend fun insert(record: ProfileRecord)

    /** @return false when there is no record with that id. */
    suspend fun update(record: ProfileRecord): Boolean

    /** Keeps [test] as [id]'s last connection test, changing nothing else. @return false when there is no such record. */
    suspend fun recordTest(id: String, test: ProfileTest): Boolean

    suspend fun delete(id: String)
}

/** [ProfileStore] on the Room database. */
class RoomProfileStore(private val dao: ProfileDao) : ProfileStore {
    override fun observeAll(): Flow<List<ProfileRecord>> = dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    override suspend fun get(id: String): ProfileRecord? = dao.get(id)?.toRecord()

    override suspend fun ids(): Set<String> = dao.ids().toSet()

    override suspend fun credentialKeyIds(): Set<String> = dao.credentialKeyIds().toSet()

    override suspend fun insert(record: ProfileRecord) = dao.insert(record.toEntity())

    override suspend fun update(record: ProfileRecord): Boolean = dao.update(record.toEntity()) == 1

    override suspend fun recordTest(id: String, test: ProfileTest): Boolean =
        dao.recordTest(id, test.atMillis, test.code, test.latencyMillis) == 1

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    private fun ProfileEntity.toRecord(): ProfileRecord {
        val ciphertext = credentialsCiphertext
        val iv = credentialsIv
        val format = credentialsFormat
        val key = credentialsKey
        return ProfileRecord(
            id = id,
            name = name,
            protocol = protocol,
            host = host,
            port = port,
            tlsMode = tlsMode,
            tlsServerName = tlsServerName,
            requiresSignIn = requiresSignIn,
            // A row with only part of the encrypted fields can't be decrypted; it reads as unreadable.
            credentials = if (ciphertext != null && iv != null && format != null && key != null) {
                SealedCredentials(format, key, iv, ciphertext)
            } else {
                null
            },
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            lastTest = lastTestAtMillis?.let { ProfileTest(it, lastTestCode, lastTestLatencyMillis) },
        )
    }

    private fun ProfileRecord.toEntity() = ProfileEntity(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        tlsMode = tlsMode,
        tlsServerName = tlsServerName,
        requiresSignIn = requiresSignIn,
        credentialsCiphertext = credentials?.ciphertext,
        credentialsIv = credentials?.iv,
        credentialsFormat = credentials?.formatVersion,
        credentialsKey = credentials?.keyId,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        lastTestAtMillis = lastTest?.atMillis,
        lastTestCode = lastTest?.code,
        lastTestLatencyMillis = lastTest?.latencyMillis,
    )
}
