package app.proxyflow.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.db.LogDatabase
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The diagnostic log's storage (ADR-0012): bounded, sanitized, and empty after Clear — in the file too. */
@RunWith(AndroidJUnit4::class)
class LogRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "logs-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<LogDatabase>()
    private var now = 10 * DAY

    @After
    fun closeAndDelete() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }

    private fun room(maxEntries: Int = LogRepository.MAX_ENTRIES, file: Boolean = false): RoomLogRepository {
        val db = (if (file) LogDatabase.create(context, name) else LogDatabase.inMemory(context)).also { opened += it }
        return RoomLogRepository(db.logEventDao(), clock = { now }, maxEntries = maxEntries)
    }

    private fun repositories(maxEntries: Int = LogRepository.MAX_ENTRIES): List<LogRepository> =
        listOf(room(maxEntries), InMemoryLogRepository(clock = { now }, maxEntries = maxEntries))

    private fun entry(time: Long, event: LogEvent = LogEvent.Connected, details: Map<String, String> = emptyMap(), code: String? = null) =
        LogEntry(
            timeMillis = time,
            level = LogEntry.Level.Info,
            event = event,
            stage = ConnectionStage.Connected,
            code = code,
            profileId = "p1",
            details = details,
        )

    @Test
    fun entriesComeBackNewestFirstWithEverythingTheyRecorded() = runTest {
        for (repository in repositories()) {
            repository.append(listOf(entry(now - 2_000), entry(now - 1_000, LogEvent.Stopped, mapOf(LogDetail.BY to "user"))))

            val read = repository.entries.first()

            assertEquals(listOf(LogEvent.Stopped, LogEvent.Connected), read.map { it.event })
            assertEquals(mapOf(LogDetail.BY to "user"), read[0].details)
            assertEquals(ConnectionStage.Connected, read[0].stage)
            assertEquals("p1", read[0].profileId)
            assertTrue("ids are given on write", read.all { it.id > 0 })
        }
    }

    @Test
    fun whateverAWriterPassesIsSanitizedBeforeItIsKept() = runTest {
        for (repository in repositories()) {
            repository.append(
                listOf(
                    entry(
                        now,
                        details = mapOf(LogDetail.DETAIL to "proxy 203.0.113.7 refused alice:hunter2@x", "destination" to "example.com"),
                        code = "auth failed for alice",
                    ),
                ),
            )

            val kept = repository.entries.first().single()

            assertEquals("INTERNAL", kept.code)
            assertEquals(setOf(LogDetail.DETAIL), kept.details.keys)
            for (secret in listOf("203.0.113.7", "hunter2", "alice", "example.com")) {
                assertFalse(kept.toString(), kept.toString().contains(secret))
            }
        }
    }

    @Test
    fun theLogKeepsAtMostItsLimitDroppingTheOldest() = runTest {
        for (repository in repositories(maxEntries = 3)) {
            repository.append((1..5).map { entry(now - 10_000 + it) })

            assertEquals((5 downTo 3).map { now - 10_000 + it }, repository.entries.first().map { it.timeMillis })
        }
    }

    @Test
    fun entriesOlderThanTheRetentionGoOnTheNextWriteOrPurge() = runTest {
        for (repository in repositories()) {
            repository.append(listOf(entry(now - LogRepository.RETENTION_MILLIS + 1_000), entry(now)))
            assertEquals(2, repository.entries.first().size)

            now += 2_000
            repository.purge()
            assertEquals(listOf(now - 2_000), repository.entries.first().map { it.timeMillis })

            now += LogRepository.RETENTION_MILLIS
            repository.append(listOf(entry(now)))
            assertEquals(listOf(now), repository.entries.first().map { it.timeMillis })
        }
    }

    @Test
    fun clearRemovesEveryEntry() = runTest {
        for (repository in repositories()) {
            repository.append((1..10).map { entry(now - it) })

            repository.clear()

            assertEquals(emptyList<LogEntry>(), repository.entries.first())
        }
    }

    @Test
    fun clearLeavesNoEntryReadableInTheDatabaseFile() = runTest {
        val repository = room(file = true)
        repository.append((1..50).map { entry(now - it, details = mapOf(LogDetail.REASON to MARKER)) })
        assertTrue("the marker is written in the file before Clear", fileHolds(MARKER))

        repository.clear()
        opened.forEach { it.close() }
        opened.clear()

        assertFalse("secure_delete overwrites deleted entries and no journal keeps them", fileHolds(MARKER))
        assertEquals(0, room(file = true).entries.first().size)
    }

    @Test
    fun aRowOfAnEventThisVersionDoesNotKnowIsLeftOut() = runTest {
        val db = LogDatabase.inMemory(context).also { opened += it }
        db.logEventDao().insertAll(listOf(entry(now).toRow().copy(event = "from_a_newer_version"), entry(now).toRow()))

        assertEquals(listOf(LogEvent.Connected), RoomLogRepository(db.logEventDao(), clock = { now }).entries.first().map { it.event })
    }

    private fun fileHolds(text: String): Boolean {
        val path = context.getDatabasePath(name)
        val files = listOf(path, File(path.path + "-journal"), File(path.path + "-wal"))
        val needle = text.toByteArray()
        return files.filter { it.exists() }.any { file -> file.readBytes().let { bytes -> indexOf(bytes, needle) >= 0 } }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1_000
        const val MARKER = "marker-zebra-quokka"
    }
}
