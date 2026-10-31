package app.proxyflow.feature.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface HomeUiState {
    data object Loading : HomeUiState

    /** Board B1. */
    data object NoProfile : HomeUiState

    /**
     * Boards B2–B4, B6b, B6d and the blocked layout (B6a's), from [connection].
     *
     * @param profile the connection's profile while one exists (connecting, connected or blocked),
     *   otherwise the active profile, so Home never shows one proxy and connects another.
     */
    data class Ready(val profile: ProxyProfile, val connection: ConnectionState, val canConnect: Boolean, val dialog: HomeDialog? = null) :
        HomeUiState {
        val status: ConnectionStatus get() = connection.status

        /** Why the last attempt with [profile] ended, or why its traffic is blocked; null when there is nothing to say. */
        val error: ConnectionError? get() = connection.error?.takeIf { connection.profileId == profile.id }
    }
}

sealed interface HomeDialog {
    /** Board B9: what the VPN permission is for, before Android's consent screen [consent]. */
    data class VpnExplainer(val consent: Intent) : HomeDialog

    /** Board H1: the user did not allow the VPN. Nothing was changed. */
    data object VpnPermissionDenied : HomeDialog

    /** Leaving a blocked state sends apps to the normal network, so it is confirmed (docs/m1/04 §7). */
    data object ConfirmStop : HomeDialog
}

class HomeViewModel(profiles: ProfileRepository, private val connection: ConnectionController) : ViewModel() {
    private val dialog = MutableStateFlow<HomeDialog?>(null)
    private var notificationRequestTaken = false

    val uiState: StateFlow<HomeUiState> =
        combine(profiles.profiles, profiles.activeProfileId, connection.state, dialog) { list, activeId, current, dialog ->
            val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
            val connecting = list.firstOrNull { it.id == current.profileId }?.takeIf { current.active }
            val shown = connecting ?: active
            if (shown == null) HomeUiState.NoProfile else HomeUiState.Ready(shown, current, connection.canConnect, dialog)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState.Loading)

    /**
     * Connect, Try again, Reconnect, Restart: the shown profile. When Android needs the
     * user's consent first, the explainer (B9) opens instead; nothing starts until the
     * user allowed it ([onConsentResult]).
     */
    fun connect() {
        val ready = uiState.value as? HomeUiState.Ready ?: return
        if (!ready.canConnect) return
        dialog.value = null
        connection.clearError()
        val consent = connection.consentIntent()
        if (consent != null) {
            dialog.value = HomeDialog.VpnExplainer(consent)
        } else {
            connection.connect(ready.profile.id)
        }
    }

    /** Android's consent screen answered: connect, or show H1. */
    fun onConsentResult(granted: Boolean) {
        val ready = uiState.value as? HomeUiState.Ready ?: return
        if (granted) {
            connection.connect(ready.profile.id)
        } else {
            dialog.value = HomeDialog.VpnPermissionDenied
        }
    }

    /** Disconnect (B4) and Cancel (B3). */
    fun disconnect() = connection.disconnect()

    /** "Stop and use normal network" while traffic is held: asks first. */
    fun requestStop() {
        dialog.value = HomeDialog.ConfirmStop
    }

    fun confirmStop() {
        dialog.value = null
        connection.disconnect()
    }

    /** Closes the open dialog; H1 and a shown error are then forgotten. */
    fun dismissDialog() {
        dialog.value = null
        connection.clearError()
    }

    /** The user acts on a shown error (Edit profile, Use another proxy), so it is not shown again. */
    fun dismissError() = connection.clearError()

    /**
     * Android 13+: true the first time per app run, when the screen should ask for the
     * notification permission before connecting (until onboarding asks, M9). Android
     * itself stops showing the request after two refusals.
     */
    fun takeNotificationRequest(): Boolean {
        val take = !notificationRequestTaken
        notificationRequestTaken = true
        return take
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
