package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus

/**
 * What the Quick Settings tile (board B11, M9) shows and does, apart from Android's tile API so it can be tested on the
 * JVM. The tile is on while a connection exists — connecting, connected, reconnecting, waiting for a network or blocked:
 * blocked traffic is never shown as off — and off only when apps use the normal network.
 */
internal object QuickTile {
    enum class Look { On, Off, Unavailable }

    enum class Line { Profile, Connected, Connecting, Reconnecting, Offline, Blocked, Off, AddProxy }

    /** What a tap does. Anything that needs a choice or a confirmation (B9, "Stop and use normal network") opens Home. */
    enum class Tap { Connect, Disconnect, OpenApp }

    data class Content(val look: Look, val line: Line, val profileName: String?)

    /**
     * @param shownProfile the profile a tap connects: the one Home shows and connects ([ProfileRepository.activeProfileId]'s).
     * @param locked the phone is locked: the tile is shown on the lock screen, so it names no profile.
     */
    fun content(state: ConnectionState, shownProfile: String?, locked: Boolean): Content {
        val name = (state.profileName ?: shownProfile).takeUnless { locked }
        return when (state.status) {
            ConnectionStatus.Connected -> if (name != null) Content(Look.On, Line.Profile, name) else Content(Look.On, Line.Connected, null)
            ConnectionStatus.Connecting -> Content(Look.On, Line.Connecting, null)
            ConnectionStatus.Reconnecting -> Content(Look.On, Line.Reconnecting, null)
            ConnectionStatus.Offline -> Content(Look.On, Line.Offline, null)
            ConnectionStatus.Blocked -> Content(Look.On, Line.Blocked, null)
            ConnectionStatus.Disconnected -> if (shownProfile == null) {
                Content(Look.Unavailable, Line.AddProxy, null)
            } else {
                Content(Look.Off, Line.Off, null)
            }
        }
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
