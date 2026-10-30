package app.proxyflow.feature.logs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyLogPointsToLogSettings() {
        var opened = 0
        compose.setContent { ProxyFlowTheme { LogsScreen(LogsUiState(loading = false), onOpenSettings = { opened++ }) } }
        compose.onNodeWithText("No events yet").assertIsDisplayed()
        compose.onNodeWithText("Log settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun entriesShowTheirLevelInWordsNotOnlyColour() {
        val entry = LogEntry(1, timeMillis = 0, level = LogEntry.Level.Error, code = "AUTH_FAILED", message = "Proxy rejected the password")
        compose.setContent { ProxyFlowTheme { LogsScreen(LogsUiState(loading = false, entries = listOf(entry)), onOpenSettings = {}) } }
        compose.onNodeWithText("Proxy rejected the password").assertIsDisplayed()
        compose.onNodeWithText("Error · AUTH_FAILED", substring = true).assertIsDisplayed()
    }
}
