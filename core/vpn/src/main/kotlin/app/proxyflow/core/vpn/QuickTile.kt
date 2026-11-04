package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.isolatedText

/**
 * What the Quick Settings tile (board B11, M9) shows and does, apart from Android's tile API so it can be tested on the
 * JVM. The tile is on while ProxyFlow connects or a connection exists — connecting, connected, reconnecting, waiting for
 * a network or blocked: held or blocked traffic is never shown as off — and off once disconnected. During a first
 * connect apps still use the normal network until the proxy is checked (ADR-0010 §3); the tile says "Connecting".
 */
internal object QuickTile {
    /** Never Android's "unavailable": it delivers no tap, and a tile with nothing to connect opens Home instead. */
    enum class Look { On, Off }

    enum class Line { Profile, Connected, Connecting, Reconnecting, Offline, Blocked, Off, AddProxy }

    /** What a tap does. Anything that needs a choice or a confirmation (B9, "Stop and use normal network") opens Home. */
    enum class Tap { Connect, Disconnect, OpenApp }

    data class Content(val look: Look, val line: Line, val profileName: String?)

    /**
     * @param shownProfile the profile a tap connects: the one Home shows and connects ([ProfileRepository.activeProfileId]'s).
     *   While its connection runs, its name as saved now: a rename shows at once, not at the next connect.
     * @param locked the phone is locked: the tile is shown on the lock screen, so it names no profile.
     */
    fun content(state: ConnectionState, shownProfile: ProxyProfile?, locked: Boolean): Content {
        val connectionName = shownProfile?.takeIf { it.id == state.profileId }?.displayName ?: state.profileName
        val name = (connectionName ?: shownProfile?.displayName).takeUnless { locked }
        return when (state.status) {
            ConnectionStatus.Connected -> if (name != null) Content(Look.On, Line.Profile, name) else Content(Look.On, Line.Connected, null)
            ConnectionStatus.Connecting -> Content(Look.On, Line.Connecting, null)
            ConnectionStatus.Reconnecting -> Content(Look.On, Line.Reconnecting, null)
            ConnectionStatus.Offline -> Content(Look.On, Line.Offline, null)
            ConnectionStatus.Blocked -> Content(Look.On, Line.Blocked, null)
            ConnectionStatus.Disconnected -> if (shownProfile == null) {
                Content(Look.Off, Line.AddProxy, null)
            } else {
                Content(Look.Off, Line.Off, null)
            }
        }
    }

    /** What the tile shows as its second line, and what TalkBack reads with its name ([description]) and as its state. */
    data class Shown(val subtitle: String, val description: String, val stateDescription: String)

    /**
     * What the tile shows and says for [content]: the line — the profile's name, or ProxyFlow's [words] for it — set in
     * a first-strong isolate ([isolatedText]), so it reads in its own direction whatever the system UI's (M10-106).
     * Android 12 to 15 lay the subtitle out in the phone's language's direction, which on a right-to-left phone would
     * put a name's trailing full stop or leading digit at the wrong end ("2 Office", "Tokyo.") and turn ProxyFlow's
     * "Connecting…" round; Android 10 and 11, and Android 16's Compose tile, go by the first letter, which would turn round
     * a name without one ("2606:4700::1111", an unnamed profile's server: M10-101). The state description is the same
     * isolated line, so Android 16's tile, which leaves out a subtitle its state description already holds, reads it
     * once (M10-115); [describe] gets the line as it is.
     */
    fun shown(content: Content, words: (Line) -> String, describe: (String) -> String): Shown {
        val line = if (content.line == Line.Profile) content.profileName.orEmpty() else words(content.line)
        val isolatedLine = isolatedText(line)
        return Shown(subtitle = isolatedLine, description = describe(line), stateDescription = isolatedLine)
    }

    /**
     * @param hasProfile a profile exists to connect.
     * @param consentNeeded Android's VPN consent screen must be shown first, which only Home can do (behind B9).
     */
    fun tap(state: ConnectionState, hasProfile: Boolean, consentNeeded: Boolean): Tap = when (state.status) {
        // As Home's Disconnect (B4): apps go back to the normal network, which the user asked for.
        ConnectionStatus.Connected -> Tap.Disconnect
        ConnectionStatus.Disconnected -> if (hasProfile && !consentNeeded) Tap.Connect else Tap.OpenApp
        // Cancelling a held start, or leaving held traffic, sends apps to the normal network: Home asks first.
        ConnectionStatus.Connecting, ConnectionStatus.Reconnecting, ConnectionStatus.Offline, ConnectionStatus.Blocked -> Tap.OpenApp
    }
}
