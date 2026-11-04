package app.proxyflow.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.proxyflow.core.model.LogRetention
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App settings that survive restarts. */
interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun setTheme(theme: ThemePreference)

    /** How long the diagnostic log keeps entries (board E1). */
    suspend fun setLogRetention(retention: LogRetention)

    /** The first-launch pages were gone through (docs/m1/03 J1): they are not shown again. */
    suspend fun setOnboardingCompleted()
}

/**
 * Settings in Preferences DataStore. DataStore reads and writes on its own
 * I/O dispatcher, so callers on the main thread never block on disk.
 */
class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(
            theme = prefs[THEME]?.let(::themeFromKey) ?: ThemePreference.System,
            logRetention = prefs[LOG_RETENTION]?.let(::retentionFromKey) ?: LogRetention.Day,
            onboardingCompleted = prefs[ONBOARDING_COMPLETED] ?: false,
        )
    }

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[THEME] = theme.key }
    }

    override suspend fun setLogRetention(retention: LogRetention) {
        dataStore.edit { it[LOG_RETENTION] = retention.key }
    }

    override suspend fun setOnboardingCompleted() {
        dataStore.edit { it[ONBOARDING_COMPLETED] = true }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val LOG_RETENTION = stringPreferencesKey("log_retention")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        // Stable keys, independent of enum names, so renaming a constant never loses a setting.
        val ThemePreference.key: String
            get() = when (this) {
                ThemePreference.System -> "system"
                ThemePreference.Light -> "light"
                ThemePreference.Dark -> "dark"
            }

        val LogRetention.key: String
            get() = when (this) {
                LogRetention.Hour -> "1h"
                LogRetention.Day -> "24h"
                LogRetention.Week -> "7d"
            }

        fun themeFromKey(key: String): ThemePreference? = ThemePreference.entries.firstOrNull { it.key == key }

        // A value this version doesn't know (a later one wrote it) reads as the default.
        fun retentionFromKey(key: String): LogRetention? = LogRetention.entries.firstOrNull { it.key == key }
    }
}
