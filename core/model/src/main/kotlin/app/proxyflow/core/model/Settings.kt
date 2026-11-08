package app.proxyflow.core.model

/** App theme choice; System follows the phone's dark-theme setting. */
enum class ThemePreference { System, Light, Dark }

/**
 * How long the diagnostic log keeps an entry (board E1, "Keep logs for"; ADR-0014). One day is the M1 default; the log
 * never keeps more than its entry limit, whatever this says.
 */
enum class LogRetention(val millis: Long) {
    Hour(60L * 60 * 1_000),
    Day(24L * 60 * 60 * 1_000),
    Week(7L * 24 * 60 * 60 * 1_000),
}

/**
 * Settings stored on the phone (Preferences DataStore).
 *
 * @param onboardingCompleted the first-launch pages (A1–A3) were gone through once (docs/m1/03 J1); they are not shown
 *   again.
 */
data class UserSettings(
    val theme: ThemePreference = ThemePreference.System,
    val logRetention: LogRetention = LogRetention.Day,
    val onboardingCompleted: Boolean = false,
)
