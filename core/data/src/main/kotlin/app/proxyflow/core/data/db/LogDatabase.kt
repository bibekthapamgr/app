package app.proxyflow.core.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One entry of the diagnostic log (ADR-0012): stable keys for the level, the event and the stage,
 * the catalogue code, the profile's random id and the sanitized details as a JSON object of strings.
 * No column can hold a credential, a server name, a site or a payload: nothing the app writes has one.
 */
@Entity(tableName = "log_events", indices = [Index(value = ["time_millis"])])
data class LogEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "time_millis") val timeMillis: Long,
    val level: String,
    val event: String,
    val stage: String?,
    val code: String?,
    @ColumnInfo(name = "profile_id") val profileId: String?,
    val details: String,
)

@Dao
abstract class LogEventDao {
    /** Newest first; [limit] bounds what the screen reads. */
    @Query("SELECT * FROM log_events ORDER BY time_millis DESC, id DESC LIMIT :limit")
    abstract fun observeLatest(limit: Int): Flow<List<LogEventEntity>>

    @Query("SELECT COUNT(*) FROM log_events")
    abstract suspend fun count(): Int

    /** Unbounded: use [insertBounded]. */
    @Insert
    abstract suspend fun insertAll(rows: List<LogEventEntity>)

    /** Entries dated before [cutoffMillis] or after [futureMillis] (the clock was set ahead, then back). */
    @Query("DELETE FROM log_events WHERE time_millis < :cutoffMillis OR time_millis > :futureMillis")
    abstract suspend fun deleteOutside(cutoffMillis: Long, futureMillis: Long): Int

    /** Keeps the newest [keep] entries. */
    @Query("DELETE FROM log_events WHERE id NOT IN (SELECT id FROM log_events ORDER BY time_millis DESC, id DESC LIMIT :keep)")
    abstract suspend fun trimTo(keep: Int): Int

    @Query("DELETE FROM log_events")
    abstract suspend fun deleteAll(): Int

    /**
     * Writes [rows], then drops what is dated outside [cutoffMillis]..[futureMillis] and all but the newest
     * [keep]: the log never grows past its bounds.
     */
    @Transaction
    open suspend fun insertBounded(rows: List<LogEventEntity>, cutoffMillis: Long, futureMillis: Long, keep: Int) {
        insertAll(rows)
        deleteOutside(cutoffMillis, futureMillis)
        trimTo(keep)
    }

    /** Applies the bounds without writing (at start, every hour, and each time the Logs screen is shown). */
    @Transaction
    open suspend fun purge(cutoffMillis: Long, futureMillis: Long, keep: Int) {
        deleteOutside(cutoffMillis, futureMillis)
        trimTo(keep)
    }
}

/**
 * The diagnostic log's own database, apart from the profiles' (ADR-0012): clearing it, its retention
 * and its schema never touch a profile. SQLite's `secure_delete` overwrites deleted entries, and the
 * rollback journal is truncated after each write instead of a write-ahead log keeping old pages, so
 * "Clear" leaves no readable entry behind in the file: both are set on the connection each time it
 * opens, whatever the platform's defaults. Like the profiles' database, its exported schema is
 * committed in `core/data/schemas` and checked in CI (ADR-0009).
 *
 * - Version 1 (M7): `log_events`.
 */
@Database(entities = [LogEventEntity::class], version = 1, exportSchema = true)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logEventDao(): LogEventDao

    /** `journal_mode` and `secure_delete` as this database's connection has them (one connection: no write-ahead log). */
    fun storageSettings(): Map<String, String> {
        val db = openHelper.writableDatabase
        return listOf("journal_mode", "secure_delete").associateWith { pragma ->
            db.query("PRAGMA $pragma").use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).lowercase() else "" }
        }
    }

    companion object {
        const val NAME = "logs.db"

        fun create(context: Context, name: String = NAME): LogDatabase =
            Room.databaseBuilder(context.applicationContext, LogDatabase::class.java, name)
                .setJournalMode(JournalMode.TRUNCATE)
                .addCallback(SecureDelete)
                .build()

        /** In memory, for tests. */
        fun inMemory(context: Context): LogDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, LogDatabase::class.java).addCallback(SecureDelete).build()
    }

    /**
     * `secure_delete` and the journal mode are per-connection settings, so they are set each time the database opens:
     * Room's TRUNCATE only turns the write-ahead log off, leaving the journal mode to the platform's default.
     */
    private object SecureDelete : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query("PRAGMA secure_delete = ON").close()
            db.query("PRAGMA journal_mode = TRUNCATE").close()
        }
    }
}
