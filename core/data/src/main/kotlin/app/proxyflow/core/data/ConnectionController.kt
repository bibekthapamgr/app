package app.proxyflow.core.data

import app.proxyflow.core.model.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The seam between the UI and the VPN service. Screens observe [status] and
 * call [connect]/[disconnect]; they never talk to the engine directly
 * (docs/m1/06-architecture.md §2).
 */
interface ConnectionController {
    val status: StateFlow<ConnectionStatus>

    /** False until the VPN service exists (milestone 5); the UI says so instead of pretending. */
    val canConnect: Boolean

    fun connect(profileId: String)

    fun disconnect()
}

/** M2 implementation: always disconnected, connecting is not available yet. */
class UnavailableConnectionController : ConnectionController {
    private val state = MutableStateFlow(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = state.asStateFlow()
    override val canConnect: Boolean = false

    override fun connect(profileId: String) = Unit

    override fun disconnect() = Unit
}
