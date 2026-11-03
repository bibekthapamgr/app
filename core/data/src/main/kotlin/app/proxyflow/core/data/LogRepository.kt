package app.proxyflow.core.data

import android.content.Context
import app.proxyflow.core.data.db.LogDatabase
import app.proxyflow.core.data.db.LogEventDao
import app.proxyflow.core.data.db.LogEventEntity
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogSanitizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * The local diagnostic log the Logs screen shows (M7, ADR-0012). It is bounded: entries older
 * than [RETENTION_MILLIS] go, and never more than [MAX_ENTRIES] are kept. Everything written goes
 * through [LogSanitizer] once more.
 */
interface LogRepository {
    /** Every entry kept, newest first. */
    val entries: Flow<List<LogEntry>>

    suspend fun append(entries: List<LogEntry>)

    /** Removes every entry. */
    suspend fun clear()

    /** Drops what the bounds no longer allow, without writing (at start, and when the Logs screen opens). */
    suspend fun purge()

    companion object {
        /** One day, the M1 default (docs/m1/06-architecture.md §7). */
        const val RETENTION_MILLIS = 24L * 60 * 60 * 1_000

        /** About a week of busy use at a few hundred entries a day, well within a megabyte or two. */
        const val MAX_ENTRIES = 5_000
    }
}

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
    override val entries: Flow<List<LogEntry>> = list.asStateFlow()

    override suspend fun append(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        synchronized(this) {
            val added = entries.map { it.sanitized().copy(id = nextId++) }
            list.value = bounded(added + list.value)
        }
    }

    override suspend fun clear() {
        synchronized(this) { list.value = emptyList() }
    }

    override suspend fun purge() {
        synchronized(this) { list.value = bounded(list.value) }
    }

    private fun bounded(all: List<LogEntry>): List<LogEntry> {
        val cutoff = clock() - retentionMillis
        return all.filter { it.timeMillis >= cutoff }
            .sortedWith(compareByDescending<LogEntry> { it.timeMillis }.thenByDescending { it.id })
            .take(maxEntries)
    }
}

/** The log in its own Room database ([LogDatabase]). */
class RoomLogRepository(
    private val dao: LogEventDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = LogRepository.RETENTION_MILLIS,
    private val maxEntries: Int = LogRepository.MAX_ENTRIES,
) : LogRepository {
    override val entries: Flow<List<LogEntry>> = dao.observeLatest(maxEntries).map { rows -> rows.mapNotNull { it.toEntry() } }

    override suspend fun append(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        dao.insertBounded(entries.map { it.sanitized().toRow() }, clock() - retentionMillis, maxEntries)
    }

    override suspend fun clear() {
        dao.deleteAll()
    }

    override suspend fun purge() {
        dao.purge(clock() - retentionMillis, maxEntries)
    }
}

/** Builds the log's storage, so no other module needs Room on its classpath. */
object LogStorage {
    fun repository(context: Context): LogRepository = RoomLogRepository(LogDatabase.create(context).logEventDao())
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
