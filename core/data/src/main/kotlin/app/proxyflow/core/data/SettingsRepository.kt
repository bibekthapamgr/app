package app.proxyflow.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** App settings that survive restarts. */
interface SettingsRepository {
    val settings: Flow<UserSettings>

    suspend fun setTheme(theme: ThemePreference)
}

/**
 * Settings in Preferences DataStore. DataStore reads and writes on its own
 * I/O dispatcher, so callers on the main thread never block on disk.
 */
class DataStoreSettingsRepository(private val dataStore: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<UserSettings> = dataStore.data.map { prefs ->
        UserSettings(theme = prefs[THEME]?.let(::themeFromKey) ?: ThemePreference.System)
    }

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[THEME] = theme.key }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")

        // Stable keys, independent of enum names, so renaming a constant never loses a setting.
        val ThemePreference.key: String
            get() = when (this) {
                ThemePreference.System -> "system"
                ThemePreference.Light -> "light"
                ThemePreference.Dark -> "dark"
            }

        fun themeFromKey(key: String): ThemePreference? = ThemePreference.entries.firstOrNull { it.key == key }
    }
}
