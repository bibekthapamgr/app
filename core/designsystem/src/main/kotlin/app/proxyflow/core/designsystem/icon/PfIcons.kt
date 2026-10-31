package app.proxyflow.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * ProxyFlow's own 24 dp stroke icons, drawn for the M1 design file
 * (design/screens) and generated from its SVG paths. Tinted by the caller
 * through Icon(tint) / LocalContentColor, like any ImageVector.
 */
object PfIcons {
    val Logo: ImageVector by lazy {
        strokeIcon(
            "Logo",
            2.0f,
            "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z",
            "M8.5 12h7M13 9.5l2.5 2.5-2.5 2.5",
        )
    }
    val Shield: ImageVector by lazy {
        strokeIcon(
            "Shield",
            2.0f,
            "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z",
        )
    }
    val ShieldCheck: ImageVector by lazy {
        strokeIcon(
            "ShieldCheck",
            2.0f,
            "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z",
            "M9 12l2 2 4-4",
        )
    }
    val ShieldAlert: ImageVector by lazy {
        strokeIcon(
            "ShieldAlert",
            2.0f,
            "M12 3l7 3v6c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6z",
            "M12 9v4M12 16h.01",
        )
    }
    val Home: ImageVector by lazy {
        strokeIcon(
            "Home",
            2.0f,
            "M4 11l8-7 8 7",
            "M6 10v10h12V10",
        )
    }
    val Profiles: ImageVector by lazy {
        strokeIcon(
            "Profiles",
            2.0f,
            "M6 4h12a2 2 0 0 1 2 2v2a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-2a2 2 0 0 1 2 -2z",
            "M6 14h12a2 2 0 0 1 2 2v2a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-2a2 2 0 0 1 2 -2z",
            "M8 7h.01M8 17h.01",
        )
    }
    val Logs: ImageVector by lazy {
        strokeIcon(
            "Logs",
            2.0f,
            "M6 3h9l3 3v15H6z",
            "M9 11h6M9 15h6M9 7h3",
        )
    }
    val Settings: ImageVector by lazy {
        strokeIcon(
            "Settings",
            2.0f,
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
            "M12 2v3M12 19v3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M2 12h3M19 12h3M4.9 19.1L7 17M17 7l2.1-2.1",
        )
    }
    val Lock: ImageVector by lazy {
        strokeIcon(
            "Lock",
            2.0f,
            "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z",
            "M8 11V8a4 4 0 0 1 8 0v3",
        )
    }
    val Refresh: ImageVector by lazy {
        strokeIcon(
            "Refresh",
            2.0f,
            "M20 11a8 8 0 1 0-2.3 5.7",
            "M20 4v7h-7",
        )
    }
    val Block: ImageVector by lazy {
        strokeIcon(
            "Block",
            2.0f,
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M6 18L18 6",
        )
    }
    val ChevronRight: ImageVector by lazy {
        strokeIcon(
            "ChevronRight",
            2.0f,
            "M9 6l6 6-6 6",
        )
    }
    val ChevronDown: ImageVector by lazy {
        strokeIcon(
            "ChevronDown",
            2.0f,
            "M6 9l6 6 6-6",
        )
    }
    val Close: ImageVector by lazy {
        strokeIcon(
            "Close",
            2.0f,
            "M6 6l12 12M18 6L6 18",
        )
    }
    val Back: ImageVector by lazy {
        strokeIcon(
            "Back",
            2.0f,
            "M19 12H5M11 6l-6 6 6 6",
        )
    }
    val Key: ImageVector by lazy {
        strokeIcon(
            "Key",
            2.0f,
            "M4 15a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z",
            "M11 12l8-8M16 7l2 2",
        )
    }
    val Add: ImageVector by lazy {
        strokeIcon(
            "Add",
            2.0f,
            "M12 5v14M5 12h14",
        )
    }
    val AlertCircle: ImageVector by lazy {
        strokeIcon(
            "AlertCircle",
            2.0f,
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M12 8v5M12 16h.01",
        )
    }
    val Info: ImageVector by lazy {
        strokeIcon(
            "Info",
            2.0f,
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M12 11v5M12 8h.01",
        )
    }
    val Power: ImageVector by lazy {
        strokeIcon(
            "Power",
            2.0f,
            "M12 3v8",
            "M6.3 6.8a8 8 0 1 0 11.4 0",
        )
    }
    val Bolt: ImageVector by lazy {
        strokeIcon(
            "Bolt",
            2.0f,
            "M13 3L5 13h6l-1 8 8-10h-6z",
        )
    }
    val Delete: ImageVector by lazy {
        strokeIcon(
            "Delete",
            2.0f,
            "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3",
        )
    }
    val Warning: ImageVector by lazy {
        strokeIcon(
            "Warning",
            2.0f,
            "M12 4l9 16H3z",
            "M12 10v4M12 17h.01",
        )
    }
    val Edit: ImageVector by lazy {
        strokeIcon(
            "Edit",
            2.0f,
            "M4 20h4L19 9l-4-4L4 16z",
        )
    }
    val Eye: ImageVector by lazy {
        strokeIcon(
            "Eye",
            2.0f,
            "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
        )
    }
    val EyeOff: ImageVector by lazy {
        strokeIcon(
            "EyeOff",
            2.0f,
            "M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z",
            "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
            "M4 4l16 16",
        )
    }
    val Check: ImageVector by lazy {
        strokeIcon(
            "Check",
            2.0f,
            "M5 12l5 5 9-10",
        )
    }

    /** Two overlapping sheets: "Duplicate". */
    val Copy: ImageVector by lazy {
        strokeIcon(
            "Copy",
            2.0f,
            "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1 -1 1h-10a1 1 0 0 1 -1 -1v-10a1 1 0 0 1 1 -1z",
            "M5 15h-0a1 1 0 0 1 -1 -1v-10a1 1 0 0 1 1 -1h10a1 1 0 0 1 1 1v0",
        )
    }
    val MoreVert: ImageVector by lazy {
        strokeIcon(
            "MoreVert",
            2.0f,
            "M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z",
            "M11 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z",
            "M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z",
        )
    }
    val ExternalLink: ImageVector by lazy {
        strokeIcon(
            "ExternalLink",
            2.0f,
            "M14 4h6v6M20 4l-9 9",
            "M18 14v5H5V6h5",
        )
    }
    val Globe: ImageVector by lazy {
        strokeIcon(
            "Globe",
            2.0f,
            "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z",
            "M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18",
        )
    }
    val Phone: ImageVector by lazy {
        strokeIcon(
            "Phone",
            2.0f,
            "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z",
            "M11 18h2",
        )
    }
}

private fun strokeIcon(name: String, strokeWidth: Float, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = "PfIcons.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    for (d in paths) {
        builder.addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}
