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
import app.proxyflow.core.data.DataStoreProtectionStore
import app.proxyflow.core.data.DataStoreSettingsRepository
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.LogStorage
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.data.ProfileStorage
import app.proxyflow.core.data.ProtectionStore
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.data.crypto.AndroidKeystoreSecretKeyStore
import app.proxyflow.core.data.crypto.SecretKeyStore
import app.proxyflow.core.vpn.RetryPolicy
import app.proxyflow.core.vpn.VpnRuntime
import app.proxyflow.feature.home.HomeViewModel
import app.proxyflow.feature.logs.LogsViewModel
import app.proxyflow.feature.onboarding.OnboardingViewModel
import app.proxyflow.feature.profiles.AddEditProxyViewModel
import app.proxyflow.feature.profiles.ProfilesViewModel
import app.proxyflow.feature.settings.SettingsViewModel
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manual dependency injection (ADR-0008): the one place that decides which
 * implementation each screen gets. Later milestones swap implementations here
 * without touching the screens: the VPN-backed connection controller arrived in
 * M5 (ADR-0010), the engine-backed connection tester in M9 (ADR-0014; what it
 * contacts follows the pre-flight check, open decision D6).
 *
 * @param credentialKeys where the per-profile credential keys live: the Android
 *   Keystore in the app; JVM tests, where it does not exist, pass software keys.
 * @param proxyTrust replaces the platform trust manager that checks TLS proxies' certificates;
 *   only the device tests pass one, which also trusts their run's test CA (ADR-0011). The app
 *   never does: [ProxyFlowApplication] builds this container with the default.
 * @param retry how the connection reconnects on its own (ADR-0012); only the device tests pass
 *   one, with shorter waits. The app always uses [RetryPolicy]'s default.
 */
class AppContainer(
    context: Context,
    credentialKeys: SecretKeyStore = AndroidKeystoreSecretKeyStore(),
    proxyTrust: (() -> X509TrustManager)? = null,
    retry: () -> RetryPolicy = { RetryPolicy() },
) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val settingsStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // A damaged settings file falls back to defaults instead of crashing at start.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { appContext.preferencesDataStoreFile(SETTINGS_FILE) },
    )

    private val profiles = ProfileStorage.repository(appContext, settingsStore, credentialKeys)

    /**
     * What outlives the process about the protection (M8, ADR-0013): Android's kill switch as last reported, the
     * connected marker, an interrupted connection. Its own file, so a damaged one never costs the settings.
     */
    val protectionStore: ProtectionStore = DataStoreProtectionStore(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { appContext.preferencesDataStoreFile(PROTECTION_FILE) },
        ),
    )

    val settingsRepository: SettingsRepository = DataStoreSettingsRepository(settingsStore)
    val profileRepository: ProfileRepository = profiles

    /** The diagnostic log (ADR-0012), in its own database; the session writes it, the Logs screen reads and clears it. */
    val logRepository: LogRepository = LogStorage.repository(appContext)

    /** The VPN layer: the session, and the service's way back to it (ProxyFlowApplication is its provider). */
    val vpnRuntime =
        VpnRuntime(appContext, profiles, appScope, proxyTrust, logs = logRepository, retry = retry, protection = protectionStore)
    val connectionController: ConnectionController = vpnRuntime.controller
    val connectionTester: ConnectionTester = vpnRuntime.tester

    init {
        // Keys left behind by a delete that was interrupted (ADR-0006). A failure here only delays that clean-up.
        appScope.launch { runCatching { profiles.deleteOrphanedKeys() } }
        appScope.launch(Dispatchers.IO) {
            // How long the log keeps entries, as the user set it (board E1, ADR-0014), before anything is purged: a purge
            // with the default first would delete what a longer setting keeps (M10). A settings file that can't be read,
            // or isn't read in time, keeps the default.
            withTimeoutOrNull(SETTINGS_WAIT_MS) { settingsRepository.settings.catch {}.firstOrNull() }
                ?.let { runCatching { logRepository.setRetention(it.logRetention.millis) } }
            // Entries past the log's bounds go at start and every hour while the process runs, not only at the next
            // write (ADR-0012; docs/m1/03 J10: retention applied at least daily).
            launch {
                while (true) {
                    runCatching { logRepository.purge() }
                    delay(LOG_PURGE_INTERVAL_MS)
                }
            }
            // Each change of the setting, which deletes at once what a shorter time no longer allows.
            settingsRepository.settings
                .map { it.logRetention }
                .distinctUntilChanged()
                .catch {}
                .collect { runCatching { logRepository.setRetention(it.millis) } }
        }
    }

    /** Creates every screen's ViewModel; navigation passes it to each destination. */
    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(profileRepository, connectionController, protectionStore) }
        initializer { ProfilesViewModel(profileRepository, connectionController, connectionTester) }
        initializer { AddEditProxyViewModel(createSavedStateHandle(), profileRepository, connectionTester, connectionController) }
        initializer { SettingsViewModel(settingsRepository, protectionStore, connectionController, logRepository) }
        initializer { LogsViewModel(logRepository, profileRepository) }
        initializer { OnboardingViewModel(settingsRepository) }
    }

    private companion object {
        const val SETTINGS_FILE = "settings"
        const val PROTECTION_FILE = "protection"
        const val LOG_PURGE_INTERVAL_MS = 60 * 60 * 1_000L

        /** How long the first purge waits for the user's retention setting at most (a settings file read at start). */
        const val SETTINGS_WAIT_MS = 5_000L
    }
}
