package app.proxyflow

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
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

/**
 * Every window's text, as one line per node with its bounds and whether it is
 * placed, for [app.proxyflow.core.testing.DumpOnFailureRule].
 */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.describeScreen(): String = buildString {
    onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEachIndexed { index, root ->
        appendLine("window $index ${root.boundsInWindow}")
        fun visit(node: SemanticsNode, depth: Int) {
            val label = listOfNotNull(
                node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text },
                node.config.getOrNull(SemanticsProperties.EditableText)?.text?.let { "edit=$it" },
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.let { "desc=$it" },
            ).joinToString(" ")
            if (label.isNotEmpty()) {
                val b = node.boundsInWindow
                appendLine(
                    "${"  ".repeat(
                        depth,
                    )}#${node.id} [${b.left},${b.top},${b.right},${b.bottom}] placed=${node.layoutInfo.isPlaced} $label",
                )
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 1)
    }
}
