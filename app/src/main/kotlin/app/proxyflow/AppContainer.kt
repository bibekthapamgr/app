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
import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.DataStoreSettingsRepository
import app.proxyflow.core.data.InMemoryLogRepository
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.data.ProfileStorage
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.data.UnavailableConnectionController
import app.proxyflow.core.data.UnavailableConnectionTester
import app.proxyflow.core.data.crypto.AndroidKeystoreSecretKeyStore
import app.proxyflow.core.data.crypto.SecretKeyStore
import app.proxyflow.feature.home.HomeViewModel
import app.proxyflow.feature.logs.LogsViewModel
import app.proxyflow.feature.profiles.AddEditProxyViewModel
import app.proxyflow.feature.profiles.ProfilesViewModel
import app.proxyflow.feature.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency injection (ADR-0008): the one place that decides which
 * implementation each screen gets. Later milestones swap implementations here
 * (the engine-backed tester in M4, the VPN-backed controller in M5) without
 * touching the screens.
 *
 * @param credentialKeys where the per-profile credential keys live: the Android
 *   Keystore in the app; JVM tests, where it does not exist, pass software keys.
 */
class AppContainer(context: Context, credentialKeys: SecretKeyStore = AndroidKeystoreSecretKeyStore()) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // A damaged settings file falls back to defaults instead of crashing at start.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { appContext.preferencesDataStoreFile(SETTINGS_FILE) },
    )

    private val profiles = ProfileStorage.repository(appContext, settingsStore, credentialKeys)

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(settingsStore)
    val profileRepository: ProfileRepository = profiles
    val logRepository: LogRepository = InMemoryLogRepository()
    val connectionController: ConnectionController = UnavailableConnectionController()
    val connectionTester: ConnectionTester = UnavailableConnectionTester()

    init {
        // Keys left behind by a delete that was interrupted (ADR-0006). A failure here only delays that clean-up.
        appScope.launch { runCatching { profiles.deleteOrphanedKeys() } }
    }

    /** Creates every screen's ViewModel; navigation passes it to each destination. */
    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(profileRepository, connectionController) }
        initializer { ProfilesViewModel(profileRepository) }
        initializer { AddEditProxyViewModel(createSavedStateHandle(), profileRepository, connectionTester) }
        initializer { SettingsViewModel(settingsRepository) }
        initializer { LogsViewModel(logRepository) }
    }

    private companion object {
        const val SETTINGS_FILE = "settings"
    }
}
