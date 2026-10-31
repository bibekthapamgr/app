package app.proxyflow

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import org.junit.rules.TestRule

// Helpers shared by the app-level UI tests.

/**
 * The app is ready to use: its first frame is drawn (it waits for the saved theme, which
 * DataStore reads off the main thread) and the screen has stopped loading (Home reads the
 * profiles from Room and the active one from DataStore, also off the main thread).
 */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.waitForApp() {
    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty() }
    waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Loading").fetchSemanticsNodes().isEmpty() }
}

/**
 * [text] once it is on screen. Profiles come from Room and the active profile from
 * DataStore, both on background threads, so a screen shows them a moment after it opens.
 */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.waitForText(text: String): SemanticsNodeInteraction {
    waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithText(text)
}

/** A bottom-navigation item (Role.Tab), so it never matches a screen title with the same text. */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.tab(label: String): SemanticsNodeInteraction =
    onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

/** The proxy form's Save in the top app bar, which is always on screen. */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.topBarSave(): SemanticsNodeInteraction =
    onNode(hasText("Save") and !hasAnyAncestor(hasScrollAction()))

/** The proxy form's Save at the end of the form, which is usually below the fold. */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.formEndSave(): SemanticsNodeInteraction =
    onNode(hasText("Save") and hasAnyAncestor(hasScrollAction()))

/**
 * System Back, as the gesture or the back button would send it. The screen is
 * first allowed to settle: a person cannot press Back in the same frame as the
 * tap or keystroke before it, and a Back that arrives before that input is
 * drawn reaches the back handlers of the previous frame (the form's "Discard
 * changes?" handler is still off, NavHost's is not yet on).
 */
internal fun <A : ComponentActivity> AndroidComposeTestRule<out TestRule, A>.pressBack() {
    waitForIdle()
    runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
    waitForIdle()
}

/**
 * Records what navigation and the activity lifecycle do from now on, for
 * [describeNavigation]. Call after [waitForApp].
 */
internal fun AndroidComposeTestRule<out TestRule, MainActivity>.recordNavigation(events: MutableList<String>) {
    runOnUiThread {
        events += "activity ${System.identityHashCode(activity)}"
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += "activity $event" })
        activity.navController?.addOnDestinationChangedListener { controller, destination, _ ->
            events += "now at ${destination.route?.substringAfterLast('.')}; back stack ${controller.backStackNames()}"
        }
    }
}

/** The back stack (with each entry's lifecycle state) and the recorded events. */
internal fun AndroidComposeTestRule<out TestRule, MainActivity>.describeNavigation(events: List<String>): String {
    val controller = activity.navController ?: return "no NavController"
    val entries = controller.currentBackStack.value.joinToString { entry ->
        "${entry.destination.route?.substringAfterLast('.')}#${entry.id.take(4)}:${entry.lifecycle.currentState}"
    }
    return "activity ${System.identityHashCode(activity)}\nback stack: $entries\nevents:\n" + events.joinToString("\n")
}

private fun NavController.backStackNames() = currentBackStack.value.joinToString { it.destination.route?.substringAfterLast('.') ?: "?" }

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
                val indent = "  ".repeat(depth)
                val b = node.boundsInWindow
                appendLine("$indent#${node.id} [${b.left},${b.top},${b.right},${b.bottom}] placed=${node.layoutInfo.isPlaced} $label")
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 1)
    }
}
