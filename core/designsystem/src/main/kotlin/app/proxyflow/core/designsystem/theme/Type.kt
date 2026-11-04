package app.proxyflow.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp

/** Type scale from design/tokens (system font, sizes in sp so they follow the user's font scale). */
private val Scale =
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
        )
    }

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)

/**
 * The scale, laid out in [layoutDirection] (defect M10-64). On a phone set to a right-to-left language the screens
 * mirror; ProxyFlow's words are English (until decision D8), and Compose would lay each sentence out right to left too —
 * its full stop at the line's start, a unit before its number ("MB 61.3"), a range turned round. So each text takes the
 * direction of its own words, as Android's own views do, left to right when it has none (numbers, "1–65535"), and its
 * lines keep to the layout's start edge, as the mirrored screen around them does: a text's own direction would
 * otherwise set its alignment, and an English line would sit at the far side of its space.
 */
internal fun proxyFlowTypography(layoutDirection: LayoutDirection): Typography = Scale.laidOut(layoutDirection)

/** monoSmall: addresses, error codes, log lines; laid out as the scale's styles are. */
val MonoSmall: TextStyle
    @Composable
    @ReadOnlyComposable
    get() = monoSmall(LocalLayoutDirection.current)

internal fun monoSmall(layoutDirection: LayoutDirection): TextStyle = Mono.laidOut(layoutDirection)

private fun startOf(layoutDirection: LayoutDirection): TextAlign =
    if (layoutDirection == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

private fun TextStyle.laidOut(layoutDirection: LayoutDirection): TextStyle =
    copy(textDirection = TextDirection.ContentOrLtr, textAlign = startOf(layoutDirection))

private fun Typography.laidOut(layoutDirection: LayoutDirection): Typography = copy(
    displayLarge = displayLarge.laidOut(layoutDirection),
    displayMedium = displayMedium.laidOut(layoutDirection),
    displaySmall = displaySmall.laidOut(layoutDirection),
    headlineLarge = headlineLarge.laidOut(layoutDirection),
    headlineMedium = headlineMedium.laidOut(layoutDirection),
    headlineSmall = headlineSmall.laidOut(layoutDirection),
    titleLarge = titleLarge.laidOut(layoutDirection),
    titleMedium = titleMedium.laidOut(layoutDirection),
    titleSmall = titleSmall.laidOut(layoutDirection),
    bodyLarge = bodyLarge.laidOut(layoutDirection),
    bodyMedium = bodyMedium.laidOut(layoutDirection),
    bodySmall = bodySmall.laidOut(layoutDirection),
    labelLarge = labelLarge.laidOut(layoutDirection),
    labelMedium = labelMedium.laidOut(layoutDirection),
    labelSmall = labelSmall.laidOut(layoutDirection),
)
