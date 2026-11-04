package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.QuickTile.Content
import app.proxyflow.core.vpn.QuickTile.Line
import app.proxyflow.core.vpn.QuickTile.Look
import app.proxyflow.core.vpn.QuickTile.Tap
import java.text.Bidi
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
     * The subtitle reads as its line does in the line's own direction, whatever direction the system UI lays it out in
     * (M10-101, M10-106): right to left, as Android 12 and later do on a right-to-left phone (the phone's language);
     * left to right; and by the first letter outside isolates, as Android 10 and 11 do, on either kind of phone.
     * Laid out by Java's Unicode bidi algorithm, the same as Android's.
     */
    @Test
    fun theSubtitleKeepsItsOrderInEveryDirection() {
        val lines = listOf(
            "Tokyo residential", "2 Office", "1. Home", "Tokyo.", "1.", "2606:4700::", "2606:4700:4700::1111",
            "fd00::1", "203.0.113.10", "مكتب 2", "2 مكتب", "מִשְׂרָד.", "Connecting…", "Off",
        )
        val directions = listOf(
            Bidi.DIRECTION_RIGHT_TO_LEFT,
            Bidi.DIRECTION_LEFT_TO_RIGHT,
            Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT,
            Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT,
        )
        for (line in lines) {
            val asWritten = shown(line, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
            for (direction in directions) {
                assertEquals("\"$line\" laid out with base $direction", asWritten, shown(QuickTile.subtitleOf(line), direction))
            }
        }
        // Without the isolate, a right-to-left layout turns these round: what the test would catch.
        assertEquals(".Tokyo", shown("Tokyo.", Bidi.DIRECTION_RIGHT_TO_LEFT))
        assertEquals("1111::2606:4700:4700", shown("2606:4700:4700::1111", Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT))
        // The line's own directional formatting cannot end the isolate.
        assertEquals("\u2068Office!\u2069", QuickTile.subtitleOf("Office\u2069\u202E!"))
    }

    /** [text] in visual order, left to right, laid out in [direction]; the invisible formatting characters left out. */
    private fun shown(text: String, direction: Int): String {
        val bidi = Bidi(text, direction)
        val runs = Array<Any>(bidi.runCount) { it }
        val levels = ByteArray(bidi.runCount) { bidi.getRunLevel(it).toByte() }
        Bidi.reorderVisually(levels, 0, runs, 0, runs.size)
        return runs.joinToString("") { run ->
            val i = run as Int
            val part = text.substring(bidi.getRunStart(i), bidi.getRunLimit(i))
            if (bidi.getRunLevel(i) % 2 == 1) part.reversed() else part
        }.filterNot { it in '\u2066'..'\u2069' }
    }
}
