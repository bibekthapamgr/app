package app.proxyflow.core.data

import android.content.Intent
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.RoutingDiagnostics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The seam between the UI and the VPN layer (core/vpn, ADR-0010). Screens
 * observe [state] and send intentions; they never talk to the engine or the
 * VPN service directly (docs/m1/06-architecture.md §2).
 */
interface ConnectionController {
    val state: StateFlow<ConnectionState>

    /** False when this build cannot connect at all (JVM tests); the UI then says so instead of pretending. */
    val canConnect: Boolean

    /**
     * Android's VPN consent screen when the user has not allowed ProxyFlow's VPN
     * (or another VPN app took over since); null when [connect] may go ahead. The
     * screen launches it, behind the explainer (board B9), before [connect]. Asking
     * may take the VPN over from another app the user allowed earlier, so ask only
     * when the user taps Connect.
     */
    fun consentIntent(): Intent? = null

    /** Connects [profileId]; nothing happens while it is already connecting or connected, another profile is replaced. */
    fun connect(profileId: String)

    /** Disconnects, cancels a connection being set up, or stops a blocked one ("Stop and use normal network"). */
    fun disconnect()

    /** Runs the connection again after it stopped with traffic blocked (Restart, Try again). */
    fun restart() = Unit

    /** Forgets why the last attempt ended, once the user has seen it; a blocked connection keeps its reason. */
    fun clearError() = Unit

    /**
     * What the connection did since it connected (M6, ADR-0011), read from the engine about once
     * a second for as long as it is collected; null whenever it isn't connected.
     */
    val diagnostics: Flow<RoutingDiagnostics?> get() = flowOf(null)
}

/** For builds and tests without a VPN service: always disconnected, connecting is not available. */
class UnavailableConnectionController : ConnectionController {
    private val current = MutableStateFlow(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = current.asStateFlow()
    override val canConnect: Boolean = false

    override fun connect(profileId: String) = Unit

    override fun disconnect() = Unit
}
