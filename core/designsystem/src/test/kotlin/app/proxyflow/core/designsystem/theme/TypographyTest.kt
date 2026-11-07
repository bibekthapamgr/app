package app.proxyflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyTest {
    /**
     * Defect M10-64: laid out right to left, as on a phone set to Arabic, Hebrew, Persian or Urdu, every style's text is
     * still laid out left to right, as English is written — its full stop at its end, a unit after its number, a range
     * in order — and keeps to the layout's start edge, the right one there, as the mirrored screen does. Left to right,
     * nothing changes. The right-to-left screenshots show it laid out.
     */
    @Test
    fun everyStyleIsLaidOutAsEnglishAtTheLayoutsStart() {
        for ((layout, start) in listOf(LayoutDirection.Ltr to TextAlign.Left, LayoutDirection.Rtl to TextAlign.Right)) {
            val styles = stylesOf(proxyFlowTypography(layout)) + ("monoSmall" to monoSmall(layout))
            assertEquals(16, styles.size)
            styles.forEach { (name, style) ->
                assertEquals("$name ($layout)", TextDirection.Ltr, style.textDirection)
                assertEquals("$name ($layout)", start, style.textAlign)
            }
        }
    }

    /**
     * What the user wrote is not ProxyFlow's English (defect M10-83): a name shown alone takes the direction of its first
     * letter, left to right when it has none (M10-91), still at the layout's start; what is typed takes its own direction
     * and that direction's start, as Android's fields do, but an address or a credential is typed left to right from the
     * left edge, as Android's password fields are (M10-91); and a name set inside a sentence is a first-strong isolate,
     * which keeps its order and the sentence's.
     */
    @Test
    fun theUsersOwnTextTakesItsOwnDirection() {
        for ((layout, start) in listOf(LayoutDirection.Ltr to TextAlign.Left, LayoutDirection.Rtl to TextAlign.Right)) {
            val name = proxyFlowTypography(layout).titleMedium.inItsOwnDirection()
            assertEquals(TextDirection.ContentOrLtr, name.textDirection)
            assertEquals("a name stays at the layout's start ($layout)", start, name.textAlign)
            val typed = proxyFlowTypography(layout).bodyLarge.typedInItsOwnDirection()
            assertEquals(TextDirection.Content, typed.textDirection)
            assertEquals(TextAlign.Start, typed.textAlign)
            val address = proxyFlowTypography(layout).bodyLarge.typedLeftToRight()
            assertEquals(TextDirection.Ltr, address.textDirection)
            assertEquals(TextAlign.Start, address.textAlign)
        }
        assertEquals("\u2068مكتب 2\u2069", isolated("مكتب 2"))
    }

    /**
     * A name cannot end its isolate early (M10-94): its own embeddings, overrides and isolates are left out, so a pop
     * followed by a right-to-left override cannot turn the rest of ProxyFlow's sentence round. Other text is kept.
     */
    @Test
    fun aNamesOwnDirectionalFormattingIsLeftOut() {
        assertEquals("\u2068Office!\u2069", isolated("Office\u2069\u202E!"))
        val formatting = ('\u202A'..'\u202E') + ('\u2066'..'\u2069')
        assertEquals("\u2068ab\u2069", isolated(formatting.joinToString("", prefix = "a", postfix = "b")))
        // Marks are not formatting that nests: they stay, as do the letters and spaces around them.
        assertEquals("\u2068a\u200Fb \u200Ec\u2069", isolated("a\u200Fb \u200Ec"))
    }

    /** Only the direction and the alignment change: the sizes are still design/tokens'. */
    @Test
    fun theScaleKeepsItsSizes() {
        for (layout in LayoutDirection.entries) {
            val type = proxyFlowTypography(layout)
            assertEquals(36f, type.displaySmall.fontSize.value)
            assertEquals(24f, type.headlineSmall.fontSize.value)
            assertEquals(16f, type.bodyLarge.fontSize.value)
            assertEquals(14f, type.bodyMedium.fontSize.value)
            assertEquals(12f, type.labelMedium.fontSize.value)
            assertEquals(12f, monoSmall(layout).fontSize.value)
        }
    }

    private fun stylesOf(type: Typography): List<Pair<String, TextStyle>> = with(type) {
        listOf(
            "displayLarge" to displayLarge,
            "displayMedium" to displayMedium,
            "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge,
            "headlineMedium" to headlineMedium,
            "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge,
            "titleMedium" to titleMedium,
            "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge,
            "bodyMedium" to bodyMedium,
            "bodySmall" to bodySmall,
            "labelLarge" to labelLarge,
            "labelMedium" to labelMedium,
            "labelSmall" to labelSmall,
        )
    }
}
