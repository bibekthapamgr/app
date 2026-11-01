package app.proxyflow.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.RoutingDiagnostics
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The VPN layer of one process (ADR-0010): the connection state, the one
 * [VpnSession], and the [ConnectionController] the screens use. The app
 * builds it once (AppContainer) and hands it to [ProxyFlowVpnService], which
 * Android creates, through the [Provider] its Application implements.
 *
 * @param scope the app's scope; the session runs on a serial view of the I/O dispatcher inside it.
 * @param proxyTrust replaces the platform trust manager that checks TLS proxies' certificates.
 *   Only the device tests pass one (a platform trust manager that also trusts their run's test
 *   CA, ADR-0011); the app never does, so certificates are always checked as ADR-0005 says.
 */
class VpnRuntime(
    context: Context,
    private val profiles: ProfileRepository,
    scope: CoroutineScope,
    proxyTrust: (() -> X509TrustManager)? = null,
    private val engine: TunnelEngine = if (proxyTrust == null) ProxyTunnelEngine() else ProxyTunnelEngine(trust = proxyTrust),
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(ConnectionState.Idle)

    /** What the screens and the notification show. Written only by the session. */
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** The session's and the service's lifecycle, for diagnosis (no credentials, server names or payloads). */
    val log = VpnLog()

    val session = VpnSession(
        profiles = profiles,
        engine = engine,
        services = { startService() },
        state = mutableState,
        scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + SERIAL),
        log = log::add,
    )

    val controller: ConnectionController = VpnConnectionController(this)

    /**
     * Home's diagnostics (ADR-0011): while connected, the engine's counters read once a second for
     * as long as someone collects; null otherwise. Reading never waits for the session.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val diagnostics: Flow<RoutingDiagnostics?> = state
        .map { it.status == ConnectionStatus.Connected }
        .distinctUntilChanged()
        .flatMapLatest { connected ->
            if (!connected) {
                flowOf<RoutingDiagnostics?>(null)
            } else {
                flow<RoutingDiagnostics?> {
                    while (true) {
                        emit(engine.diagnostics())
                        delay(DIAGNOSTICS_INTERVAL_MS)
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)

    init {
        // A deleted profile's credentials must not stay in use: the connection using it stops its engine (J11).
        // Room is watched only while a connection exists.
        scope.launch {
            mutableState.map { s -> s.profileId?.takeIf { s.active } }.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                profiles.profiles.first { list -> list.none { it.id == id } }
                session.profileDeleted(id)
            }
        }
    }

    /** The system's VPN consent screen, or null when the VPN may start (see [ConnectionController.consentIntent]). */
    fun consentIntent(): Intent? = VpnService.prepare(appContext)

    private fun startService(): Boolean = try {
        appContext.startService(Intent(appContext, ProxyFlowVpnService::class.java).setAction(ProxyFlowVpnService.ACTION_CONNECT)) != null
    } catch (e: IllegalStateException) {
        false // not allowed from the background
    } catch (e: SecurityException) {
        false
    }

    /** Implemented by the Application, so the service Android creates finds the process's runtime. */
    interface Provider {
        val vpnRuntime: VpnRuntime
    }

    private companion object {
        /** One thread of control for every transition (ADR-0010 §2); blocking engine calls are allowed on it. */
        val SERIAL = Dispatchers.IO.limitedParallelism(1)

        const val DIAGNOSTICS_INTERVAL_MS = 1_000L
    }
}

/** The app's [ConnectionController]: screens send intentions, the session decides. */
internal class VpnConnectionController(private val runtime: VpnRuntime) : ConnectionController {
    override val state: StateFlow<ConnectionState> get() = runtime.state
    override val canConnect: Boolean = true

    override fun consentIntent(): Intent? = runtime.consentIntent()

    override fun connect(profileId: String) = runtime.session.connect(profileId)

    override fun disconnect() = runtime.session.disconnect()

    override fun restart() = runtime.session.restart()

    override fun clearError() = runtime.session.clearError()

    override val diagnostics: Flow<RoutingDiagnostics?> get() = runtime.diagnostics
}
