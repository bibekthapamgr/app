package app.proxyflow.core.model

/**
 * Connection states from the M1 state model (docs/m1/03-user-journeys.md).
 * M5 drives Disconnected, Connecting, Connected, Reconnecting (a profile
 * switch or a restart, traffic held meanwhile) and Blocked (the engine
 * stopped and traffic is held back); Offline arrives with network handling in M7.
 */
enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting, Blocked, Offline }

/** The step a connection is at while [ConnectionStatus.Connecting] (board B3). */
enum class ConnectStep {
    /** Reading the profile and checking the proxy: TLS and sign-in, before anything changes on the phone. */
    CheckingProxy,

    /** The proxy answered; the VPN interface is being set up, the engine started, and Android moves apps over to it. */
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

        /** The profile a connection used was deleted while traffic was held: it stays held until the user stops. */
        const val PROFILE_DELETED = "PROFILE_DELETED"
        const val INTERNAL = "INTERNAL"
    }
}

/**
 * What the screens and the notification show about the connection.
 *
 * @param profileId the profile connecting or connected, or the one the last attempt used.
 * @param profile the settings the connection uses: a copy taken when the attempt started, so the
 *   screens describe what actually runs even after the saved profile is edited or deleted. No credentials.
 * @param step set while [status] is [ConnectionStatus.Connecting] or [ConnectionStatus.Reconnecting].
 * @param connectedSinceMillis wall-clock time the current connection was established.
 * @param error why the last attempt ended (with [ConnectionStatus.Disconnected]) or why
 *   traffic is blocked (with [ConnectionStatus.Blocked]); null after a normal disconnect.
 */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val profileId: String? = null,
    val profileName: String? = null,
    val profile: ProxyProfile? = null,
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
