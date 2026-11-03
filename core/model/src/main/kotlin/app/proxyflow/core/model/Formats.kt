package app.proxyflow.core.model

import java.util.Locale

/** How amounts of data and lengths of time read on the screens and in the log (M7). */
object Formats {
    private val UNITS = listOf("B", "kB", "MB", "GB", "TB", "PB")

    /**
     * [bytes] in decimal units, as Android's own settings show amounts of data: "999 B",
     * "1.2 kB", "34.5 MB", "120 MB" — one decimal below 100 of a unit, none from there.
     */
    fun bytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1_000) return "${bytes.coerceAtLeast(0)} B"
        var value = bytes / 1_000.0
        var unit = 1
        // Rounded as shown: 999,950 bytes read "1.0 MB", never "1000 kB".
        while (value >= 999.95 && unit < UNITS.lastIndex) {
            value /= 1_000
            unit++
        }
        val number = String.format(locale, if (value < 99.95) "%.1f" else "%.0f", value)
        return "$number ${UNITS[unit]}"
    }

    /** A session's length as a clock: "00:12:47", "27:03:00". */
    fun clock(millis: Long): String {
        val seconds = (millis / 1_000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    /** A short wait or set-up time in seconds with one decimal: "0.4 s", "12.0 s". */
    fun seconds(millis: Long, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f s", millis.coerceAtLeast(0) / 1_000.0)
}
