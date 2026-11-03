package app.proxyflow.core.data

import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.SystemKillSwitch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What ProxyFlow keeps about its protection from one process to the next (M8, ADR-0013): what Android last said about
 * its kill switch, the connection this process runs (so the next process can tell it ended without being stopped), and
 * such an interrupted connection until the user has seen it on Home. Ids, times and reasons only.
 */
interface ProtectionStore {
    /** Android's kill switch as Android last reported it while ProxyFlow was connected; null until that happened once. */
    val killSwitch: Flow<SystemKillSwitch?>

    suspend fun recordKillSwitch(value: SystemKillSwitch)

    /** A connection that ended with its process, until the user has seen it (Home) or connected again; null when none. */
    val interruption: Flow<Interruption?>

    suspend fun setInterruption(value: Interruption?)

    /** Written when a connection is established, so a process that ends without stopping it leaves it behind. */
    suspend fun markConnected(marker: ConnectedMarker)

    /** The connection was stopped (or it never connected): nothing is left behind. */
    suspend fun clearConnected()

    /** What a previous process left behind, removed as it is read; null when its connection was stopped. */
    suspend fun takeConnected(): ConnectedMarker?
}

/**
 * The connection a process runs: its profile's random id, since when (wall clock), and the process's id and the
 * phone's boot count at the time, so the next process can ask Android how that one ended (ApplicationExitInfo) and
 * tell a restart of the phone apart.
 */
data class ConnectedMarker(val profileId: String?, val sinceMillis: Long, val pid: Int, val bootCount: Int?)

/** [ProtectionStore] in memory: JVM tests, and a process that may not keep anything. */
class InMemoryProtectionStore(
    killSwitch: SystemKillSwitch? = null,
    interruption: Interruption? = null,
    connected: ConnectedMarker? = null,
) : ProtectionStore {
    private val switch = MutableStateFlow(killSwitch)
    private val interrupted = MutableStateFlow(interruption)

    /** The marker as stored now (tests). */
    @Volatile var connected: ConnectedMarker? = connected
        private set

    override val killSwitch: Flow<SystemKillSwitch?> = switch.asStateFlow()

    override suspend fun recordKillSwitch(value: SystemKillSwitch) {
        switch.value = value
    }

    override val interruption: Flow<Interruption?> = interrupted.asStateFlow()

    override suspend fun setInterruption(value: Interruption?) {
        interrupted.value = value
    }

    override suspend fun markConnected(marker: ConnectedMarker) {
        connected = marker
    }

    override suspend fun clearConnected() {
        connected = null
    }

    override suspend fun takeConnected(): ConnectedMarker? = connected.also { connected = null }
}
