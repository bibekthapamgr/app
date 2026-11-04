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
 * its full stop at the line's start, a unit before its number ("MB 61.3"), a range turned round. So ProxyFlow's own text
 * is laid out left to right, as English is written, whatever the screen's direction, and its lines keep to the layout's
 * start edge, as the mirrored screen around them does: a paragraph's direction sets its alignment otherwise, and an
 * English line would sit at the far side of its space. What the user wrote — a profile's name, what they type — is not
 * ProxyFlow's English: it takes its own direction ([inItsOwnDirection], [isolated], and the fields'
 * [typedInItsOwnDirection]). Translations (D8) will need each language's own direction instead.
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
    copy(textDirection = TextDirection.Ltr, textAlign = startOf(layoutDirection))

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

/**
 * For text the user wrote shown on its own (a profile's name): the direction of its own words, whatever their script —
 * "مكتب 2" reads as written — still at the layout's start edge.
 */
fun TextStyle.inItsOwnDirection(): TextStyle = copy(textDirection = TextDirection.Content)

/**
 * For a text field's input: the direction of what is typed, and its lines at that direction's start, as Android's own
 * fields do — so typing in a right-to-left script edits as it should, and a space typed at the end of English shows.
 */
fun TextStyle.typedInItsOwnDirection(): TextStyle = copy(textDirection = TextDirection.Content, textAlign = TextAlign.Start)

/**
 * [text] the user wrote, set inside one of ProxyFlow's sentences: Unicode's first-strong isolate, so it keeps its own
 * direction and leaves the sentence's order alone ("Switch to مكتب 2?").
 */
fun isolated(text: String): String = "\u2068$text\u2069"
