package app.proxyflow.core.vpn

import app.proxyflow.core.data.InMemoryLogRepository
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session's writer to the diagnostic log (ADR-0012): in order, off the session's thread, and sites' failures counted per minute. */
class DiagnosticsRecorderTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val now = AtomicLong(NOW)

    @After
    fun tearDown() = scope.cancel()

    private fun entry(n: Int) = LogEntry(timeMillis = NOW + n, level = LogEntry.Level.Info, event = LogEvent.Connected, profileId = "p$n")

    @Test
    fun entriesAreWrittenInOrder() {
        val log = InMemoryLogRepository(clock = { now.get() })
        val recorder = DiagnosticsRecorder(log, scope, clock = { now.get() })

        repeat(250) { recorder.record(entry(it)) }

        val written = awaitEntries(log, 250)
        assertEquals((249 downTo 0).map { "p$it" }, written.map { it.profileId })
    }

    @Test
    fun aBacklogPastItsCapacityDropsTheOldestUnwrittenEntries() {
        val release = CountDownLatch(1)
        val delegate = InMemoryLogRepository(clock = { now.get() })
        val log = object : LogRepository by delegate {
            override suspend fun append(entries: List<LogEntry>, generation: Long) {
                release.await(5, TimeUnit.SECONDS) // the disk is slow: the writer is stuck on its first batch
                delegate.append(entries, generation)
            }
        }
        val recorder = DiagnosticsRecorder(log, scope, clock = { now.get() }, capacity = 10)

        recorder.record(entry(0))
        Thread.sleep(100) // the writer took entry 0 and waits
        repeat(30) { recorder.record(entry(it + 1)) }
        release.countDown()

        val written = awaitEntries(log, 11)
        assertEquals("the first batch and the newest ten", listOf("p0") + (21..30).map { "p$it" }, written.reversed().map { it.profileId })
    }

    @Test
    fun sitesFailuresAreCountedPerCodeAndProfileAndWrittenOncePerWindow() {
        val log = InMemoryLogRepository(clock = { now.get() })
        val recorder = DiagnosticsRecorder(log, scope, clock = { now.get() }, windowMillis = 60_000)

        repeat(3) { recorder.connectionFailed("TARGET_UNREACHABLE", "p1") }
        now.addAndGet(5_000)
        recorder.connectionFailed("TARGET_REFUSED", "p1")
        recorder.connectionFailed("TARGET_UNREACHABLE", "p2")
        recorder.flushProblems()

        val written = awaitEntries(log, 3)
        assertTrue(written.all { it.event == LogEvent.ConnectionProblems && it.level == LogEntry.Level.Warning })
        val byKey = written.associateBy { it.profileId to it.code }
        assertEquals("3", byKey.getValue("p1" to "TARGET_UNREACHABLE").details[LogDetail.COUNT])
        assertEquals("60", byKey.getValue("p1" to "TARGET_UNREACHABLE").details[LogDetail.WINDOW_S])
        assertEquals("dated when the first failed", NOW, byKey.getValue("p1" to "TARGET_UNREACHABLE").timeMillis)
        assertEquals(NOW + 5_000, byKey.getValue("p1" to "TARGET_REFUSED").timeMillis)
        assertEquals("1", byKey.getValue("p2" to "TARGET_UNREACHABLE").details[LogDetail.COUNT])

        recorder.flushProblems()
        Thread.sleep(100)
        assertEquals("counted once", 3, runBlocking { log.entries.first() }.size)
    }

    @Test
    fun whatHappenedBeforeTheLogWasClearedGoesWithItEvenWhenItIsWrittenAfter() {
        val release = CountDownLatch(1)
        val delegate = InMemoryLogRepository(clock = { now.get() })
        val log = object : LogRepository by delegate {
            override suspend fun append(entries: List<LogEntry>, generation: Long) {
                release.await(5, TimeUnit.SECONDS) // the writer is held while the log is cleared
                delegate.append(entries, generation)
            }
        }
        val recorder = DiagnosticsRecorder(log, scope, clock = { now.get() })
        recorder.record(entry(1))
        Thread.sleep(100) // the writer took entry 1 and waits
        recorder.record(entry(2)) // queued
        recorder.connectionFailed("TARGET_UNREACHABLE", "p1")
        recorder.connectionFailed("TARGET_REFUSED", "p2")

        runBlocking { log.clear() }
        now.addAndGet(1_000)
        repeat(5) { recorder.connectionFailed("TARGET_UNREACHABLE", "p1") }
        recorder.flushProblems()
        recorder.record(entry(3))
        release.countDown()

        awaitEntries(log, 2)
        Thread.sleep(100) // nothing else comes
        val written = runBlocking { log.entries.first() }
        assertEquals("only what happened after Clear: $written", 2, written.size)
        assertEquals("p3", written.single { it.event == LogEvent.Connected }.profileId)
        val counted = written.single { it.event == LogEvent.ConnectionProblems }
        assertEquals("p1" to "TARGET_UNREACHABLE", counted.profileId to counted.code)
        assertEquals("the failures after Clear, counted afresh", "5", counted.details[LogDetail.COUNT])
        assertEquals("dated when the first of them failed", NOW + 1_000, counted.timeMillis)
    }

    /**
     * Engine threads report failures at any time: one that read the log's generation just before a Clear and wrote its
     * count just after another thread's, counted after the Clear, never replaces that count with one of before.
     */
    @Test
    fun aFailureCountedJustAfterAClearIsNeverLostToOneOfBefore() {
        val delegate = InMemoryLogRepository(clock = { now.get() })
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var slow: Thread? = null
        val log = object : LogRepository by delegate {
            override val generation: Long
                get() {
                    val generation = delegate.generation
                    if (Thread.currentThread() == slow) {
                        paused.countDown() // read, then the thread loses the CPU
                        resume.await(5, TimeUnit.SECONDS)
                    }
                    return generation
                }
        }
        val recorder = DiagnosticsRecorder(log, scope, clock = { now.get() })
        val first = Thread { recorder.connectionFailed("TARGET_UNREACHABLE", "p1") } // just before Clear
        slow = first
        first.start()
        assertTrue(paused.await(5, TimeUnit.SECONDS))
        runBlocking { log.clear() }
        now.addAndGet(1_000)
        val second = Thread { recorder.connectionFailed("TARGET_UNREACHABLE", "p1") } // after Clear
        second.start()
        // It finishes, or it waits for the first thread's turn: either way the first may go on now.
        val deadline = System.currentTimeMillis() + 5_000
        while (second.state != Thread.State.BLOCKED && second.state != Thread.State.TERMINATED) {
            check(System.currentTimeMillis() < deadline) { "the second report neither ran nor waited" }
            Thread.sleep(5)
        }
        resume.countDown()
        first.join()
        second.join()
        now.addAndGet(1_000)
        recorder.connectionFailed("TARGET_UNREACHABLE", "p1") // and one more after Clear
        recorder.flushProblems()

        val counted = awaitEntries(log, 1).single { it.event == LogEvent.ConnectionProblems }
        assertEquals("the two failures after Clear", "2", counted.details[LogDetail.COUNT])
        assertEquals("dated when the first of them failed", NOW + 1_000, counted.timeMillis)
    }

    @Test
    fun aLogThatCannotBeWrittenNeverStopsTheWriter() {
        val failures = AtomicInteger()
        val delegate = InMemoryLogRepository(clock = { now.get() })
        val log = object : LogRepository by delegate {
            override suspend fun append(entries: List<LogEntry>, generation: Long) {
                if (failures.getAndIncrement() == 0) throw IllegalStateException("disk full")
                delegate.append(entries, generation)
            }
        }
        val recorder = DiagnosticsRecorder(log, scope, clock = { now.get() })

        recorder.record(entry(1))
        Thread.sleep(200)
        recorder.record(entry(2))

        assertEquals(listOf("p2"), awaitEntries(log, 1).map { it.profileId })
    }

    private fun awaitEntries(log: LogRepository, count: Int): List<LogEntry> {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val entries = runBlocking { log.entries.first() }
            if (entries.size >= count) return entries
            check(System.currentTimeMillis() < deadline) { "timed out with ${entries.size} of $count entries" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
