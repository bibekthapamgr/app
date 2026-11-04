package app.proxyflow.core.model

/**
 * Connection states from the M1 state model (docs/m1/03-user-journeys.md).
 * Disconnected, Connecting, Connected; Reconnecting (a profile switch or a restart,
 * or — with [ConnectionState.reconnect] set — ProxyFlow reconnecting on its own, M7);
 * Blocked (the engine stopped and traffic is held back) and Offline (the phone has no
 * network: traffic is held until it has one again, M7).
 */
enum class ConnectionStatus { Disconnected, Connecting, Connected, Reconnecting, Blocked, Offline }

/** The step a connection attempt is at (board B3), while connecting and during a switch or a restart. */
enum class ConnectStep {
    /** The connection was asked for: the VPN service is being started. */
    Starting,

    /** Android's permission for ProxyFlow's VPN is checked. */
    VpnPermission,

    /** The pre-flight check: TCP, TLS and the proxy handshake with its sign-in, before anything changes on the phone. */
    ProxyHandshake,

    /**
     * The proxy opened a test tunnel and accepted the sign-in: the VPN interface is set up, the engine
     * started, and Android moves apps over to it.
     */
    Authenticated,
}

/**
 * The stage a connection is at, for the screens and the log (M7, ADR-0012). [key] is stored
 * in the log and exported, so it never changes.
 */
enum class ConnectionStage(val key: String) {
    Starting("starting"),
    VpnPermission("vpn_permission"),
    ProxyHandshake("proxy_handshake"),
    Authenticated("authenticated"),
    Connected("connected"),
    Reconnecting("reconnecting"),
    Failed("failed"),
    Stopped("stopped"),
    ;

    companion object {
        fun of(key: String?): ConnectionStage? = entries.firstOrNull { it.key == key }

        fun of(step: ConnectStep?): ConnectionStage = when (step) {
            ConnectStep.Starting, null -> Starting
            ConnectStep.VpnPermission -> VpnPermission
            ConnectStep.ProxyHandshake -> ProxyHandshake
            ConnectStep.Authenticated -> Authenticated
        }
    }
}

/** Why ProxyFlow is reconnecting on its own (M7). */
enum class ReconnectReason {
    /** Connections in a row could not reach the proxy (the escalation rule, docs/m1/04 §4). */
    ProxyFailures,

    /** The phone has a network again after being offline. */
    NetworkBack,

    /** The phone moved to another network while ProxyFlow was already reconnecting: the attempts start over. */
    NetworkChanged,

    /**
     * A SOCKS5 proxy left connection requests to different sites unanswered several times in a row, with nothing
     * working between them (M8): its own way out is probably broken.
     */
    ProxyUnanswered,

    /**
     * Android started ProxyFlow for Always-on VPN and connecting failed in a way that may pass (M8, ADR-0013):
     * traffic is held from the start, and attempts go on as after a connection that broke.
     */
    AlwaysOnStart,
}

/**
 * ProxyFlow is reconnecting on its own (board B5): attempt [attempt] of [maxAttempts] runs at
 * [nextAttemptAtMillis] (wall clock), or now when that is null. [lastError] is what the last
 * attempt, or the failures that started reconnecting, ended with.
 */
