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
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.TrafficStats
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
     * Boards B2–B7, from [connection].
     *
     * @param profile while a connection exists (connecting, connected, reconnecting or blocked), the
     *   settings it runs with — its own copy, unchanged by later edits and still there when the saved
     *   profile was deleted; otherwise the active profile, so Home never shows one proxy and connects another.
     * @param diagnostics while connected, what the connection did so far (B4, ADR-0011); null until read.
     * @param traffic while there is a connected session (B4, and on through B5 and B7), what it carried
     *   so far (ADR-0012); null until read.
     */
    data class Ready(
        val profile: ProxyProfile,
        val connection: ConnectionState,
        val canConnect: Boolean,
        val dialog: HomeDialog? = null,
        val diagnostics: RoutingDiagnostics? = null,
        val traffic: TrafficStats? = null,
    ) : HomeUiState {
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

    // The connection and, while connected, its diagnostics and traffic: read from the engine only while
    // Home is shown (and for STOP_TIMEOUT_MS after), never shown for a connection that is gone.
    private val live = combine(connection.state, connection.diagnostics, connection.traffic) { state, read, traffic ->
        Live(
            connection = state,
            diagnostics = read.takeIf { state.status == ConnectionStatus.Connected },
            traffic = traffic.takeIf { state.connectedSinceMillis != null && it?.sinceMillis == state.connectedSinceMillis },
        )
    }

    val uiState: StateFlow<HomeUiState> =
        combine(profiles.profiles, profiles.activeProfileId, live, dialog) { list, activeId, now, dialog ->
            val current = now.connection
            val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
            val running = current.profile?.takeIf { current.active }
            val shown = running ?: active
            if (shown == null) {
                HomeUiState.NoProfile
            } else {
                HomeUiState.Ready(shown, current, connection.canConnect, dialog, now.diagnostics, now.traffic)
            }
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

    /** "Retry now" while ProxyFlow reconnects on its own (B5): the next attempt runs at once. */
    fun retryNow() = connection.retryNow()

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

    private data class Live(val connection: ConnectionState, val diagnostics: RoutingDiagnostics?, val traffic: TrafficStats?)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
