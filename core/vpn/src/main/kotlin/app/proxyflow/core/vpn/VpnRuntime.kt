package app.proxyflow.core.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.InMemoryLogRepository
import app.proxyflow.core.data.InMemoryProtectionStore
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.data.ProtectionStore
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.TrafficStats
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.cancellation.CancellationException
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/**
 * The VPN layer of one process (ADR-0010, ADR-0012): the connection state, the one
 * [VpnSession], the diagnostic log it writes, the phone's network it is told about, and
 * the [ConnectionController] the screens use. The app builds it once (AppContainer) and
 * hands it to [ProxyFlowVpnService], which Android creates, through the [Provider] its
 * Application implements.
 *
 * @param scope the app's scope; the session runs on a serial view of the I/O dispatcher inside it.
 * @param proxyTrust replaces the platform trust manager that checks TLS proxies' certificates.
 *   Only the device tests pass one (a platform trust manager that also trusts their run's test
 *   CA, ADR-0011); the app never does, so certificates are always checked as ADR-0005 says.
 * @param logs where the session's diagnostic log entries go (the Logs screen's).
 * @param retry how the session reconnects on its own, read each time it starts to. Only the device
 *   tests pass one (shorter waits); the app always uses [RetryPolicy]'s default.
 * @param protection what outlives the process about the connection (M8, ADR-0013): Android's kill switch as last
 *   reported, the connected marker, an interrupted connection the user has not seen yet.
 */
class VpnRuntime(
    context: Context,
    private val profiles: ProfileRepository,
    scope: CoroutineScope,
    proxyTrust: (() -> X509TrustManager)? = null,
    logs: LogRepository = InMemoryLogRepository(),
    retry: () -> RetryPolicy = { RetryPolicy() },
    val protection: ProtectionStore = InMemoryProtectionStore(),
    private val engine: TunnelEngine = if (proxyTrust == null) ProxyTunnelEngine() else ProxyTunnelEngine(trust = proxyTrust),
) {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(ConnectionState.Idle)

    /** What the screens and the notification show. Written only by the session. */
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    /** The session's and the service's lifecycle, for diagnosis (no credentials, server names or payloads). */
    val log = VpnLog()

    /** Writes the session's entries off its thread, in batches (ADR-0012). */
    private val recorder = DiagnosticsRecorder(
        repository = logs,
        scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.IO),
    )

    val session = VpnSession(
        profiles = profiles,
        engine = engine,
        services = { startService() },
        state = mutableState,
        scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + SERIAL),
        log = log::add,
        retry = retry,
        diagnostics = recorder,
        protection = protection,
        history = AndroidProcessHistory(appContext),
    )

    val controller: ConnectionController = VpnConnectionController(this)

    /**
     * "Test connection" (M9, ADR-0014): the session's own pre-flight check on the same engine — and so with the same
     * trust for TLS proxies — without the VPN.
     */
    val tester: ConnectionTester = EngineConnectionTester(profiles, engine)

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

    /**
     * Home's session counters (ADR-0012): while there is a connected session — also while it reconnects
     * on its own or waits for a network — what it carried, read once a second for as long as someone
     * collects; null otherwise. Reading never waits for the session.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val traffic: Flow<TrafficStats?> = state
        .map { it.connectedSinceMillis != null }
        .distinctUntilChanged()
        .flatMapLatest { live ->
            if (!live) {
                flowOf<TrafficStats?>(null)
            } else {
                flow<TrafficStats?> {
                    while (true) {
                        emit(session.traffic())
                        delay(TRAFFIC_INTERVAL_MS)
                    }
                }
            }
        }
        .flowOn(Dispatchers.IO)

    init {
        // The phone's network, for the life of the process: the session reconnects when it changes (ADR-0012).
        if (!NetworkMonitor(appContext).start(session::underlyingNetwork)) log.add("network changes are not reported")

        // A deleted profile's credentials must not stay in use: the connection using it stops its engine (J11).
        // Room is watched only while a connection exists.
        scope.launch {
            mutableState.map { s -> s.profileId?.takeIf { s.active } }.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                profiles.profiles.first { list -> list.none { it.id == id } }
                session.profileDeleted(id)
            }
        }
        // A rename shows at once on the connection using the profile — Home, the tile, the notification: a name is not
        // a setting the engine runs (M9). Watched the same way.
        scope.launch {
            mutableState.map { s -> s.profileId?.takeIf { s.active } }.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                profiles.profiles.mapNotNull { list -> list.firstOrNull { it.id == id }?.name }.distinctUntilChanged()
                    .collect { name -> session.profileRenamed(id, name) }
            }
        }
    }

    /** The profile Home shows and connects while nothing runs: the active one, for the Quick Settings tile (B11, M9). */
    internal val shownProfile: Flow<ProxyProfile?> =
        combine(profiles.profiles, profiles.activeProfileId) { list, id -> list.firstOrNull { it.id == id } }

    /**
     * Connects [profileId] as Home's Connect does (the tile, M9): connecting a profile is the user's choice of it (M8),
     * which Always-on VPN connects later.
     */
    internal suspend fun connectChosen(profileId: String) {
        session.connect(profileId)
        try {
            if (profiles.chosenProfileId.first() != profileId) profiles.setActive(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not saved: the connection goes on; Home connects the same profile next time unless another is chosen.
            log.add("tile: the choice was not saved (${e.javaClass.simpleName})")
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
        const val TRAFFIC_INTERVAL_MS = 1_000L
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

    override fun reconnect(profileId: String) = runtime.session.reconnect(profileId)

    override fun switchIfConnected(profileId: String) = runtime.session.switchIfActive(profileId)

    override fun disconnectIfConnected() = runtime.session.disconnectIfConnected()

    override fun clearError() = runtime.session.clearError()

    override val diagnostics: Flow<RoutingDiagnostics?> get() = runtime.diagnostics

    override val traffic: Flow<TrafficStats?> get() = runtime.traffic

    override fun retryNow() = runtime.session.retryNow()

    override fun checkSystemKillSwitch() = runtime.session.checkSystemKillSwitch()

    override fun dismissInterruption(shown: Interruption) = runtime.session.dismissInterruption(shown)
}
