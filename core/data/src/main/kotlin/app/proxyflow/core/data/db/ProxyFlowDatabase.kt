package app.proxyflow.core.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.TlsMode
import kotlinx.coroutines.flow.Flow

/**
 * One saved profile (docs/m1/06-architecture.md §6). The username and password
 * are only in [credentialsCiphertext]: AES-256-GCM with the profile's own
 * Keystore key, the profile id as associated data (ADR-0006). Enums are stored
 * by name. Rows are compared by id where it matters, never by their byte arrays.
 */
@Suppress("ArrayInDataClass")
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    @ColumnInfo(name = "tls_mode") val tlsMode: TlsMode,
    @ColumnInfo(name = "tls_server_name") val tlsServerName: String?,
    @ColumnInfo(name = "requires_sign_in") val requiresSignIn: Boolean,
    @ColumnInfo(name = "credentials_ct", typeAffinity = ColumnInfo.BLOB) val credentialsCiphertext: ByteArray?,
    @ColumnInfo(name = "credentials_iv", typeAffinity = ColumnInfo.BLOB) val credentialsIv: ByteArray?,
    @ColumnInfo(name = "credentials_format") val credentialsFormat: Int?,
    @ColumnInfo(name = "created_at") val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
)

@Dao
interface ProfileDao {
    /** Oldest first, so the list keeps the order in which profiles were added. */
    @Query("SELECT * FROM profiles ORDER BY created_at, id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: String): ProfileEntity?

    @Query("SELECT id FROM profiles")
    suspend fun ids(): List<String>

    @Insert
    suspend fun insert(profile: ProfileEntity)

    @Update
    suspend fun update(profile: ProfileEntity): Int

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String): Int
}

/**
 * The app database (version 1, M3). Every schema change raises [version] and
 * adds a tested migration; the exported schemas in `core/data/schemas` are
 * checked by `DatabaseMigrationTest` and by CI (ADR-0009).
 */
@Database(entities = [ProfileEntity::class], version = 1, exportSchema = true)
abstract class ProxyFlowDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

    companion object {
        const val NAME = "proxyflow.db"

        /**
         * The database file in the app's private storage. There is deliberately no
         * destructive fallback: a missing migration fails loudly and never deletes
         * the user's profiles.
         */
        fun create(context: Context, name: String = NAME): ProxyFlowDatabase =
            Room.databaseBuilder(context.applicationContext, ProxyFlowDatabase::class.java, name).build()
    }
}
