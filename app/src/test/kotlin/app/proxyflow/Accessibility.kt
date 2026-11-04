package app.proxyflow

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The accessibility rules every screen is held to (M9; docs/m1/04 §1), checked on what TalkBack would reach: each control
 * has a name it reads out — its text, or a content description — and is at least 48 × 48 dp, Android's minimum touch
 * target. Run on every screen the screenshot tests draw, in each state they draw it in, dialogs and sheets included.
 */
internal fun ComposeTestRule.accessibilityProblems(screen: String): List<String> {
    val density = density
    return onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
        val name = node.spokenName()
        val width: Dp = with(density) { node.size.width.toDp() }
        val height: Dp = with(density) { node.size.height.toDp() }
        when {
            name.isBlank() -> "$screen: a control TalkBack would read without a name (${node.config})"
            width < MIN_TOUCH_TARGET - ROUNDING || height < MIN_TOUCH_TARGET - ROUNDING ->
                "$screen: \"$name\" is $width × $height, under the 48 dp touch target"
            else -> null
        }
    }
}

/** What TalkBack reads for a control: its own and its children's words, merged as the control merges them. */
private fun SemanticsNode.spokenName(): String = listOfNotNull(
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
    config.getOrNull(SemanticsProperties.EditableText)?.text,
).joinToString(" ").trim()

private val MIN_TOUCH_TARGET = 48.dp

/** Layout sizes are whole pixels. */
private val ROUNDING = 0.5.dp
