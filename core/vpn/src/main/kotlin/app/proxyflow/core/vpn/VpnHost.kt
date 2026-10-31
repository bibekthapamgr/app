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

    /** Puts the service in the foreground, or updates its notification, for [state]; false when Android refused. */
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
