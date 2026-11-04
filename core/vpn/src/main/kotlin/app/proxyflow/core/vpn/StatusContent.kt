package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus

/**
 * What the status notification says and offers in each connection state (ADR-0010 §9, ADR-0012).
 * Kept apart from Android's notification classes, so the JVM tests can check every state;
 * [VpnNotifications] turns it into the notification. [attempt] and [maxAttempts] are set while
 * ProxyFlow reconnects on its own.
 */
internal data class StatusContent(
    val title: Title,
    val text: Text,
    val actions: List<Action>,
    val attempt: Int? = null,
    val maxAttempts: Int? = null,
) {
    enum class Title { CONNECTING, RECONNECTING, CONNECTED, BLOCKED, OFFLINE, DISCONNECTED }

    enum class Text {
        CHECKING_PROXY,
        STARTING_PROTECTION,
        HOLDING,
        RECONNECTING,
        RECONNECTING_ATTEMPT,
        CONNECTED,
        OFFLINE,
        ENGINE_STOPPED,
        PROFILE_DELETED,
        PROXY_FAILED,
        DISCONNECTED,
    }

    enum class Action { CANCEL, RESTART, DISCONNECT }

    companion object {
        private val RESTART_OR_DISCONNECT = listOf(Action.RESTART, Action.DISCONNECT)

        fun of(state: ConnectionState): StatusContent = when (state.status) {
            // Android started ProxyFlow as Always-on VPN (M8): its interface holds apps' traffic from the start, so the way
            // out is Disconnect — apps then use the normal network — not a Cancel that would leave the phone as it was.
            ConnectionStatus.Connecting -> if (state.held) {
                StatusContent(Title.CONNECTING, Text.HOLDING, listOf(Action.DISCONNECT))
            } else {
                StatusContent(
                    Title.CONNECTING,
                    if (state.step == ConnectStep.Authenticated) Text.STARTING_PROTECTION else Text.CHECKING_PROXY,
                    listOf(Action.CANCEL),
                )
            }
            ConnectionStatus.Reconnecting -> when (val retry = state.reconnect) {
                null -> StatusContent(Title.RECONNECTING, Text.RECONNECTING, listOf(Action.DISCONNECT))
                else -> StatusContent(
                    Title.RECONNECTING,
                    Text.RECONNECTING_ATTEMPT,
                    listOf(Action.DISCONNECT),
                    retry.attempt,
                    retry.maxAttempts,
                )
            }
            ConnectionStatus.Connected -> StatusContent(Title.CONNECTED, Text.CONNECTED, listOf(Action.DISCONNECT))
            ConnectionStatus.Offline -> StatusContent(Title.OFFLINE, Text.OFFLINE, listOf(Action.DISCONNECT))
            ConnectionStatus.Blocked -> when (state.error?.code) {
                ConnectionError.ENGINE_STOPPED -> StatusContent(Title.BLOCKED, Text.ENGINE_STOPPED, RESTART_OR_DISCONNECT)
                // Its proxy is never used again, so only stopping leaves this state (Home offers only Stop as well).
                ConnectionError.PROFILE_DELETED -> StatusContent(Title.BLOCKED, Text.PROFILE_DELETED, listOf(Action.DISCONNECT))
                else -> StatusContent(Title.BLOCKED, Text.PROXY_FAILED, RESTART_OR_DISCONNECT)
            }
            // Never shown: the service leaves the foreground when a connection ends.
            ConnectionStatus.Disconnected -> StatusContent(Title.DISCONNECTED, Text.DISCONNECTED, emptyList())
        }
    }
}
