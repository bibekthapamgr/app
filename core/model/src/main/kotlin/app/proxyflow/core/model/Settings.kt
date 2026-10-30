package app.proxyflow.core.model

/** App theme choice; System follows the phone's dark-theme setting. */
enum class ThemePreference { System, Light, Dark }

/** Settings stored on the phone (Preferences DataStore). */
data class UserSettings(val theme: ThemePreference = ThemePreference.System)
