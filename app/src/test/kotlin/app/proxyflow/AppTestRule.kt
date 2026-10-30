package app.proxyflow

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.rules.TestRule

// Helpers shared by the app-level UI tests.

/** The first frame waits for the saved theme, which DataStore reads off the main thread. */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.waitForApp() {
    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty() }
}

/** A bottom-navigation item (Role.Tab), so it never matches a screen title with the same text. */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.tab(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

/** System Back, as the gesture or the back button would send it. */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.pressBack() {
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}
