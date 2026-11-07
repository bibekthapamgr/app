package app.proxyflow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection

/** ProxyFlow colour roles Material 3 has no slot for (design/tokens). */
@Immutable
data class ProxyFlowExtendedColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val accentFill: Color,
    val onAccentFill: Color,
    val statusConnected: Color,
    val statusTransition: Color,
    val statusOff: Color,
    val statusError: Color,
)

internal val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/**
 * The app theme. Dynamic colour (Material You) is deliberately off: status
 * colours carry meaning and must stay predictable (docs/m1/05 §2). The type
 * scale is laid out in the screen's direction ([proxyFlowTypography]).
 */
@Composable
fun ProxyFlowTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors
    val layoutDirection = LocalLayoutDirection.current
    val typography = remember(layoutDirection) { proxyFlowTypography(layoutDirection) }
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = typography,
            shapes = ProxyFlowShapes,
            content = content,
        )
    }
}

/** Access to the extended roles: `ProxyFlowTheme.colors.warning`. */
object ProxyFlowTheme {
    val colors: ProxyFlowExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}
