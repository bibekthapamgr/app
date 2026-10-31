package app.proxyflow.core.model

/**
 * Connection states from the M1 state model (docs/m1/03-user-journeys.md).
 * M5 drives Disconnected, Connecting, Connected and Blocked (the engine
 * stopped and traffic is held back); Reconnecting and Offline arrive with
 * reconnect handling in M7.
 */
enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting, Blocked, Offline }

/** The step a connection is at while [ConnectionStatus.Connecting] (board B3). */
enum class ConnectStep {
    /** Reading the profile and checking the proxy: TLS and sign-in, before anything changes on the phone. */
    CheckingProxy,

    /** The proxy answered; the VPN interface is being set up and the engine started. */
    StartingProtection,
}

/**
 * Why the last connection attempt ended or what holds traffic back: [code] is
 * from the error catalogue (docs/m1/04-ux-screens-and-error-states.md §4), for
 * example `PROXY_UNREACHABLE` or `VPN_REVOKED`; [detail] is a log-safe phrase.
 */
data class ConnectionError(val code: String, val detail: String? = null) {
    companion object {
        const val VPN_PERMISSION_DENIED = "VPN_PERMISSION_DENIED"
        const val VPN_REVOKED = "VPN_REVOKED"
        const val CREDENTIAL_UNREADABLE = "CREDENTIAL_UNREADABLE"
        const val ENGINE_STOPPED = "ENGINE_STOPPED"
        const val INTERNAL = "INTERNAL"
    }
}

/**
 * What the screens and the notification show about the connection.
 *
 * @param profileId the profile connecting or connected, or the one the last attempt used.
 * @param step set while [status] is [ConnectionStatus.Connecting].
 * @param connectedSinceMillis wall-clock time the current connection was established.
 * @param error why the last attempt ended (with [ConnectionStatus.Disconnected]) or why
 *   traffic is blocked (with [ConnectionStatus.Blocked]); null after a normal disconnect.
 */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val profileId: String? = null,
    val profileName: String? = null,
    val step: ConnectStep? = null,
    val connectedSinceMillis: Long? = null,
    val error: ConnectionError? = null,
) {
    /** A VPN interface is up: everything except the app's own traffic goes to the engine. */
    val tunnelUp: Boolean get() = status == ConnectionStatus.Connected || status == ConnectionStatus.Blocked

    /** Connecting or up: a new connect for the same profile has nothing to do. */
    val active: Boolean get() = status != ConnectionStatus.Disconnected

    companion object {
        val Idle = ConnectionState()
    }
}

/** One entry in the local event log (filled by the engine from M7). */
data class LogEntry(val id: Long, val timeMillis: Long, val level: Level, val code: String?, val message: String) {
    enum class Level { Info, Warning, Error }
}
