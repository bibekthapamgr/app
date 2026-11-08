package app.proxyflow.core.vpn

import app.proxyflow.core.data.ConnectedMarker
import app.proxyflow.core.model.ExitReason

/**
 * What the session asks Android about its own process (M8, ADR-0013): what to leave behind while connected, so the next
 * process can tell that this one ended without the connection being stopped, and how such a process ended.
 */
interface ProcessHistory {
    /** The marker this process leaves while [profileId] is connected since [sinceMillis]. */
    fun marker(profileId: String?, sinceMillis: Long): ConnectedMarker

    /** How the process that left [marker] ended, as far as Android says. */
    fun ending(marker: ConnectedMarker): Ending

    /** [reason], and when Android says the process ended (wall clock), or null when it does not say. */
    data class Ending(val reason: ExitReason, val atMillis: Long?)

    /** For the JVM tests: no process to ask about. */
    object None : ProcessHistory {
        override fun marker(profileId: String?, sinceMillis: Long) = ConnectedMarker(profileId, sinceMillis, pid = 0, bootCount = null)

        override fun ending(marker: ConnectedMarker) = Ending(ExitReason.Unknown, null)
    }
}
