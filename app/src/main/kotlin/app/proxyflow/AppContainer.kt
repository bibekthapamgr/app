package app.proxyflow

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.DataStoreSettingsRepository
import app.proxyflow.core.data.InMemoryLogRepository
import app.proxyflow.core.data.InMemoryProfileRepository
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.data.UnavailableConnectionController
import app.proxyflow.feature.home.HomeViewModel
import app.proxyflow.feature.logs.LogsViewModel
import app.proxyflow.feature.profiles.AddEditProxyViewModel
import app.proxyflow.feature.profiles.ProfilesViewModel
import app.proxyflow.feature.settings.SettingsViewModel

/**
 * Manual dependency injection (ADR-0008): the one place that decides which
 * implementation each screen gets. Later milestones swap implementations here
 * (Room + Keystore profiles in M3, the VPN-backed controller in M5) without
 * touching the screens.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val settingsStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // A damaged settings file falls back to defaults instead of crashing at start.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { appContext.preferencesDataStoreFile(SETTINGS_FILE) },
    )

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(settingsStore)
    val profileRepository: ProfileRepository = InMemoryProfileRepository()
    val logRepository: LogRepository = InMemoryLogRepository()
    val connectionController: ConnectionController = UnavailableConnectionController()

    /** Creates every screen's ViewModel; navigation passes it to each destination. */
    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(profileRepository, connectionController) }
        initializer { ProfilesViewModel(profileRepository) }
        initializer { AddEditProxyViewModel(createSavedStateHandle(), profileRepository) }
        initializer { SettingsViewModel(settingsRepository) }
        initializer { LogsViewModel(logRepository) }
    }

    private companion object {
        const val SETTINGS_FILE = "settings"
    }
}
