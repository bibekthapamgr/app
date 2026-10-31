package app.proxyflow.feature.logs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.LogEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyLogSaysWhatWillAppearAndOffersNoSettingsYet() {
        compose.setContent { ProxyFlowTheme { LogsScreen(LogsUiState(loading = false)) } }
        compose.onNodeWithText("No events yet").assertIsDisplayed()
        compose.onNodeWithText("never include passwords", substring = true).assertIsDisplayed()
        // Log settings arrive with logging itself (M7); until then there is nothing to link to.
        compose.onNodeWithText("Log settings").assertDoesNotExist()
    }

    @Test
    fun entriesShowTheirLevelInWordsNotOnlyColour() {
        val entry = LogEntry(1, timeMillis = 0, level = LogEntry.Level.Error, code = "AUTH_FAILED", message = "Proxy rejected the password")
        compose.setContent { ProxyFlowTheme { LogsScreen(LogsUiState(loading = false, entries = listOf(entry))) } }
        compose.onNodeWithText("Proxy rejected the password").assertIsDisplayed()
        compose.onNodeWithText("Error · AUTH_FAILED", substring = true).assertIsDisplayed()
    }
}
