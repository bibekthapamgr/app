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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.TlsMode
import kotlinx.coroutines.flow.Flow

/**
 * One saved profile (docs/m1/06-architecture.md §6). The username and password
 * are only in [credentialsCiphertext]: AES-256-GCM under the Keystore key
 * [credentialsKey], made for these credentials alone, with the profile id as
 * associated data (ADR-0006). Enums are stored by name. Rows are compared by
 * id where it matters, never by their byte arrays.
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
    /** Id of the Keystore key the credentials are sealed under (`proxyflow_credentials_<id>`); added in version 2. */
    @ColumnInfo(name = "credentials_key") val credentialsKey: String?,
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

    @Query("SELECT credentials_key FROM profiles WHERE credentials_key IS NOT NULL")
    suspend fun credentialKeyIds(): List<String>

    @Insert
    suspend fun insert(profile: ProfileEntity)

    @Update
    suspend fun update(profile: ProfileEntity): Int

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: String): Int
}

/**
 * The app database. Every schema change raises the version and adds a tested
 * migration; the exported schema of every version is committed in
 * `core/data/schemas`, CI checks the current one against the entities, and
 * `DatabaseMigrationTest` migrates every earlier one (ADR-0009).
 *
 * - Version 1 (M3): profiles, credentials sealed under one key per profile.
 * - Version 2 (M3): `credentials_key`, so every new set of credentials gets a new key.
 */
@Database(entities = [ProfileEntity::class], version = 2, exportSchema = true)
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
            Room.databaseBuilder(context.applicationContext, ProxyFlowDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/**
 * Version 1 sealed each profile's credentials under one key named after the
 * profile, so those rows keep using that key: their key id is the profile id.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE profiles ADD COLUMN credentials_key TEXT")
        db.execSQL("UPDATE profiles SET credentials_key = id WHERE credentials_ct IS NOT NULL")
    }
}
