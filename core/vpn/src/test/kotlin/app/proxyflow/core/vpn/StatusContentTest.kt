package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ReconnectInfo
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.vpn.StatusContent.Action
import app.proxyflow.core.vpn.StatusContent.Text
import app.proxyflow.core.vpn.StatusContent.Title
import org.junit.Assert.assertEquals
import org.junit.Test

/** The status notification says what each state means for apps' traffic, and offers only what works in it. */
class StatusContentTest {
    @Test
    fun theCheckAndTheStartOfProtectionCanBeCancelled() {
        assertEquals(
            StatusContent(Title.CONNECTING, Text.CHECKING_PROXY, listOf(Action.CANCEL)),
            StatusContent.of(state(ConnectionStatus.Connecting, step = ConnectStep.ProxyHandshake)),
        )
        assertEquals(
            StatusContent(Title.CONNECTING, Text.STARTING_PROTECTION, listOf(Action.CANCEL)),
            StatusContent.of(state(ConnectionStatus.Connecting, step = ConnectStep.Authenticated)),
        )
    }

    /** M8: an Always-on VPN start holds traffic during its check, so the way out is Disconnect, as while reconnecting. */
    @Test
    fun anAlwaysOnStartHoldingTrafficOffersDisconnectNotCancel() {
        assertEquals(
            StatusContent(Title.CONNECTING, Text.HOLDING, listOf(Action.DISCONNECT)),
            StatusContent.of(state(ConnectionStatus.Connecting, step = ConnectStep.ProxyHandshake).copy(held = true)),
        )
    }

    @Test
    fun reconnectingSaysTrafficIsPausedAndOffersDisconnect() {
        assertEquals(
            StatusContent(Title.RECONNECTING, Text.RECONNECTING, listOf(Action.DISCONNECT)),
            StatusContent.of(state(ConnectionStatus.Reconnecting, step = ConnectStep.ProxyHandshake)),
        )
    }

    @Test
    fun reconnectingOnItsOwnCountsTheAttempts() {
        val retry = ReconnectInfo(ReconnectReason.ProxyFailures, attempt = 2, maxAttempts = 8, nextAttemptAtMillis = 5L, lastError = null)
        assertEquals(
            StatusContent(Title.RECONNECTING, Text.RECONNECTING_ATTEMPT, listOf(Action.DISCONNECT), attempt = 2, maxAttempts = 8),
            StatusContent.of(state(ConnectionStatus.Reconnecting).copy(reconnect = retry)),
        )
    }

    @Test
    fun offlineSaysTrafficWaitsForANetwork() {
        assertEquals(
            StatusContent(Title.OFFLINE, Text.OFFLINE, listOf(Action.DISCONNECT)),
            StatusContent.of(state(ConnectionStatus.Offline, ConnectionError.OFFLINE)),
        )
    }

    @Test
    fun connectedOffersOnlyDisconnect() {
        assertEquals(
            StatusContent(Title.CONNECTED, Text.CONNECTED, listOf(Action.DISCONNECT)),
            StatusContent.of(state(ConnectionStatus.Connected)),
        )
    }

    @Test
    fun blockedSaysWhyAndOffersRestartWhereARestartCanWork() {
        assertEquals(
            StatusContent(Title.BLOCKED, Text.ENGINE_STOPPED, listOf(Action.RESTART, Action.DISCONNECT)),
            StatusContent.of(state(ConnectionStatus.Blocked, ConnectionError.ENGINE_STOPPED)),
        )
        for (code in listOf("AUTH_FAILED", "HANDSHAKE_TIMEOUT", "PROXY_UNREACHABLE", ConnectionError.INTERNAL)) {
            assertEquals(
                code,
                StatusContent(Title.BLOCKED, Text.PROXY_FAILED, listOf(Action.RESTART, Action.DISCONNECT)),
                StatusContent.of(state(ConnectionStatus.Blocked, code)),
            )
        }
    }

    /** The deleted profile's proxy is never used again: Restart could not work, so only Disconnect is offered. */
    @Test
    fun aDeletedProfileOffersOnlyDisconnect() {
        assertEquals(
            StatusContent(Title.BLOCKED, Text.PROFILE_DELETED, listOf(Action.DISCONNECT)),
            StatusContent.of(state(ConnectionStatus.Blocked, ConnectionError.PROFILE_DELETED)),
        )
    }

    private fun state(status: ConnectionStatus, code: String? = null, step: ConnectStep? = null) = ConnectionState(
        status = status,
        profileId = "p1",
        profileName = "Office",
        step = step,
        connectedSinceMillis = if (status == ConnectionStatus.Connected) 1L else null,
        error = code?.let { ConnectionError(it) },
    )
}
