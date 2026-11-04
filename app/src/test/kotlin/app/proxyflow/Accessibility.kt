package app.proxyflow

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * The accessibility rules every screen is held to (M9; docs/m1/04 §1), checked on what TalkBack would reach: each control
 * has a name it reads out — its text, or a content description — and a touch target of at least 48 × 48 dp, Android's
 * minimum. Run on every screen the screenshot tests draw, in each state they draw it in, dialogs and sheets included.
 */
internal fun ComposeTestRule.accessibilityProblems(screen: String): List<String> {
    val density = density
    return onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { node ->
        val name = node.spokenName()
        val target = node.touchTarget()
        val width: Dp = with(density) { target.width.toDp() }
        val height: Dp = with(density) { target.height.toDp() }
        when {
            name.isBlank() -> "$screen: a control TalkBack would read without a name (${node.config})"
            width < MIN_TOUCH_TARGET - ROUNDING || height < MIN_TOUCH_TARGET - ROUNDING ->
                "$screen: \"$name\" is $width × $height to touch, under the 48 dp touch target"
            else -> null
        }
    }
}

/**
 * The area a touch reaches the control in: its whole layout node. Material's controls draw smaller than they can be
 * touched — an icon button 40 dp, a chip 32 dp, a text button 40 dp tall — and reserve the rest of the 48 dp around
 * them (minimumInteractiveComponentSize), which the layout node includes and the click's own bounds don't.
 */
private fun SemanticsNode.touchTarget(): IntSize = IntSize(maxOf(size.width, layoutInfo.width), maxOf(size.height, layoutInfo.height))

/** What TalkBack reads for a control: its own and its children's words, merged as the control merges them. */
private fun SemanticsNode.spokenName(): String = listOfNotNull(
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
    config.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
    config.getOrNull(SemanticsProperties.EditableText)?.text,
).joinToString(" ").trim()

private val MIN_TOUCH_TARGET = 48.dp

/** Layout sizes are whole pixels. */
private val ROUNDING = 0.5.dp
