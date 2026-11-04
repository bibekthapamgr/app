package app.proxyflow.core.designsystem.theme

import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyTest {
    /**
     * Every style of the theme takes its direction from its own text (defect M10-64): laid out right to left, as on a
     * phone set to Arabic, Hebrew, Persian or Urdu, an English sentence stays left to right — its full stop at its end, a
     * unit after its number — while a right-to-left text reads right to left. The right-to-left screenshots show it.
     */
    @Test
    fun everyStyleTakesItsDirectionFromItsText() {
        val styles = with(ProxyFlowTypography) {
            mapOf(
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
                "MonoSmall" to MonoSmall,
            )
        }
        styles.forEach { (name, style) -> assertEquals(name, TextDirection.Content, style.textDirection) }
    }

    /** The direction is the only change: the sizes are still design/tokens'. */
    @Test
    fun theScaleKeepsItsSizes() {
        assertEquals(36f, ProxyFlowTypography.displaySmall.fontSize.value)
        assertEquals(16f, ProxyFlowTypography.bodyLarge.fontSize.value)
        assertEquals(14f, ProxyFlowTypography.bodyMedium.fontSize.value)
        assertEquals(12f, MonoSmall.fontSize.value)
    }
}
