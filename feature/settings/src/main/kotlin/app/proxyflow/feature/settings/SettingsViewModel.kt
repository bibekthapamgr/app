package app.proxyflow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ProtectionStore
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.ThemePreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings (boards E1, E1b) and the system kill switch guide (E2).
 *
 * @param killSwitch Android's kill switch as Android last reported it while ProxyFlow was connected (M8); null when
 *   it never did. It may have been changed in Android's settings since, unless [connected].
 * @param connected ProxyFlow is connected now, so Android was asked when the screen was shown.
 */
data class SettingsUiState(
    val loading: Boolean = true,
    val theme: ThemePreference = ThemePreference.System,
    val killSwitch: SystemKillSwitch? = null,
    val connected: Boolean = false,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    protection: ProtectionStore,
    private val connection: ConnectionController,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> =
        combine(settings.settings, protection.killSwitch, connection.state) { current, killSwitch, state ->
            SettingsUiState(
                loading = false,
                theme = current.theme,
                killSwitch = killSwitch,
                connected = state.status == ConnectionStatus.Connected,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState())

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    /** The screen is shown: while ProxyFlow is connected, Android is asked again (the user may have just changed it). */
    fun checkKillSwitch() = connection.checkSystemKillSwitch()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
