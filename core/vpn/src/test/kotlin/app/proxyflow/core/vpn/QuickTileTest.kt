package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.QuickTile.Content
import app.proxyflow.core.vpn.QuickTile.Line
import app.proxyflow.core.vpn.QuickTile.Look
import app.proxyflow.core.vpn.QuickTile.Tap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Quick Settings tile (board B11, M9): on while ProxyFlow connects or a connection exists — blocked traffic is never
 * shown as off — the profile named only on an unlocked phone, and a tap that only connects or disconnects; whatever
 * needs a choice or a confirmation opens Home.
 */
class QuickTileTest {
    private fun state(status: ConnectionStatus) = ConnectionState(status, profileId = "p1", profileName = "Tokyo residential")
    private val office = ProxyProfile("p2", "Office", ProxyProtocol.HTTP, "office.example.com", 3128)

    @Test
    fun theTileIsOnWheneverAConnectionExists() {
        assertEquals(
            Content(Look.On, Line.Profile, "Tokyo residential"),
            QuickTile.content(state(ConnectionStatus.Connected), office, locked = false),
        )
        assertEquals(
            Content(Look.On, Line.Connecting, null),
            QuickTile.content(state(ConnectionStatus.Connecting), office, locked = false),
        )
        assertEquals(
            Content(Look.On, Line.Reconnecting, null),
            QuickTile.content(state(ConnectionStatus.Reconnecting), office, locked = false),
        )
        assertEquals(Content(Look.On, Line.Offline, null), QuickTile.content(state(ConnectionStatus.Offline), office, locked = false))
        assertEquals(
            "never shown as off",
            Content(Look.On, Line.Blocked, null),
            QuickTile.content(state(ConnectionStatus.Blocked), office, locked = false),
        )
        assertEquals(Content(Look.Off, Line.Off, null), QuickTile.content(ConnectionState.Idle, office, locked = false))
        // Inactive, not Android's "unavailable", which delivers no tap: a tap opens Home to add one.
        assertEquals(Content(Look.Off, Line.AddProxy, null), QuickTile.content(ConnectionState.Idle, null, locked = false))
        assertEquals(Tap.OpenApp, QuickTile.tap(ConnectionState.Idle, hasProfile = false, consentNeeded = false))
    }

    /** A profile renamed while its connection runs shows its new name at once, as Home does. */
    @Test
    fun theConnectionsProfileIsNamedAsSavedNow() {
        val renamed = ProxyProfile("p1", "Tokyo (home)", ProxyProtocol.SOCKS5, "tokyo.example.com", 1080)
        assertEquals(
            Content(Look.On, Line.Profile, "Tokyo (home)"),
            QuickTile.content(state(ConnectionStatus.Connected), renamed, locked = false),
        )
        assertEquals(
            "another profile shown: the connection's own name",
            Content(Look.On, Line.Profile, "Tokyo residential"),
            QuickTile.content(state(ConnectionStatus.Connected), office, locked = false),
        )
    }

    @Test
    fun aLockedPhoneNamesNoProfile() {
        assertEquals(Content(Look.On, Line.Connected, null), QuickTile.content(state(ConnectionStatus.Connected), office, locked = true))
        assertEquals(Content(Look.Off, Line.Off, null), QuickTile.content(ConnectionState.Idle, office, locked = true))
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

    /**
     * A name without a letter, as an unnamed profile's server, gets a left-to-right mark in front, so a right-to-left
     * phone's system UI keeps it in order (M10-101); a name with a letter, in any script, is left as it is.
     */
    @Test
    fun aNameWithoutALetterKeepsItsOrder() {
        assertEquals("\u200E2606:4700:4700::1111", QuickTile.subtitleOf("2606:4700:4700::1111"))
        assertEquals("\u200E203.0.113.10", QuickTile.subtitleOf("203.0.113.10"))
        for (name in listOf("Tokyo residential", "fd00::1", "مكتب 2", "2 מִשְׂרָד", "東京")) {
            assertEquals(name, QuickTile.subtitleOf(name))
        }
    }
}
