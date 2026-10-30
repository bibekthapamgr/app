package app.proxyflow

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.testing.DumpOnFailureRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** TEMPORARY (removed before merge): MainActivity without the shared test helpers. */
@RunWith(AndroidJUnit4::class)
class BackDiagnosticMainActivityTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.describeScreen() }

    @Test
    fun w4_mainActivityWithoutTheSharedHelpers() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add proxy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Add a proxy to get started").assertExists()
        compose.onNodeWithText("Server").assertDoesNotExist()
    }
}
