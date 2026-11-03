package app.proxyflow.core.vpn

import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the session's diagnostic log entries go (ADR-0012). Every call returns at once and never throws. */
interface DiagnosticsSink {
    fun record(entry: LogEntry)

    /** A connection to one site failed while the proxy worked: counted, and written once a minute per code. */
    fun connectionFailed(code: String, profileId: String?) = Unit

    /** Writes the counts gathered so far now (the session is ending). */
    fun flushProblems() = Unit

    object None : DiagnosticsSink {
        override fun record(entry: LogEntry) = Unit
    }
}

/**
 * Writes the session's entries to the [LogRepository] in order, off the session's thread and in
 * batches, so logging never slows a transition; a backlog past [capacity] drops the oldest unwritten
 * entries. Sites' connection failures — as many as an app makes — are counted per code and profile
 * and written as one [LogEvent.ConnectionProblems] entry each per [windowMillis], dated when the first
 * of them failed (so a count gathered before the log was cleared is dropped with it).
 */
class DiagnosticsRecorder(
    private val repository: LogRepository,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMillis: Long = 60_000,
    capacity: Int = 1_000,
) : DiagnosticsSink {
    private val queue = Channel<LogEntry>(capacity, BufferOverflow.DROP_OLDEST)
    private val counts = mutableMapOf<Pair<String?, String>, Problems>()

    init {
        val quiet = CoroutineExceptionHandler { _, _ -> } // a log that can't be written must not take anything down
        scope.launch(quiet) {
            for (first in queue) {
                val batch = mutableListOf(first)
                while (batch.size < BATCH) batch += queue.tryReceive().getOrNull() ?: break
                runCatching { repository.append(batch) }
            }
        }
        scope.launch(quiet) {
            while (true) {
                delay(windowMillis)
                flushProblems()
            }
        }
    }

    override fun record(entry: LogEntry) {
        queue.trySend(entry)
    }

    override fun connectionFailed(code: String, profileId: String?) {
        val now = clock()
        synchronized(counts) {
            val key = profileId to code
            counts[key] = counts[key]?.let { it.copy(count = it.count + 1) } ?: Problems(firstMillis = now, count = 1)
        }
    }

    override fun flushProblems() {
        val gathered = synchronized(counts) { counts.toMap().also { counts.clear() } }
        for ((key, problems) in gathered.entries.sortedBy { it.key.second }) {
            record(
                LogEntry(
                    timeMillis = problems.firstMillis,
                    level = LogEntry.Level.Warning,
                    event = LogEvent.ConnectionProblems,
                    stage = ConnectionStage.Connected,
                    code = key.second,
                    profileId = key.first,
                    details = mapOf(
                        LogDetail.COUNT to problems.count.toString(),
                        LogDetail.WINDOW_S to (windowMillis / 1_000).toString(),
                    ),
                ),
            )
        }
    }

    private data class Problems(val firstMillis: Long, val count: Long)

    private companion object {
        const val BATCH = 100
    }
}
