package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState

/**
 * What only the running [ProxyFlowVpnService] can do, for the session: the
 * VPN APIs that need the service instance, and its foreground notification.
 * Every method may be called from the session's thread.
 */
interface VpnHost {
    /** `VpnService.prepare()` is null: the user's consent stands and no other VPN app took over since. */
    fun hasConsent(): Boolean

    /** `VpnService.Builder.establish()` with [config]; null when consent is missing. May throw. */
    fun establish(config: TunConfig): TunInterface?

    /** `VpnService.protect()`: keeps the socket out of the VPN. */
    fun protectSocket(fd: Int): Boolean

    /**
     * Android's VPN networks right now (ConnectivityManager), so the session can tell when
     * Android routes apps into an interface it just established. Throws when Android can't
     * answer: an empty list means there is no VPN network.
     */
    fun vpnNetworks(): List<VpnNetwork>

    /** A fresh [SocketGuard] for one pre-flight check. */
    fun socketGuard(): SocketGuard

    /**
     * Puts the service in the foreground, or updates its notification, for [state]; false
     * when Android refused. Updates may reach the notification a moment later; the last
     * state always does.
     */
    fun showStatus(state: ConnectionState): Boolean

    /** Alerts on the "Connection problems" channel (revocation, ADR-0010 §7). */
    fun notifyProblem(state: ConnectionState)

    /** Removes an earlier problem alert (a new connection starts). */
    fun clearProblem()

    /**
     * Leaves the foreground, removes the status notification and stops the
     * service, unless a newer start reached it meanwhile (`stopSelfResult`).
     */
    fun stop()
}

/**
 * One VPN network as ConnectivityManager reports it.
 *
 * @param id the network's handle (its netId), stable while it exists.
 * @param interfaceName the interface its routes lead into (`tun0`…).
 * @param ready Android has finished setting it up: it reports a VPN network VALIDATED only
 *   after the network was created with its routes, DNS servers and app rules in place.
 */
data class VpnNetwork(val id: String, val interfaceName: String?, val ready: Boolean)

/**
 * Lets a cancelled pre-flight check end at once instead of after its timeout: the
 * sockets the check protects are recorded ([track]), and [abort] shuts them down, so
 * the engine's blocking call returns; a socket tracked after [abort] is shut down at
 * once. [close] lets go of them after the check returned. Thread-safe.
 */
interface SocketGuard {
    fun track(fd: Int)

    fun abort()

    fun close()
}
