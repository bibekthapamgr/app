package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus

/**
 * What the status notification says and offers in each connection state (ADR-0010 §9).
 * Kept apart from Android's notification classes, so the JVM tests can check every state;
 * [VpnNotifications] turns it into the notification.
 */
internal data class StatusContent(val title: Title, val text: Text, val actions: List<Action>) {
    enum class Title { CONNECTING, RECONNECTING, CONNECTED, BLOCKED, DISCONNECTED }

    enum class Text {
        CHECKING_PROXY,
        STARTING_PROTECTION,
        RECONNECTING,
        CONNECTED,
        ENGINE_STOPPED,
        PROFILE_DELETED,
        PROXY_FAILED,
        DISCONNECTED,
    }

    enum class Action { CANCEL, RESTART, DISCONNECT }

    companion object {
        private val RESTART_OR_DISCONNECT = listOf(Action.RESTART, Action.DISCONNECT)

        fun of(state: ConnectionState): StatusContent = when (state.status) {
            ConnectionStatus.Connecting -> StatusContent(
                Title.CONNECTING,
                if (state.step == ConnectStep.StartingProtection) Text.STARTING_PROTECTION else Text.CHECKING_PROXY,
                listOf(Action.CANCEL),
            )
            ConnectionStatus.Reconnecting -> StatusContent(Title.RECONNECTING, Text.RECONNECTING, listOf(Action.DISCONNECT))
            ConnectionStatus.Connected -> StatusContent(Title.CONNECTED, Text.CONNECTED, listOf(Action.DISCONNECT))
            ConnectionStatus.Blocked, ConnectionStatus.Offline -> when (state.error?.code) {
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
