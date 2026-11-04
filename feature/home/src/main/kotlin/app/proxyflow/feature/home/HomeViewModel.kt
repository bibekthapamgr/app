package app.proxyflow.feature.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.InMemoryProtectionStore
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.data.ProtectionStore
import app.proxyflow.core.data.switchIfConnected
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.TrafficStats
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
     * @param killSwitch Android's kill switch as Android last reported it while ProxyFlow was connected (M8); null
     *   when it never did.
     * @param interruption the last process ended while connected, without the connection being stopped, and the user
     *   has not acknowledged it yet (M8).
     * @param profiles every saved profile, for the profile picker (B8), with [lastTests], each one's last connection test.
     */
    data class Ready(
        val profile: ProxyProfile,
        val connection: ConnectionState,
        val canConnect: Boolean,
        val dialog: HomeDialog? = null,
        val diagnostics: RoutingDiagnostics? = null,
        val traffic: TrafficStats? = null,
        val killSwitch: SystemKillSwitch? = null,
        val interruption: Interruption? = null,
        val profiles: List<ProxyProfile> = emptyList(),
        val lastTests: Map<String, ProfileTest> = emptyMap(),
    ) : HomeUiState {
        val status: ConnectionStatus get() = connection.status

        /**
         * Why the last attempt with [profile] ended, or why its traffic is blocked; null when there is nothing to say.
         * Android starting ProxyFlow for Always-on VPN with no profile chosen concerns no profile, and is shown too.
         */
        val error: ConnectionError? get() = connection.error?.takeIf {
            connection.profileId == profile.id || it.code == ConnectionError.ALWAYS_ON_NO_PROFILE
        }
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

class HomeViewModel(
    private val profiles: ProfileRepository,
    private val connection: ConnectionController,
    protection: ProtectionStore = InMemoryProtectionStore(),
) : ViewModel() {
    private val dialog = MutableStateFlow<HomeDialog?>(null)

    // The connection and, while connected, its diagnostics and traffic: read from the engine only while
    // Home is shown (and for STOP_TIMEOUT_MS after), never shown for a connection that is gone.
    private val live = combine(connection.state, connection.diagnostics, connection.traffic) { state, read, traffic ->
        Live(
            connection = state,
            diagnostics = read.takeIf { state.status == ConnectionStatus.Connected },
            traffic = traffic.takeIf { state.connectedSinceMillis != null && it?.sinceMillis == state.connectedSinceMillis },
        )
    }

    // Android's kill switch as last reported, and an interruption not acknowledged yet (M8).
    private val kept =
        combine(protection.killSwitch, protection.interruption) { killSwitch, interruption -> Kept(killSwitch, interruption) }

    // The saved profiles and each one's last test, for the picker (B8).
    private val saved = combine(profiles.profiles, profiles.lastTests) { list, tests -> Saved(list, tests) }

    val uiState: StateFlow<HomeUiState> =
        combine(saved, profiles.activeProfileId, live, dialog, kept) { stored, activeId, now, dialog, kept ->
            val list = stored.profiles
            val current = now.connection
            val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
            val running = current.profile?.takeIf { current.active }
            val shown = running ?: active
            if (shown == null) {
                HomeUiState.NoProfile
            } else {
                HomeUiState.Ready(
                    shown,
                    current,
                    connection.canConnect,
                    dialog,
                    now.diagnostics,
                    now.traffic,
                    killSwitch = kept.killSwitch,
                    interruption = kept.interruption,
                    profiles = list,
                    lastTests = stored.lastTests,
                )
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
            connectNow(ready.profile.id)
        }
    }

    /** Android's consent screen answered: connect, or show H1. */
    fun onConsentResult(granted: Boolean) {
        val ready = uiState.value as? HomeUiState.Ready ?: return
        if (granted) {
            connectNow(ready.profile.id)
        } else {
            dialog.value = HomeDialog.VpnPermissionDenied
        }
    }

    /**
     * Connecting the profile Home shows is the user's choice of it (M8): Always-on VPN later connects it, and never a
     * profile the user didn't pick. A notice of an interrupted connection Home shows has been seen once the user
     * connects again; one not shown yet stays.
     */
    private fun connectNow(profileId: String) {
        connection.connect(profileId)
        dismissInterruption()
        viewModelScope.launch {
            if (profiles.chosenProfileId.first() != profileId) profiles.setActive(profileId)
        }
    }

    /**
     * B8: [id] becomes the profile Home shows and connects, the user's choice (M8). While a connection runs on another
     * profile it switches to [id] (J11): the old proxy's flows end and apps' traffic is held until the new proxy is
     * checked, never sent around a proxy (ADR-0010 §5).
     */
    fun chooseProfile(id: String) {
        connection.switchIfConnected(id)
        viewModelScope.launch {
            try {
                profiles.setActive(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not saved: Home goes on showing the profile it showed (after a switch, the connection's).
            }
        }
    }

    /** The notice that ProxyFlow was stopped while connected, acknowledged (M8): the one Home shows, if it still is. */
    fun dismissInterruption() {
        (uiState.value as? HomeUiState.Ready)?.interruption?.let(connection::dismissInterruption)
    }

    /** Home is shown: while ProxyFlow is connected, Android is asked about its kill switch again (M8). */
    fun checkKillSwitch() = connection.checkSystemKillSwitch()

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

    private data class Live(val connection: ConnectionState, val diagnostics: RoutingDiagnostics?, val traffic: TrafficStats?)

    private data class Kept(val killSwitch: SystemKillSwitch?, val interruption: Interruption?)

    private data class Saved(val profiles: List<ProxyProfile>, val lastTests: Map<String, ProfileTest>)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