data class ReconnectInfo(
    val reason: ReconnectReason,
    val attempt: Int,
    val maxAttempts: Int,
    val nextAttemptAtMillis: Long?,
    val lastError: ConnectionError?,
)

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
        const val OFFLINE = "OFFLINE"

        /** The profile a connection used was deleted while traffic was held: it stays held until the user stops. */
        const val PROFILE_DELETED = "PROFILE_DELETED"

        /**
         * Android started ProxyFlow for Always-on VPN, but no profile is chosen (M8): ProxyFlow never picks one for the
         * user, so it connects nothing and says so.
         */
        const val ALWAYS_ON_NO_PROFILE = "ALWAYS_ON_NO_PROFILE"
        const val INTERNAL = "INTERNAL"

        /**
         * How the engine's detail for `AUTH_FAILED` starts when an HTTP proxy offers only sign-in methods
         * ProxyFlow lacks, such as Digest (engine/adapters/httpconnect.go): the screens then say so.
         */
        const val DETAIL_UNSUPPORTED_SIGN_IN = "proxy offers only "

        /** How the engine's detail for `PROTOCOL_ERROR` starts when an HTTP proxy refuses CONNECT itself (HTTP 405, 501). */
        const val DETAIL_NO_CONNECT = "proxy does not support CONNECT"
    }

    /** An HTTP proxy that offers only sign-in methods ProxyFlow doesn't support (catalogue: the Digest variant). */
    val unsupportedSignIn: Boolean get() = code == "AUTH_FAILED" && detail?.startsWith(DETAIL_UNSUPPORTED_SIGN_IN) == true

    /** An HTTP proxy that doesn't allow tunnels at all (catalogue: the 405/501 variant). */
    val noConnect: Boolean get() = code == "PROTOCOL_ERROR" && detail?.startsWith(DETAIL_NO_CONNECT) == true
}

/**
 * What the screens and the notification show about the connection.
 *
 * @param profileId the profile connecting or connected, or the one the last attempt used.
 * @param profile the settings the connection uses: a copy taken when the attempt started, so the
 *   screens describe what actually runs even after the saved profile is edited or deleted. No credentials.
 * @param step set while an attempt runs: [ConnectionStatus.Connecting], or [ConnectionStatus.Reconnecting]
 *   for a switch, a restart or an automatic attempt.
 * @param connectedSinceMillis wall-clock time the connection was first established; it stays the same
 *   while ProxyFlow reconnects on its own or waits for a network, so the session's time goes on.
 * @param error why the last attempt ended (with [ConnectionStatus.Disconnected]) or why
 *   traffic is blocked (with [ConnectionStatus.Blocked]); null after a normal disconnect.
 * @param reconnect set while ProxyFlow reconnects on its own (board B5).
 * @param held while [ConnectionStatus.Connecting]: the VPN interface is already up and holds apps' traffic — Android
 *   started ProxyFlow as Always-on VPN (M8, ADR-0013 §3) — so cancelling sends apps to the normal network, unlike a
 *   connect that changes nothing on the phone until it passes.
 */
data class ConnectionState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val profileId: String? = null,
    val profileName: String? = null,
    val profile: ProxyProfile? = null,
    val step: ConnectStep? = null,
    val connectedSinceMillis: Long? = null,
    val error: ConnectionError? = null,
    val reconnect: ReconnectInfo? = null,
    val held: Boolean = false,
) {
    /** Connecting or up: a new connect for the same profile has nothing to do. */
    val active: Boolean get() = status != ConnectionStatus.Disconnected

    /** The stage for the screens and the log (ADR-0012). */
    val stage: ConnectionStage
        get() = when (status) {
            ConnectionStatus.Connected -> ConnectionStage.Connected
            ConnectionStatus.Offline -> ConnectionStage.Reconnecting
            ConnectionStatus.Blocked -> ConnectionStage.Failed
            ConnectionStatus.Disconnected -> if (error != null) ConnectionStage.Failed else ConnectionStage.Stopped
            ConnectionStatus.Connecting -> ConnectionStage.of(step)
            ConnectionStatus.Reconnecting -> if (reconnect != null) ConnectionStage.Reconnecting else ConnectionStage.of(step)
        }

    companion object {
        val Idle = ConnectionState()
    }
}

/**
 * What the current connection carried (M7): approximate, as the engine counts it — the bytes of
 * the apps' TCP connections relayed through the proxy, without packet headers, the proxy's
 * handshakes or the DNS answered on the phone — and its connections relaying bytes now. It
 * starts when the connection is established and goes on while ProxyFlow reconnects on its own.
 */
data class TrafficStats(val bytesUp: Long, val bytesDown: Long, val activeConnections: Long, val sinceMillis: Long?)
