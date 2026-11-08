package app.proxyflow.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.db.LogDatabase
import app.proxyflow.core.data.db.LogEventDao
import app.proxyflow.core.data.db.LogEventEntity
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
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
        return RoomLogRepository(db, clock = { now }, maxEntries = maxEntries)
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
        val raw = entry(
            now,
            details = mapOf(LogDetail.DETAIL to "proxy 203.0.113.7 refused alice:hunter2@x", "destination" to "example.com"),
            code = "auth failed for alice",
        )
        val secrets = listOf("203.0.113.7", "hunter2", "alice", "example.com")
        // The rows as stored, not as reading gives them back (which sanitizes once more).
        val db = LogDatabase.inMemory(context).also { opened += it }
        RoomLogRepository(db, clock = { now }).append(listOf(raw))
        val row = db.logEventDao().observeLatest(10).first().single()
        assertEquals("INTERNAL", row.code)
        assertEquals("{\"${LogDetail.DETAIL}\"", row.details.substringBefore(':'))
        for (secret in secrets) assertFalse(row.toString(), row.toString().contains(secret))

        val memory = InMemoryLogRepository(clock = { now })
        memory.append(listOf(raw))
        val kept = memory.entries.first().single()
        assertEquals("INTERNAL", kept.code)
        assertEquals(setOf(LogDetail.DETAIL), kept.details.keys)
        for (secret in secrets) assertFalse(kept.toString(), kept.toString().contains(secret))
    }

    @Test
    fun anEntryPastTheRetentionNeverShowsEvenBeforeThePurge() = runTest {
        for (repository in repositories()) {
            repository.append(listOf(entry(now - LogRepository.RETENTION_MILLIS + 1_000), entry(now)))
            now += 2_000 // no write and no purge since

            assertEquals(listOf(now - 2_000), repository.entries.first().map { it.timeMillis })
        }
    }

    /** M9 (board E1, "Keep logs for"): a shorter time hides and deletes at once what it no longer allows; a longer one keeps more. */
    @Test
    fun theRetentionFollowsTheUsersSettingAndAShorterOneDeletesAtOnce() = runTest {
        for (repository in repositories()) {
            assertEquals(LogRepository.RETENTION_MILLIS, repository.retentionMillis.value)
            repository.setRetention(7 * DAY)
            repository.append(listOf(entry(now - 3 * DAY), entry(now - 2 * HOUR), entry(now - HOUR / 2)))
            assertEquals("a week keeps all three", 3, repository.entries.first().size)

            repository.setRetention(HOUR)

            assertEquals(HOUR, repository.retentionMillis.value)
            assertEquals("an hour keeps the last", listOf(now - HOUR / 2), repository.entries.first().map { it.timeMillis })
            if (repository is RoomLogRepository) {
                assertEquals("deleted, not only hidden", 1, opened.last().logEventDao().count())
            }
            repository.setRetention(7 * DAY)
            assertEquals("nothing deleted comes back", 1, repository.entries.first().size)
            repository.append(listOf(entry(now - 5 * DAY)))
            assertEquals("a week keeps a five-day-old entry again", 2, repository.entries.first().size)
        }
    }

    @Test
    fun entriesDatedFarAheadOfTheClockAreHiddenWhenReadAndDroppedByThePurge() = runTest {
        for (repository in repositories()) {
            val right = now
            val soon = right + LogRepository.FUTURE_MARGIN_MILLIS / 2
            now += DAY // written while the phone's clock was a day ahead
            val ahead = now
            repository.append(listOf(entry(ahead), entry(soon)))
            now = right // then the clock was set right, with no write or purge since

            assertEquals("hidden when read", listOf(soon), repository.entries.first().map { it.timeMillis })
            if (repository is RoomLogRepository) {
                assertEquals(
                    "still in the database until the purge",
                    2,
                    opened.last().logEventDao().count(),
                )
            }
            repository.purge()
            if (repository is RoomLogRepository) assertEquals("deleted by the purge", 1, opened.last().logEventDao().count())
            now += DAY // the day it was dated: dropped by the purge, it never shows
            assertFalse(repository.entries.first().any { it.timeMillis == ahead })
        }
    }

    @Test
    fun entriesOfEventsFromBeforeClearAreDroppedWhenTheyArriveAfterIt() = runTest {
        for (repository in repositories()) {
            val before = repository.generation
            repository.append(listOf(entry(now - 5_000)), before)
            repository.clear()
            // A batch the session's writer had on its way, and the minute's count gathered before Clear.
            repository.append(listOf(entry(now - 3_000), entry(now - 1)), before)
            now += 1_000
            repository.append(listOf(entry(now)), repository.generation)

            assertEquals(listOf(now), repository.entries.first().map { it.timeMillis })
        }
    }

    @Test
    fun aClockSetBackAfterClearLosesNothingWrittenAfterIt() = runTest {
        for (repository in repositories()) {
            val right = now
            now += DAY // the phone's clock a day ahead when the log is cleared
            repository.clear()
            now = right // then set right by the network

            repository.append(listOf(entry(now - 1_000), entry(now)))

            assertEquals(listOf(now, now - 1_000), repository.entries.first().map { it.timeMillis })
        }
    }

    @Test
    fun aBatchCheckedBeforeClearNeverLandsAfterIt() = runTest {
        val db = LogDatabase.inMemory(context).also { opened += it }
        val dao = Hooked(db.logEventDao())
        val repository = RoomLogRepository(db, dao, clock = { now })
        val checked = CountDownLatch(1)
        val release = CountDownLatch(1)
        dao.beforeInsert = {
            checked.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        // The session's writer has checked its batch against Clear and is about to insert it…
        val write = async(Dispatchers.IO) { repository.append(listOf(entry(now)), repository.generation) }
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        // …when Clear comes: it waits for the write's turn to end, then deletes what it wrote.
        val clear = async(Dispatchers.IO) { repository.clear() }
        Thread.sleep(300) // were they not taking turns, Clear would be over by now, before the insert
        release.countDown()
        write.await()
        clear.await()

        assertEquals(emptyList<LogEntry>(), repository.entries.first())
    }

    @Test
    fun aClearThatFailsLeavesEverythingAsItWas() = runTest {
        val db = LogDatabase.inMemory(context).also { opened += it }
        val dao = Hooked(db.logEventDao())
        val repository = RoomLogRepository(db, dao, clock = { now })
        repository.append(listOf(entry(now - 2_000)))
        val onItsWay = repository.generation // a batch the session's writer took before the tap on Clear

        dao.failDelete = true
        assertTrue("the disk is full", runCatching { repository.clear() }.isFailure)
        repository.append(listOf(entry(now - 1_000)), onItsWay)

        assertEquals(listOf(now - 1_000, now - 2_000), repository.entries.first().map { it.timeMillis })
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

        assertEquals(listOf(LogEvent.Connected), RoomLogRepository(db, clock = { now }).entries.first().map { it.event })
    }

    /** The database's own DAO, with a pause before each insert and a delete that can be made to fail. */
    private class Hooked(private val real: LogEventDao) : LogEventDao() {
        @Volatile var beforeInsert: () -> Unit = {}

        @Volatile var failDelete = false

        override fun observeLatest(limit: Int): Flow<List<LogEventEntity>> = real.observeLatest(limit)

        override suspend fun count(): Int = real.count()

        override suspend fun insertAll(rows: List<LogEventEntity>) = real.insertAll(rows)

        override suspend fun deleteOutside(cutoffMillis: Long, futureMillis: Long): Int = real.deleteOutside(cutoffMillis, futureMillis)

        override suspend fun trimTo(keep: Int): Int = real.trimTo(keep)

        override suspend fun deleteAll(): Int =
            if (failDelete) throw IllegalStateException("database or disk is full") else real.deleteAll()

        override suspend fun insertBounded(rows: List<LogEventEntity>, cutoffMillis: Long, futureMillis: Long, keep: Int) {
            beforeInsert()
            real.insertBounded(rows, cutoffMillis, futureMillis, keep)
        }
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
        const val HOUR = 60L * 60 * 1_000
        const val MARKER = "marker-zebra-quokka"
    }
}
