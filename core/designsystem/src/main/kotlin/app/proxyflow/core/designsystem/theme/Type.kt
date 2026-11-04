package app.proxyflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

/**
 * Type scale from design/tokens (system font, sizes in sp so they follow the user's font scale). Every style takes its
 * direction from its own text, its first strong character, as Android's own views do: on a phone set to a right-to-left
 * language the screens mirror, while an English sentence stays left to right — its full stop at its end, a unit after its
 * number. Compose's default follows the layout's direction instead (defect M10-64).
 */
internal val ProxyFlowTypography =
    Typography().let { base ->
        base.copy(
            displaySmall = base.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
            headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
            titleLarge = base.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
            titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
            bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
            labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        ).inTheirOwnDirection()
    }

/** monoSmall: addresses, error codes, log lines. */
val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
    textDirection = TextDirection.Content,
)

/** Every style of the scale with [TextDirection.Content]. */
internal fun Typography.inTheirOwnDirection(): Typography = copy(
    displayLarge = displayLarge.copy(textDirection = TextDirection.Content),
    displayMedium = displayMedium.copy(textDirection = TextDirection.Content),
    displaySmall = displaySmall.copy(textDirection = TextDirection.Content),
    headlineLarge = headlineLarge.copy(textDirection = TextDirection.Content),
    headlineMedium = headlineMedium.copy(textDirection = TextDirection.Content),
    headlineSmall = headlineSmall.copy(textDirection = TextDirection.Content),
    titleLarge = titleLarge.copy(textDirection = TextDirection.Content),
    titleMedium = titleMedium.copy(textDirection = TextDirection.Content),
    titleSmall = titleSmall.copy(textDirection = TextDirection.Content),
    bodyLarge = bodyLarge.copy(textDirection = TextDirection.Content),
    bodyMedium = bodyMedium.copy(textDirection = TextDirection.Content),
    bodySmall = bodySmall.copy(textDirection = TextDirection.Content),
    labelLarge = labelLarge.copy(textDirection = TextDirection.Content),
    labelMedium = labelMedium.copy(textDirection = TextDirection.Content),
    labelSmall = labelSmall.copy(textDirection = TextDirection.Content),
)
