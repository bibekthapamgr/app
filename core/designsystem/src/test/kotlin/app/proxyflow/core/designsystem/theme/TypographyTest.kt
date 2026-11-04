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
     * Defect M10-64: laid out right to left, as on a phone set to Arabic, Hebrew, Persian or Urdu, every style's text
     * takes the direction of its own words — so an English sentence stays left to right, its full stop at its end and a
     * unit after its number — left to right when it has none (a range, "1–65535"), and keeps to the layout's start edge,
     * the right one there, as the mirrored screen does. Left to right, nothing changes. The right-to-left screenshots
     * show it laid out.
     */
    @Test
    fun everyStyleTakesItsWordsDirectionAndTheLayoutsStart() {
        for ((layout, start) in listOf(LayoutDirection.Ltr to TextAlign.Left, LayoutDirection.Rtl to TextAlign.Right)) {
            val styles = stylesOf(proxyFlowTypography(layout)) + ("monoSmall" to monoSmall(layout))
            assertEquals(16, styles.size)
            styles.forEach { (name, style) ->
                assertEquals("$name ($layout)", TextDirection.ContentOrLtr, style.textDirection)
                assertEquals("$name ($layout)", start, style.textAlign)
            }
        }
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
