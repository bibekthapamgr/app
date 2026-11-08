package app.proxyflow.core.data

import android.content.Intent
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.TrafficStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * Runs the connection of [profileId] again with the profile as it is saved now (board D9, "Save and reconnect"): once
     * connected, its flows end and traffic is held until the new settings are checked; during a first connect, they are
     * checked instead, holding nothing (`ConnectionState.holdsTrafficOnSwitch`). Nothing happens without a connection for it.
     */
    fun reconnect(profileId: String) = Unit

    /**
     * The user picked [profileId] (board B8, "Use this proxy"): while a connection exists for another profile, it switches
     * to [profileId] (J11) — once connected, the old proxy's flows end and apps' traffic is held until the new proxy is
     * checked, never sent around a proxy (ADR-0010 §5); during a first connect, the new proxy is checked instead. Without
     * one, nothing starts: connecting stays the user's own step. The session decides, in order with what it was asked before.
     */
    fun switchIfConnected(profileId: String) = Unit

    /** Disconnects only a connection that is Connected when the session gets to it (the Quick Settings tile, B11). */
    fun disconnectIfConnected() = Unit

    /** Forgets why the last attempt ended, once the user has seen it; a blocked connection keeps its reason. */
    fun clearError() = Unit

    /**
     * What the connection did since it connected (M6, ADR-0011), read from the engine about once
     * a second for as long as it is collected; null whenever it isn't connected.
     */
    val diagnostics: Flow<RoutingDiagnostics?> get() = flowOf(null)

    /**
     * What the connection carried since it was established (M7, ADR-0012), read about once a second
     * for as long as it is collected: it goes on while ProxyFlow reconnects on its own or waits for a
     * network; null when there is no connection.
     */
    val traffic: Flow<TrafficStats?> get() = flowOf(null)

    /** While ProxyFlow reconnects on its own (board B5), runs the next attempt now instead of after its wait. */
    fun retryNow() = Unit

    /**
     * Asks Android about its kill switch now, while ProxyFlow's VPN runs (Android says nothing otherwise): a screen that
     * shows the status calls it when it is shown (M8). The answer arrives through [ProtectionStore.killSwitch].
     */
    fun checkSystemKillSwitch() = Unit

    /**
     * The interrupted connection Home shows, [shown], has been seen, or the user connects again: it is cleared through
     * the session, in order with its own writes, and only if it is still the one stored — one found as the process
     * starts, which Home has not shown yet, is never cleared unseen.
     */
    fun dismissInterruption(shown: Interruption) = Unit
}
