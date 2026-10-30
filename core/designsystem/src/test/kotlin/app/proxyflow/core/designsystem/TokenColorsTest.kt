package app.proxyflow.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.proxyflow.core.designsystem.theme.DarkColorScheme
import app.proxyflow.core.designsystem.theme.DarkExtendedColors
import app.proxyflow.core.designsystem.theme.LightColorScheme
import app.proxyflow.core.designsystem.theme.LightExtendedColors
import app.proxyflow.core.designsystem.theme.ProxyFlowExtendedColors
import java.io.File
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colours compiled into the app must be exactly the design tokens, and the
 * required pairs must meet WCAG 2.2 AA *as compiled* (docs/m1/05 §6). Fails
 * when someone edits Color.kt by hand or changes a token without regenerating.
 */
class TokenColorsTest {
    private val tokens: JsonObject =
        Json.parseToJsonElement(File(System.getProperty("proxyflow.tokens")).readText()).jsonObject

    private val schemeRoles: Map<String, (ColorScheme) -> Color> = mapOf(
        "primary" to { it.primary }, "onPrimary" to { it.onPrimary },
        "primaryContainer" to { it.primaryContainer }, "onPrimaryContainer" to { it.onPrimaryContainer },
        "inversePrimary" to { it.inversePrimary },
        "secondary" to { it.secondary }, "onSecondary" to { it.onSecondary },
        "secondaryContainer" to { it.secondaryContainer }, "onSecondaryContainer" to { it.onSecondaryContainer },
        "tertiary" to { it.tertiary }, "onTertiary" to { it.onTertiary },
        "tertiaryContainer" to { it.tertiaryContainer }, "onTertiaryContainer" to { it.onTertiaryContainer },
        "background" to { it.background }, "onBackground" to { it.onBackground },
        "surface" to { it.surface }, "onSurface" to { it.onSurface },
        "surfaceVariant" to { it.surfaceVariant }, "onSurfaceVariant" to { it.onSurfaceVariant },
        "inverseSurface" to { it.inverseSurface }, "inverseOnSurface" to { it.inverseOnSurface },
        "error" to { it.error }, "onError" to { it.onError },
        "errorContainer" to { it.errorContainer }, "onErrorContainer" to { it.onErrorContainer },
        "outline" to { it.outline }, "outlineVariant" to { it.outlineVariant }, "scrim" to { it.scrim },
        "surfaceBright" to { it.surfaceBright }, "surfaceDim" to { it.surfaceDim },
        "surfaceContainer" to { it.surfaceContainer }, "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "surfaceContainerHighest" to { it.surfaceContainerHighest }, "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainerLowest" to { it.surfaceContainerLowest },
    )

    private val extendedRoles: Map<String, (ProxyFlowExtendedColors) -> Color> = mapOf(
        "warning" to { it.warning }, "onWarning" to { it.onWarning },
        "warningContainer" to { it.warningContainer }, "onWarningContainer" to { it.onWarningContainer },
        "accentFill" to { it.accentFill }, "onAccentFill" to { it.onAccentFill },
        "statusConnected" to { it.statusConnected }, "statusTransition" to { it.statusTransition },
        "statusOff" to { it.statusOff }, "statusError" to { it.statusError },
    )

    @Test
    fun compiledColoursAreExactlyTheTokens() {
        for ((mode, scheme, extended) in listOf(
            Triple("light", LightColorScheme, LightExtendedColors),
            Triple("dark", DarkColorScheme, DarkExtendedColors),
        )) {
            val modeTokens = tokens.getValue("color").jsonObject.getValue(mode).jsonObject
            assertEquals("every token role is compiled ($mode)", modeTokens.keys, schemeRoles.keys + extendedRoles.keys)
            schemeRoles.forEach { (role, get) -> assertEquals("$mode.$role", token(mode, role), hex(get(scheme))) }
            extendedRoles.forEach { (role, get) -> assertEquals("$mode.$role", token(mode, role), hex(get(extended))) }
        }
    }

    @Test
    fun requiredPairsMeetWcagAaAsCompiled() {
        // Same pairs and thresholds as design/tools/check_contrast.py.
        val pairs = listOf(
            "onBackground" to "background" to 4.5, "onSurface" to "surface" to 4.5,
            "onSurfaceVariant" to "surface" to 4.5, "onSurfaceVariant" to "background" to 4.5,
            "onSurfaceVariant" to "surfaceVariant" to 4.5, "primary" to "background" to 4.5,
            "primary" to "surface" to 4.5, "onPrimary" to "primary" to 4.5,
            "onPrimaryContainer" to "primaryContainer" to 4.5, "onSecondary" to "secondary" to 4.5,
            "onSecondaryContainer" to "secondaryContainer" to 4.5, "error" to "background" to 4.5,
            "error" to "surface" to 4.5, "onError" to "error" to 4.5,
            "onErrorContainer" to "errorContainer" to 4.5, "warning" to "background" to 4.5,
            "onWarningContainer" to "warningContainer" to 4.5, "onAccentFill" to "accentFill" to 4.5,
            "statusConnected" to "background" to 4.5, "statusTransition" to "background" to 4.5,
            "statusOff" to "background" to 4.5, "statusError" to "background" to 4.5,
            "outline" to "surface" to 3.0, "outline" to "background" to 3.0,
            "onSurface" to "surfaceContainer" to 4.5, "onSurfaceVariant" to "surfaceContainer" to 4.5,
            "onSurface" to "surfaceContainerHigh" to 4.5, "onSurfaceVariant" to "surfaceContainerHighest" to 4.5,
            "inverseOnSurface" to "inverseSurface" to 4.5, "onTertiaryContainer" to "tertiaryContainer" to 4.5,
        )
        for ((scheme, extended) in listOf(LightColorScheme to LightExtendedColors, DarkColorScheme to DarkExtendedColors)) {
            fun colour(role: String) = schemeRoles[role]?.invoke(scheme) ?: extendedRoles.getValue(role)(extended)
            for ((roles, min) in pairs) {
                val r = contrast(colour(roles.first), colour(roles.second))
                assertTrue("${roles.first} on ${roles.second}: %.2f < $min".format(r), r >= min)
            }
        }
    }

    private fun token(mode: String, role: String): String {
        var value = tokens.getValue("color").jsonObject.getValue(mode).jsonObject.getValue(role).jsonObject
            .getValue("\$value").jsonPrimitive.content
        while (value.startsWith("{")) {
            var node: JsonObject = tokens
            value.removeSurrounding("{", "}").split('.').forEach { node = node.getValue(it).jsonObject }
            value = node.getValue("\$value").jsonPrimitive.content
        }
        return value.uppercase()
    }

    private fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

    private fun luminance(c: Color): Double {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
