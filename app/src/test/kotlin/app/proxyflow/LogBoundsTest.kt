package app.proxyflow

import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogRetention
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The log's bounds at start-up (M10 review): the user's retention is applied before the first purge, so a 7-day choice
 * keeps entries older than the 24-hour default; a setting that can't be read, or isn't read in time, keeps the default.
 */
class LogBoundsTest {
    private val calls = CopyOnWriteArrayList<String>()

    private val logs = object : LogRepository {
        override val entries: Flow<List<LogEntry>> = emptyFlow()
        override val generation: Long = 0
        override val retentionMillis: StateFlow<Long> = MutableStateFlow(LogRepository.RETENTION_MILLIS)

        override suspend fun setRetention(millis: Long) {
            calls += "retention $millis"
        }

        override suspend fun append(entries: List<LogEntry>, generation: Long) = Unit

        override suspend fun clear() = Unit

        override suspend fun purge() {
            calls += "purge"
        }
    }

    @Test
    fun theUsersRetentionComesBeforeTheFirstPurge() {
        val slowSettings = flow {
            delay(200) // the settings file read at start
            emit(LogRetention.Week)
        }
        run(slowSettings, until = 2)
        assertEquals(listOf("retention ${LogRetention.Week.millis}", "purge"), calls.toList())
    }

    @Test
    fun aChangeOfTheSettingAppliesAtOnce() {
        val setting = MutableStateFlow(LogRetention.Week)
        runBlocking {
            val job = CoroutineScope(Dispatchers.Default).launch {
                keepTheLogsBounds(logs, setting, intervalMillis = HOUR, settingsWaitMillis = 5_000)
            }
            awaitCalls(2)
            setting.value = LogRetention.Hour
            awaitCalls(3)
            job.cancelAndJoin()
        }
        assertEquals(
            "the setting read once at start, then its change",
            listOf("retention ${LogRetention.Week.millis}", "purge", "retention ${LogRetention.Hour.millis}"),
            calls.toList(),
        )
    }

    @Test
    fun aSettingNeverReadOrUnreadableKeepsTheDefault() {
        val never = flow<LogRetention> { delay(Long.MAX_VALUE) }
        run(never, until = 1, settingsWaitMillis = 100)
        assertEquals("purged after the wait, with the default", listOf("purge"), calls.toList())

        calls.clear()
        run(flow { throw IllegalStateException("unreadable settings") }, until = 1)
        assertEquals(listOf("purge"), calls.toList())

        calls.clear()
        run(flowOf(LogRetention.Day), until = 2)
        assertEquals(listOf("retention ${LogRetention.Day.millis}", "purge"), calls.toList())
    }

    private fun run(retention: Flow<LogRetention>, until: Int, settingsWaitMillis: Long = 5_000) = runBlocking {
        val job = CoroutineScope(Dispatchers.Default).launch { keepTheLogsBounds(logs, retention, HOUR, settingsWaitMillis) }
        awaitCalls(until)
        delay(100) // anything else that would come, comes
        job.cancelAndJoin()
    }

    private suspend fun awaitCalls(count: Int) = withTimeout(5_000) {
        while (calls.size < count) delay(5)
    }

    private companion object {
        const val HOUR = 60L * 60 * 1_000
    }
}
