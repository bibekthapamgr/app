package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.vpn.QuickTile.Content
import app.proxyflow.core.vpn.QuickTile.Line
import app.proxyflow.core.vpn.QuickTile.Look
import app.proxyflow.core.vpn.QuickTile.Tap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Quick Settings tile (board B11, M9): on while a connection exists — blocked traffic is never shown as off — the
 * profile named only on an unlocked phone, and a tap that only connects or disconnects; whatever needs a choice or a
 * confirmation opens Home.
 */
class QuickTileTest {
    private fun state(status: ConnectionStatus) = ConnectionState(status, profileId = "p1", profileName = "Tokyo residential")

    @Test
    fun theTileIsOnWheneverAConnectionExists() {
        assertEquals(
            Content(Look.On, Line.Profile, "Tokyo residential"),
            QuickTile.content(state(ConnectionStatus.Connected), "Office", locked = false),
        )
        assertEquals(
            Content(Look.On, Line.Connecting, null),
            QuickTile.content(state(ConnectionStatus.Connecting), "Office", locked = false),
        )
        assertEquals(
            Content(Look.On, Line.Reconnecting, null),
            QuickTile.content(state(ConnectionStatus.Reconnecting), "Office", locked = false),
        )
        assertEquals(Content(Look.On, Line.Offline, null), QuickTile.content(state(ConnectionStatus.Offline), "Office", locked = false))
        assertEquals(
            "never shown as off",
            Content(Look.On, Line.Blocked, null),
            QuickTile.content(state(ConnectionStatus.Blocked), "Office", locked = false),
        )
        assertEquals(Content(Look.Off, Line.Off, null), QuickTile.content(ConnectionState.Idle, "Office", locked = false))
        assertEquals(Content(Look.Unavailable, Line.AddProxy, null), QuickTile.content(ConnectionState.Idle, null, locked = false))
    }

    @Test
    fun aLockedPhoneNamesNoProfile() {
        assertEquals(Content(Look.On, Line.Connected, null), QuickTile.content(state(ConnectionStatus.Connected), "Office", locked = true))
        assertEquals(Content(Look.Off, Line.Off, null), QuickTile.content(ConnectionState.Idle, "Office", locked = true))
    }

    @Test
    fun aTapOnlyConnectsOrDisconnectsAndOtherwiseOpensHome() {
        assertEquals(Tap.Disconnect, QuickTile.tap(state(ConnectionStatus.Connected), hasProfile = true, consentNeeded = false))
        assertEquals(Tap.Connect, QuickTile.tap(ConnectionState.Idle, hasProfile = true, consentNeeded = false))
        assertEquals(
            "Android's consent screen needs Home (B9)",
            Tap.OpenApp,
            QuickTile.tap(ConnectionState.Idle, hasProfile = true, consentNeeded = true),
        )
        assertEquals("nothing to connect", Tap.OpenApp, QuickTile.tap(ConnectionState.Idle, hasProfile = false, consentNeeded = false))
        for (status in listOf(
            ConnectionStatus.Connecting,
            ConnectionStatus.Reconnecting,
            ConnectionStatus.Offline,
            ConnectionStatus.Blocked,
        )) {
            // Leaving held traffic sends apps to the normal network: only Home's confirmed Stop does that.
            assertEquals(status.name, Tap.OpenApp, QuickTile.tap(state(status), hasProfile = true, consentNeeded = false))
        }
    }
}
