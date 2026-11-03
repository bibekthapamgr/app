package app.proxyflow.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.RoomLogRepository
import app.proxyflow.core.data.db.LogDatabase
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.feature.logs.LogsUiState
import app.proxyflow.feature.logs.exportText
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The diagnostic log on a device (ADR-0012): what the session writes to the app's own database
 * names each attempt's stage and code and nothing secret — no credential, server name or address —
 * there and in what Export hands over; a session is logged from its request to its summary; and
 * Clear leaves nothing of the entries readable in the database file.
 */
@RunWith(AndroidJUnit4::class)
class LogsEndToEndTest {
    private val app = App()
    private lateinit var env: E2eConfig

    @Before
    fun setUp() {
        env = E2eConfig.load()
        TestTrust.labCa = null
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
    }

    @After
    fun tearDown() {
        app.disconnectAndSettle()
        app.deleteAllProfiles()
    }

    /** A refused sign-in: the log says at which stage and with which code, and holds nothing secret. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aRefusedSignInIsLoggedWithItsStageAndNothingSecret() {
        val password = "wrong-${random()}"
        val profile = app.save("Wrong SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, password)

        assertEquals("AUTH_FAILED", app.connectExpectingFailure(profile.id).error?.code)

        val failed = app.awaitLog("the failed attempt") { it.event == LogEvent.ConnectFailed && it.profileId == profile.id }
        assertEquals(ConnectionStage.ProxyHandshake, failed.stage)
        assertEquals("AUTH_FAILED", failed.code)
        assertEquals(LogEntry.Level.Error, failed.level)
        val mine = entriesOf(profile.id)
        assertEquals(
            "the stages it reached before failing",
            listOf(ConnectionStage.Starting, ConnectionStage.VpnPermission, ConnectionStage.ProxyHandshake),
            mine.filter { it.event == LogEvent.StageReached }.map { it.stage },
        )
        // What Export hands over, searched by the profile's name as a user would: the search itself stays out of it too.
        val shown = LogsUiState(loading = false, entries = mine.reversed(), total = mine.size, query = profile.name)
        val export = exportText(app.context, shown, appVersion = "e2e", zone = ZoneId.systemDefault())
        // The database file itself, not what reading it gives back (which is sanitized once more); a failure never
        // prints the secret, so it can't reach logcat either.
        val secrets = listOf(password, env.socksUser, env.socksPassword, env.proxyHost, profile.name)
        secrets.forEachIndexed { i, secret ->
            assertFalse("the log's database file holds secret #$i", fileHolds(secret))
            assertFalse("the log as read holds secret #$i", mine.toString().contains(secret))
            assertFalse("the export holds secret #$i", export.contains(secret))
        }
        assertTrue(export, export.contains("search applied"))
        Log.i(
            LAB_TAG,
            "log checked ${mine.size} entries of a refused sign-in: stage ${failed.stage?.key} code ${failed.code}, nothing secret",
        )
    }

    /** A session from its request to its summary: each stage, Connected with its set-up time, the disconnect, and what it carried. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aSessionIsLoggedFromItsRequestToItsSummary() {
        val profile = app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)
        app.connect(profile.id)
        val line = Lines.next("logged-session")
        Lines.sent(line, env.destinationName, env.destinationPort)
        assertEquals("REPLY PROXYFLOW-DEST $line", FromShell.tcp(env.destinationName, env.destinationPort, line))
        app.disconnectAndSettle()

        val summary = app.awaitLog("the session's summary") { it.event == LogEvent.SessionSummary && it.profileId == profile.id }
        // From the last request: an attempt the emulator's uplink dropped is tried again (App.connect).
        val all = entriesOf(profile.id)
        val mine = all.subList(all.indexOfLast { it.event == LogEvent.ConnectRequested }, all.size)
        val events = listOf(LogEvent.ConnectRequested, LogEvent.Connected, LogEvent.Stopped, LogEvent.SessionSummary)
        assertEquals(events, mine.map { it.event }.filter { it in events })
        assertEquals(
            listOf(ConnectionStage.Starting, ConnectionStage.VpnPermission, ConnectionStage.ProxyHandshake, ConnectionStage.Authenticated),
            mine.filter { it.event == LogEvent.StageReached }.map { it.stage },
        )
        assertTrue("$mine", checkNotNull(mine.single { it.event == LogEvent.Connected }.details[LogDetail.SETUP_MS]).toLong() > 0)
        for (key in listOf(LogDetail.DURATION_MS, LogDetail.BYTES_UP, LogDetail.BYTES_DOWN, LogDetail.OPENED)) {
            assertTrue("$key in $summary", checkNotNull(summary.details[key]).toLong() > 0)
        }
        Log.i(LAB_TAG, "log checked a session of ${mine.size} entries: ${summary.details}")
    }

    /** Clear removes every entry, and nothing of them stays readable in the database file (secure_delete, no WAL). */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun clearingTheLogLeavesNothingReadableOnThePhone() {
        val marker = "marker zebra quokka ${random().take(8)}"
        runBlocking {
            app.logs.append(
                listOf(
                    LogEntry(
                        timeMillis = System.currentTimeMillis(),
                        level = LogEntry.Level.Info,
                        event = LogEvent.Connected,
                        details = mapOf(LogDetail.REASON to marker),
                    ),
                ),
            )
        }
        app.awaitLog("the marker entry") { it.details[LogDetail.REASON] == marker }
        assertTrue("the entry is in the database file before Clear", fileHolds(marker))

        // What Clear relies on, as the app's own connection to the database has it: deleted content is overwritten, and
        // the rollback journal is truncated after each write instead of a write-ahead log keeping old pages.
        val settings = (app.logs as RoomLogRepository).storageSettings()
        assertEquals("$settings", "1", settings["secure_delete"])
        assertEquals("$settings", "truncate", settings["journal_mode"])

        runBlocking { app.logs.clear() }

        assertEquals(emptyList<LogEntry>(), runBlocking { app.logs.entries.first() })
        assertFalse("nothing of the entry is readable in the database file after Clear", fileHolds(marker))
        Log.i(LAB_TAG, "log cleared: no entry left, nothing readable in ${LogDatabase.NAME} ($settings)")
    }

    /** The profile's entries, oldest first. */
    private fun entriesOf(profileId: String): List<LogEntry> =
        runBlocking { app.logs.entries.first() }.filter { it.profileId == profileId }.sortedWith(compareBy({ it.timeMillis }, { it.id }))

    private fun fileHolds(text: String): Boolean {
        val database = app.context.getDatabasePath(LogDatabase.NAME)
        val needle = text.toByteArray()
        return listOf(database, File("${database.path}-journal"), File("${database.path}-wal"))
            .filter { it.exists() }
            .any { file ->
                file.readBytes().let { bytes ->
                    (0..bytes.size - needle.size).any { i ->
                        needle.indices.all {
                            bytes[i + it] ==
                                needle[it]
                        }
                    }
                }
            }
    }

    private fun random() = UUID.randomUUID().toString().replace("-", "").take(12)

    private companion object {
        const val TEST_TIMEOUT_MS = 90_000L
    }
}
