package app.proxyflow.feature.logs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val calls = mutableListOf<String>()
    private val actions = LogsActions(
        onFilter = { calls += "filter $it" },
        onQuery = { calls += "query $it" },
        onSelect = { calls += "select ${it.id}" },
        onCloseDetail = { calls += "close" },
        onCopy = { calls += "copy" },
        onExport = { calls += "export" },
        onCopyEntry = { calls += "copy ${it.id}" },
        onClear = { calls += "clear" },
        onConfirmClear = { calls += "confirm clear" },
        onDismissClear = { calls += "dismiss clear" },
    )

    private val failed = LogEntry(
        id = 7,
        timeMillis = TIME,
        level = LogEntry.Level.Error,
        event = LogEvent.ConnectFailed,
        stage = ConnectionStage.ProxyHandshake,
        code = "AUTH_FAILED",
        profileId = "4f6c2a1e-9b7d-4c3a-8e2f-0a1b2c3d4e5f",
        details = mapOf(LogDetail.DETAIL to "proxy rejected the username or password (HTTP 407)"),
    )

    @Test
    fun anEmptyLogSaysWhatWillAppearAndWhatItNeverHolds() {
        show(LogsUiState(loading = false))
        compose.onNodeWithText("No events yet").assertIsDisplayed()
        compose.onNodeWithText("never include passwords, server or site names, or page content", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy log").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Export log").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear log").assertIsNotEnabled()
    }

    @Test
    fun entriesSayWhatHappenedAndTheirLevelStageAndCodeInWords() {
        show(LogsUiState(loading = false, entries = listOf(failed), total = 1))
        compose.onNodeWithText("Couldn't connect").assertIsDisplayed()
        compose.onNodeWithText("Error · AUTH_FAILED · Proxy handshake", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Kept on this phone for 24 hours", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Couldn't connect").performClick()
        compose.onNodeWithText("Errors").performClick()
        compose.onNodeWithText("Search codes, events or profiles").performTextInput("auth")
        compose.onNodeWithContentDescription("Copy log").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Export log").performClick()
        compose.onNodeWithContentDescription("Clear log").performClick()
        assertEquals(listOf("select 7", "filter ${LogFilter.Errors}", "query auth", "copy", "export", "clear"), calls)
    }

    /** M9: the screen says how long entries are kept as the user set it in Settings (1 hour, 24 hours, 7 days). */
    @Test
    fun theRetentionSaidIsTheOneSet() {
        var state by mutableStateOf(LogsUiState(loading = false, entries = listOf(failed), total = 1, retentionMillis = 7 * DAY))
        compose.setContent { ProxyFlowTheme { LogsScreen(state, actions = LogsActions(), zone = utc, today = LocalDate.of(2026, 11, 3)) } }
        compose.onNodeWithText("Kept on this phone for 7 days", substring = true).assertIsDisplayed()
        state = state.copy(retentionMillis = HOUR)
        compose.onNodeWithText("Kept on this phone for 1 hour,", substring = true).assertIsDisplayed()
        state = LogsUiState(loading = false, retentionMillis = HOUR)
        compose.onNodeWithText("Logs stay on this phone for 1 hour and never include", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFilterOrSearchWithoutMatchesSaysSo() {
        show(LogsUiState(loading = false, entries = emptyList(), total = 3, filter = LogFilter.Blocked, query = "tls"))
        compose.onNodeWithText("No matching events").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy log").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear log").assertIsEnabled()
        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals(listOf("query "), calls)
    }

    /** Board F3: everything the entry holds, what to do about its code, and Copy for this entry only. */
    @Test
    fun theDetailSheetShowsTheWholeEntryAndWhatToDo() {
        show(
            LogsUiState(
                loading = false,
                entries = listOf(failed),
                total = 1,
                selected = failed,
                profileNames = mapOf(failed.profileId!! to "Tokyo residential"),
            ),
        )
        // The sheet scrolls on a small screen: each line is brought into view first.
        fun line(text: String) = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        line("2026-11-03 10:15:30.250 +00:00")
        line("Proxy handshake")
        line("AUTH_FAILED")
        line("Tokyo residential")
        line("detail = proxy rejected the username or password (HTTP 407)")
        line("What you can do")
        line("Check the username and password with your proxy provider, then edit the profile.")
        line("Copy this event").performClick()
        assertEquals(listOf("copy 7"), calls)
    }

    @Test
    fun clearingIsConfirmedFirst() {
        show(LogsUiState(loading = false, entries = listOf(failed), total = 1, confirmingClear = true))
        compose.onNodeWithText("Clear the log?").assertIsDisplayed()
        compose.onNodeWithText("This removes every event kept on this phone. It can't be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear").performClick()
        assertEquals(listOf("dismiss clear", "confirm clear"), calls)
    }

    /** What Copy and Export hand over: a header, then the entries with stable keys and nothing secret (ADR-0012). */
    @Test
    fun theExportedTextHasAHeaderAndSanitizedEntries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val leaky = failed.copy(
            id = 8,
            timeMillis = TIME + 1_000,
            details = mapOf(LogDetail.DETAIL to "alice:hunter2@203.0.113.7:1080 proxy.example.com"),
        )
        val state = LogsUiState(loading = false, entries = listOf(leaky, failed), total = 5, filter = LogFilter.Errors, query = "auth")

        val text = exportText(context, state, appVersion = "0.7.0 (7)", zone = utc, now = Instant.ofEpochMilli(TIME + 60_000))

        val lines = text.lines()
        assertEquals("ProxyFlow diagnostic log", lines[0])
        assertTrue(lines[1], lines[1].startsWith("App 0.7.0 (7) · Android "))
        assertEquals("Exported 2026-11-03 10:16:30.250 +00:00", lines[2])
        assertEquals(
            "the search's text is left out: it may be a profile's name",
            "Events: 2 of 5 · filter: Errors · search applied",
            lines[3],
        )
        assertEquals("Never includes passwords, server or site names, addresses or page content.", lines[4])
        val entries = lines.drop(6).filter { it.isNotBlank() }
        assertEquals(2, entries.size)
        assertTrue(
            entries[0],
            entries[0].startsWith("2026-11-03 10:15:30.250 +00:00  ERROR    connect_failed  stage=proxy_handshake  code=AUTH_FAILED"),
        )
        assertTrue(entries[0], entries[0].contains("profile=4f6c2a1e"))
        for (secret in listOf("alice", "hunter2", "203.0.113.7", "proxy.example.com", "Tokyo")) assertFalse(secret, text.contains(secret))
    }

    /** A site's code says one thing for a single connection and another for the proxy check before connecting. */
    @Test
    fun whatToDoDependsOnWhereTheCodeCameFrom() {
        val check = failed.copy(code = "TARGET_UNREACHABLE", details = emptyMap())
        val sites = check.copy(
            id = 9,
            event = LogEvent.ConnectionProblems,
            level = LogEntry.Level.Warning,
            stage = ConnectionStage.Connected,
        )
        val permission = check.copy(id = 10, code = "VPN_PERMISSION_DENIED", stage = ConnectionStage.VpnPermission)
        val hints = mapOf(
            check to "couldn't open a test connection to connectivitycheck.gstatic.com",
            sites to "Other connections are unaffected.",
            permission to "allow Android's VPN request",
        )
        var shown by mutableStateOf(check)
        compose.setContent {
            ProxyFlowTheme { LogsScreen(LogsUiState(loading = false, entries = listOf(shown), total = 1, selected = shown), zone = utc) }
        }
        for ((entry, hint) in hints) {
            shown = entry
            compose.onNodeWithText(hint, substring = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun anEntryOfAnotherDaySaysWhichDay() {
        compose.setContent {
            ProxyFlowTheme {
                LogsScreen(LogsUiState(loading = false, entries = listOf(failed), total = 1), zone = utc, today = LocalDate.of(2026, 11, 4))
            }
        }
        compose.onNodeWithText("11/3/26", substring = true).assertIsDisplayed()
    }

    private fun show(state: LogsUiState) {
        compose.setContent { ProxyFlowTheme { LogsScreen(state, actions = actions, zone = utc, today = LocalDate.of(2026, 11, 3)) } }
    }

    private companion object {
        /** 2026-11-03 10:15:30.250 UTC. */
        const val TIME = 1_793_700_000_000L + 930_250L
        const val HOUR = 60L * 60 * 1_000
        const val DAY = 24 * HOUR
    }
}
