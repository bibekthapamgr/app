package app.proxyflow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.InMemoryLogRepository
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.ProtectionStore
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.LogRetention
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.ThemePreference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings (boards E1, E1b) and the system kill switch guide (E2).
 *
 * @param logRetention how long the diagnostic log keeps entries (ADR-0014).
 * @param killSwitch Android's kill switch as Android last reported it while ProxyFlow was connected (M8); null when
 *   it never did. It may have been changed in Android's settings since, unless [connected].
 * @param connected ProxyFlow is connected now, so Android was asked when the screen was shown.
 * @param cleared what the last "Clear logs" did, until the screen has said so.
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val theme: ThemePreference = ThemePreference.System,
    val killSwitch: SystemKillSwitch? = null,
    val connected: Boolean = false,
    val logRetention: LogRetention = LogRetention.Day,
    val cleared: LogsCleared? = null,
)

/** What "Clear logs" did: every entry is gone, or the log couldn't be cleared and stays as it was. */
enum class LogsCleared { Done, Failed }

/**
 * @param logs the diagnostic log, which "Clear logs" empties (the Logs screen's own Clear does the same).
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    protection: ProtectionStore,
    private val connection: ConnectionController,
    private val logs: LogRepository = InMemoryLogRepository(),
) : ViewModel() {
    private val cleared = MutableStateFlow<LogsCleared?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(settings.settings, protection.killSwitch, connection.state, cleared) { current, killSwitch, state, clearing ->
            SettingsUiState(
                loading = false,
                theme = current.theme,
                killSwitch = killSwitch,
                connected = state.status == ConnectionStatus.Connected,
                logRetention = current.logRetention,
                cleared = clearing,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    /** Kept in the settings; the app applies it to the log at once (AppContainer), deleting what it no longer allows. */
    fun setLogRetention(retention: LogRetention) {
        viewModelScope.launch { settings.setLogRetention(retention) }
    }

    /**
     * "Clear logs", confirmed: every entry kept on the phone goes, and the screen says so. A log that can't be cleared now
     * stays as it was, and the screen says that instead.
     */
    fun clearLogs() {
        cleared.value = null
        viewModelScope.launch {
            cleared.value = try {
                logs.clear()
                LogsCleared.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LogsCleared.Failed
            }
        }
    }

    /** The screen has said what [SettingsUiState.cleared] reports. */
    fun onClearShown() {
        cleared.value = null
    }

    /** The screen is shown: while ProxyFlow is connected, Android is asked again (the user may have just changed it). */
    fun checkKillSwitch() = connection.checkSystemKillSwitch()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
