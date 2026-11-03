package app.proxyflow.core.data

import android.content.Context
import app.proxyflow.core.data.db.LogDatabase
import app.proxyflow.core.data.db.LogEventDao
import app.proxyflow.core.data.db.LogEventEntity
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * The local diagnostic log the Logs screen shows (M7, ADR-0012). It is bounded: entries older
 * than [RETENTION_MILLIS] go, and never more than [MAX_ENTRIES] are kept. Everything written goes
 * through [LogSanitizer] once more.
 */
interface LogRepository {
    /**
     * Every entry kept, newest first — only those within the retention when read, so an entry past it
     * never shows even before the next purge, nor one dated later than [FUTURE_MARGIN_MILLIS] from now
     * (the phone's clock was set ahead, then back). Read and checked off the main thread.
     */
    val entries: Flow<List<LogEntry>>

    /** Keeps [entries]; one older than the last [clear] (a batch already on its way) is dropped, so cleared events never come back. */
    suspend fun append(entries: List<LogEntry>)

    /** Removes every entry. */
    suspend fun clear()

    /**
     * Drops what the bounds no longer allow, and entries dated beyond [FUTURE_MARGIN_MILLIS], without
     * writing: at start, every hour, and each time the Logs screen is shown.
     */
    suspend fun purge()

    companion object {
        /** One day, the M1 default (docs/m1/06-architecture.md §7). */
        const val RETENTION_MILLIS = 24L * 60 * 60 * 1_000

        /** About a week of busy use at a few hundred entries a day, well within a megabyte or two. */
        const val MAX_ENTRIES = 5_000

        /** How far ahead of the clock an entry may be dated and still be kept (the clock was corrected meanwhile). */
        const val FUTURE_MARGIN_MILLIS = 60L * 60 * 1_000
    }
}

/** Whether an entry dated [timeMillis] is within the log's retention at [now]. */
internal fun kept(timeMillis: Long, now: Long, retentionMillis: Long): Boolean =
    timeMillis >= now - retentionMillis && timeMillis <= now + LogRepository.FUTURE_MARGIN_MILLIS

/** An entry as it is kept: sanitized details and code, whatever the writer passed. */
internal fun LogEntry.sanitized(): LogEntry = copy(code = LogSanitizer.code(code), details = LogSanitizer.details(details))

/** The log in memory (JVM tests and builds without storage), with the same bounds as [RoomLogRepository]. */
class InMemoryLogRepository(
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = LogRepository.RETENTION_MILLIS,
    private val maxEntries: Int = LogRepository.MAX_ENTRIES,
) : LogRepository {
    private val list = MutableStateFlow<List<LogEntry>>(emptyList())
    private var nextId = 1L
    private var clearedAtMillis = Long.MIN_VALUE
    override val entries: Flow<List<LogEntry>> = list.map { all ->
        val now = clock()
        all.filter { kept(it.timeMillis, now, retentionMillis) }
    }

    override suspend fun append(entries: List<LogEntry>) {
        synchronized(this) {
            val added = entries.filter { it.timeMillis >= clearedAtMillis }.map { it.sanitized().copy(id = nextId++) }
            if (added.isNotEmpty()) list.value = bounded(added + list.value)
        }
    }

    override suspend fun clear() {
        synchronized(this) {
            clearedAtMillis = clock()
            list.value = emptyList()
        }
    }

    override suspend fun purge() {
        synchronized(this) { list.value = bounded(list.value) }
    }

    private fun bounded(all: List<LogEntry>): List<LogEntry> {
        val now = clock()
        return all.filter { kept(it.timeMillis, now, retentionMillis) }
            .sortedWith(compareByDescending<LogEntry> { it.timeMillis }.thenByDescending { it.id })
            .take(maxEntries)
    }
}

/** The log in its own Room database ([LogDatabase]). */
class RoomLogRepository(
    private val database: LogDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = LogRepository.RETENTION_MILLIS,
    private val maxEntries: Int = LogRepository.MAX_ENTRIES,
) : LogRepository {
    private val dao: LogEventDao = database.logEventDao()

    @Volatile private var clearedAtMillis = Long.MIN_VALUE

    // Room hands rows to the collector's thread; parsing and sanitizing thousands of them stays off the main thread.
    override val entries: Flow<List<LogEntry>> = dao.observeLatest(maxEntries)
        .map { rows ->
            val now = clock()
            rows.filter { kept(it.timeMillis, now, retentionMillis) }.mapNotNull { it.toEntry() }
        }
        .flowOn(Dispatchers.Default)

    override suspend fun append(entries: List<LogEntry>) {
        val rows = entries.filter { it.timeMillis >= clearedAtMillis }.map { it.sanitized().toRow() }
        if (rows.isEmpty()) return
        val now = clock()
        dao.insertBounded(rows, now - retentionMillis, now + LogRepository.FUTURE_MARGIN_MILLIS, maxEntries)
    }

    override suspend fun clear() {
        clearedAtMillis = clock()
        dao.deleteAll()
    }

    override suspend fun purge() {
        val now = clock()
        dao.purge(now - retentionMillis, now + LogRepository.FUTURE_MARGIN_MILLIS, maxEntries)
    }

    /** What Clear relies on, as this database's own connection has it (device tests): `journal_mode` and `secure_delete`. */
    fun storageSettings(): Map<String, String> = database.storageSettings()
}

/** Builds the log's storage, so no other module needs Room on its classpath. */
object LogStorage {
    fun repository(context: Context): LogRepository = RoomLogRepository(LogDatabase.create(context))
}

internal fun LogEntry.toRow() = LogEventEntity(
    timeMillis = timeMillis,
    level = level.key,
    event = event.key,
    stage = stage?.key,
    code = code,
    profileId = profileId,
    details = JSONObject(details as Map<*, *>).toString(),
)

/** A row written by a newer version with an event this one doesn't know is left out. */
internal fun LogEventEntity.toEntry(): LogEntry? {
    val event = LogEvent.of(event) ?: return null
    val parsed = runCatching {
        val json = JSONObject(details)
        json.keys().asSequence().associateWith { json.optString(it) }
    }.getOrDefault(emptyMap())
    return LogEntry(
        id = id,
        timeMillis = timeMillis,
        level = LogEntry.Level.of(level),
        event = event,
        stage = ConnectionStage.of(stage),
        code = LogSanitizer.code(code),
        profileId = profileId,
        details = LogSanitizer.details(parsed),
    )
}
