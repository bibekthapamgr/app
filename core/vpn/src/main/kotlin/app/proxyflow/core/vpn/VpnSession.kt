package app.proxyflow.core.vpn

import androidx.annotation.VisibleForTesting
import app.proxyflow.core.data.CredentialsRead
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.engine.EngineException
import app.proxyflow.core.engine.EngineSecret
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The connection state machine (ADR-0010): one per process. Every transition —
 * connect, pre-flight result, establish, engine start, Android routing apps into
 * the interface, engine failure, revocation, disconnect — is an event handled one
 * at a time by one coroutine on [scope], which must be serial (one thread, or
 * `limitedParallelism(1)`), so no two transitions can interleave. Blocking engine
 * calls happen there, never on the main thread; only the pre-flight check and the
 * wait for Android's routing run on [blocking], so a disconnect or a revocation is
 * never stuck behind them.
 *
 * The session owns the VPN interface — one per connection: the engine reads it, the
 * session closes it after the engine has stopped. A switch or a restart stops the
 * engine and starts the next one on the same interface, which keeps holding apps'
 * traffic meanwhile; a new interface would make Android bring the old one down before
 * the new one's routes exist, and Android's fall-through rule would send apps to the
 * normal network in between. Connected is reported only once the engine runs and
 * Android routes apps into the interface. Until the user has been connected, any
 * failure takes the interface down again and leaves the phone as it was; after that,
 * a failure never removes it: traffic stays held until the user restarts or
 * disconnects ("Stop and use normal network", docs/m1/03-user-journeys.md).
 *
 * @param state written only here; the screens and the notification read it.
 * @param services starts [ProxyFlowVpnService], which then attaches itself as the [VpnHost].
 * @param log the lifecycle, without credentials, server names or payloads.
 */
class VpnSession(
    private val profiles: ProfileRepository,
    private val engine: TunnelEngine,
    private val services: ServiceStarter,
    private val state: MutableStateFlow<ConnectionState>,
    private val scope: CoroutineScope,
    private val blocking: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timing: Timing = Timing(),
    private val log: (String) -> Unit = {},
) {
    /** Starts the VPN service; false when Android refused (for example, the app is in the background). */
    fun interface ServiceStarter {
        fun start(): Boolean
    }

    /**
     * @param preflightTimeoutMillis the whole pre-flight check: TCP, TLS and the proxy handshake.
     * @param serviceStartTimeoutMillis how long a connection waits for the service to attach.
     * @param routingTimeoutMillis how long Android may take to route apps into a new interface.
     * @param routingPollMillis how often Android's VPN networks are looked at meanwhile.
     */
    data class Timing(
        val preflightTimeoutMillis: Long = 15_000,
        val serviceStartTimeoutMillis: Long = 10_000,
        val routingTimeoutMillis: Long = 10_000,
        val routingPollMillis: Long = 50,
    )

    private val events = Channel<Event>(Channel.UNLIMITED)

    // A job that fails in a way nothing caught must not take the process down with it (which would drop held
    // traffic): it becomes a failure of the attempt it belongs to, and is only logged once that attempt is over.
    // Every job catches what it expects; this is the last resort.
    private fun failuresOf(attempt: Long) = CoroutineExceptionHandler { _, e -> send(Event.JobFailed(attempt, e.javaClass.simpleName)) }

    // Touched only by the event loop.
    private var host: VpnHost? = null
    private var pending: ProxyProfile? = null
    private var serviceWait: Job? = null
    private var serviceToken = 0L
    private var attempt = 0L
    private var preflight: Job? = null
    private var routing: Job? = null
    private var tun: TunInterface? = null
    private var engineRunning = false
    private var engineGeneration = 0L

    /** The user has been connected since the session last ended: failures hold traffic instead of releasing it. */
    private var connectedOnce = false

    init {
        // dispatch() turns every failure into a failed attempt; should the loop still end, the process stays up.
        scope.launch(CoroutineExceptionHandler { _, e -> log("event loop ended: ${e.javaClass.simpleName}") }) {
            for (event in events) dispatch(event)
        }
    }

    /** Connects [profileId]. Nothing happens while it is already connecting or connected; another profile replaces the session. */
    fun connect(profileId: String) = send(Event.Connect(profileId))

    /** Runs the connect sequence again for the blocked profile (Restart, Try again). */
    fun restart() = send(Event.Restart)

    /** Disconnects, cancels a connection being set up, or stops a blocked one. */
    fun disconnect() = send(Event.Disconnect)

    /** Forgets the last error after a connection attempt ended; a blocked connection keeps its error. */
    fun clearError() = send(Event.ClearError)

    /** The service started (or Android started it for Always-on VPN) and can establish interfaces. */
    fun attach(host: VpnHost, alwaysOn: Boolean) = send(Event.Attached(host, alwaysOn))

    /** The service was destroyed. */
    fun detached(host: VpnHost) = send(Event.Detached(host))

    /** Android revoked the VPN: another VPN app started, or the user removed the permission. */
    fun revoked(host: VpnHost) = send(Event.Revoked(host))

    /** A profile was deleted; when it is the connection's, its credentials are no longer used (J11). */
    fun profileDeleted(profileId: String) = send(Event.ProfileDeleted(profileId))

    /**
     * Coroutines the session runs besides its event loop and that have not finished: the
     * pre-flight check (also a cancelled one whose engine call is still returning), the wait
     * for Android's routing and the wait for the service. 0 whenever no connection is being
     * set up (lifecycle tests).
     */
    @VisibleForTesting
    fun activeJobs(): Int = (scope.coroutineContext[Job]?.children?.count { !it.isCompleted } ?: 1) - 1

    /** Returns once every event sent before it has been handled (tests). */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        send(Event.Barrier(done))
        done.await()
    }

    private fun send(event: Event) {
        events.trySend(event)
    }

    private suspend fun dispatch(event: Event) {
        try {
            when (event) {
                is Event.Connect -> onConnect(event.profileId)
                Event.Restart -> onRestart()
                Event.Disconnect -> onDisconnect()
                Event.ClearError -> if (state.value.status == ConnectionStatus.Disconnected) publish(state.value.copy(error = null))
                is Event.Attached -> onAttached(event.host, event.alwaysOn)
                is Event.Detached -> onDetached(event.host)
                is Event.Revoked -> onRevoked()
                is Event.ProfileDeleted -> onProfileDeleted(event.profileId)
                is Event.PreflightDone -> onPreflightDone(event)
                is Event.Routed -> onRouted(event.attempt)
                is Event.RoutingTimedOut -> onRoutingTimedOut(event.attempt)
                is Event.EngineFailed -> onEngineFailed(event)
                is Event.JobFailed -> onJobFailed(event)
                is Event.ServiceStartTimedOut -> onServiceStartTimedOut(event.token)
                is Event.Barrier -> event.done.complete(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A bug, a platform call that threw where none was expected, the engine library failing to load, or an
            // Error: the attempt fails (held traffic stays held) instead of the event loop ending or the process dying.
            unexpected(e)
        }
    }

    // The class name only: messages may carry data.
    private fun unexpected(e: Throwable) {
        log("unexpected ${e.javaClass.simpleName}")
        runCatching { fail(ConnectionError(ConnectionError.INTERNAL, "unexpected ${e.javaClass.simpleName}")) }
    }

    private suspend fun onConnect(profileId: String) {
        val current = state.value
        if (current.profileId == profileId && current.status in BUSY) return
        beginConnect(profileId)
    }

    private suspend fun onRestart() {
        val current = state.value
        val profileId = current.profileId
        if (current.status == ConnectionStatus.Blocked && profileId != null) beginConnect(profileId)
    }

    private fun onDisconnect() {
        if (state.value.active || tun != null) log("disconnect")
        end(error = null)
    }

    private suspend fun beginConnect(profileId: String) {
        // Looked up before anything is stopped: a profile that is gone leaves a running connection alone.
        val profile = profiles.get(profileId) ?: return profileMissing(profileId)
        cancelAttempt()
        // Switching or restarting: flows end now. Once connected, the interface stays, so traffic is held until the next engine runs on it.
        stopEngine()
        if (!connectedOnce) closeInterface() // an attempt that never connected leaves nothing behind
        val reconnecting = tun != null
        log("connect ${profile.id}${if (reconnecting) " (reconnect)" else ""}")
        publish(
            ConnectionState(
                status = if (reconnecting) ConnectionStatus.Reconnecting else ConnectionStatus.Connecting,
                profileId = profile.id,
                profileName = profile.displayName,
                profile = profile,
                step = ConnectStep.CheckingProxy,
            ),
        )
        val current = host
        if (current == null) waitForService(profile) else runPreflight(current, profile)
    }

    private fun profileMissing(profileId: String) {
        log("connect $profileId: no such profile")
        val current = state.value
        when {
            // The connection's own profile (a restart, or a re-check after an edit): as when it is deleted.
            current.active && current.profileId == profileId -> onProfileDeleted(profileId)
            // Another connection runs or is being set up: it is not touched.
            current.active -> Unit
            else -> publish(ConnectionState(profileId = profileId, error = ConnectionError(ConnectionError.INTERNAL, PROFILE_GONE)))
        }
    }

    private fun waitForService(profile: ProxyProfile) {
        pending = profile
        if (serviceWait?.isActive == true) return
        if (!services.start()) {
            pending = null
            log("service start refused")
            return fail(ConnectionError(ConnectionError.INTERNAL, "Android did not start the VPN service"))
        }
        val token = ++serviceToken
        serviceWait = scope.launch(failuresOf(attempt)) {
            delay(timing.serviceStartTimeoutMillis)
            send(Event.ServiceStartTimedOut(token))
        }
    }

    private fun onServiceStartTimedOut(token: Long) {
        if (token != serviceToken || pending == null) return
        log("service did not attach")
        end(ConnectionError(ConnectionError.INTERNAL, "the VPN service did not start"))
    }

    private suspend fun onAttached(attached: VpnHost, alwaysOn: Boolean) {
        host = attached
        serviceWait?.cancel()
        serviceWait = null
        val waiting = pending
        pending = null
        when {
            waiting != null -> runPreflight(attached, waiting)
            state.value.active -> attached.showStatus(state.value)
            alwaysOn -> {
                // Always-on VPN: only the profile the user explicitly chose, never a fallback (ADR-0010 §8).
                val chosen = profiles.chosenProfileId.first()
                log("always-on start${if (chosen == null) ": no chosen profile" else ""}")
                if (chosen != null) beginConnect(chosen) else releaseHost()
            }
            else -> releaseHost() // started for a connection that was cancelled meanwhile
        }
    }

    private fun onDetached(detached: VpnHost) {
        if (host !== detached) return
        host = null
        // Android keeps a VPN service bound while its interface exists, so this happens with a
        // connection only on the way to process death; release what is left.
        if (tun != null || preflight != null || routing != null || pending != null) {
            log("service destroyed during a connection")
            end(ConnectionError(ConnectionError.INTERNAL, "the VPN service stopped"))
        }
    }

    private fun runPreflight(current: VpnHost, profile: ProxyProfile) {
        // No consent: the user withdrew it, so Android holds no VPN of ours that could hold traffic. (Another VPN app
        // taking over arrives as a revocation instead; for an app the user allowed before, prepare() takes it back.)
        if (!current.hasConsent()) return end(ConnectionError(ConnectionError.VPN_PERMISSION_DENIED))
        current.clearProblem()
        if (!current.showStatus(state.value)) {
            return fail(ConnectionError(ConnectionError.INTERNAL, "Android did not allow the foreground service"))
        }
        val id = ++attempt
        val guard = current.socketGuard()
        preflight = scope.launch(blocking + failuresOf(id)) {
            val check = coroutineContext.job
            // Cancelled (Disconnect, another profile): the check's sockets are shut down, so the engine returns at once.
            // Started at once and resumed in whichever thread cancels, so this holds even when every thread of
            // [blocking] is busy, the check's own included.
            val aborter = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (check.isCancelled) guard.abort()
                }
            }
            val result = try {
                runCheck(current, profile, guard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Check.Failed(ConnectionError(ConnectionError.INTERNAL, "unexpected ${e.javaClass.simpleName} in the proxy check"))
            } catch (e: LinkageError) {
                Check.Failed(ConnectionError(ConnectionError.INTERNAL, "the packet engine could not be loaded"))
            } finally {
                aborter.cancel()
                guard.close()
            }
            send(Event.PreflightDone(id, profile, result))
        }
    }

    /** Reads the credentials here, so a check cancelled before it ran never decrypts them, and runs the probe. */
    private suspend fun runCheck(current: VpnHost, profile: ProxyProfile, guard: SocketGuard): Check {
        val secret = when (val read = readSecret(profile)) {
            is SecretRead.Ok -> read.secret
            is SecretRead.Failed -> return Check.Failed(read.error)
            SecretRead.Changed -> return Check.ProfileChanged
        }
        val answer = try {
            engine.probe(profile, secret, PREFLIGHT_TARGET, timing.preflightTimeoutMillis) { fd ->
                guard.track(fd)
                current.protectSocket(fd)
            }
        } catch (e: Exception) {
            PreflightAnswer(ok = false, code = ConnectionError.INTERNAL, detail = "unexpected ${e.javaClass.simpleName}")
        } catch (e: LinkageError) {
            PreflightAnswer(ok = false, code = ConnectionError.INTERNAL, detail = "the packet engine could not be loaded")
        } finally {
            secret?.close() // the probe consumed it already; this covers a probe that never ran
        }
        return Check.Answered(answer)
    }

    private suspend fun onPreflightDone(event: Event.PreflightDone) {
        if (event.attempt != attempt) return // cancelled, or a newer connect replaced it
        preflight = null
        val profile = event.profile
        when (val check = event.check) {
            Check.ProfileChanged -> return checkAgain(profile.id)
            is Check.Failed -> return fail(check.error)
            is Check.Answered -> {
                log("check ${profile.id}: ${check.answer.code ?: "ok"}")
                if (!check.answer.passes) return fail(check.answer.error)
            }
        }
        val current = host ?: return fail(ConnectionError(ConnectionError.INTERNAL, "the VPN service stopped"))
        publish(state.value.copy(step = ConnectStep.StartingProtection))
        current.showStatus(state.value)

        // Read before the interface exists, so nothing can fail between establishing it and starting the engine but the engine.
        val secret = when (val read = readSecret(profile)) {
            is SecretRead.Ok -> read.secret
            is SecretRead.Failed -> return fail(read.error)
            SecretRead.Changed -> return checkAgain(profile.id)
        }
        try {
            startProtection(current, profile, secret)
        } finally {
            secret?.close() // the engine consumed it; this wipes one that was never handed over
        }
    }

    /**
     * The profile's server, protocol, TLS or sign-in changed while connecting: its new settings
     * are checked from the start, so a new password never goes to the old server.
     */
    private suspend fun checkAgain(profileId: String) {
        log("profile $profileId changed while connecting")
        beginConnect(profileId)
    }

    private fun startProtection(current: VpnHost, profile: ProxyProfile, secret: EngineSecret?) {
        val config = engine.tunConfig()
        // A switch or a restart keeps the interface; only a first connect creates one.
        val existing = tun
        if (existing != null) {
            // Held means dropped: what apps sent meanwhile must not reach the next proxy later.
            val dropped = existing.drain()
            if (dropped > 0) log("dropped $dropped packets held while no engine ran")
        }
        val before = if (existing != null) {
            emptyList()
        } else {
            try {
                current.vpnNetworks()
            } catch (e: Exception) {
                // Without Android's view from before the interface exists, a network still being torn down could pass for its.
                return fail(ConnectionError(ConnectionError.INTERNAL, "Android's networks could not be read"))
            }
        }
        val interfaceForEngine = existing ?: run {
            val fresh = try {
                current.establish(config)
            } catch (e: Exception) {
                return fail(ConnectionError(ConnectionError.INTERNAL, "Android could not create the VPN interface"))
            }
            // Not prepared any more: the user withdrew consent, or another VPN app took over (its revocation follows).
            if (fresh == null) return end(ConnectionError(ConnectionError.VPN_PERMISSION_DENIED))
            log("established ${fresh.name ?: "an interface"}")
            tun = fresh
            fresh
        }

        val generation = ++engineGeneration
        val notStarted = try {
            engine.start(interfaceForEngine, config, profile, secret, current::protectSocket) { error ->
                send(Event.EngineFailed(generation, error))
            }
            null
        } catch (e: EngineException) {
            ConnectionError(e.code, e.detail)
        } catch (e: Exception) {
            ConnectionError(ConnectionError.INTERNAL, "unexpected ${e.javaClass.simpleName}")
        } catch (e: LinkageError) {
            ConnectionError(ConnectionError.INTERNAL, "the packet engine could not be loaded")
        }
        if (notStarted != null) {
            log("engine did not start: ${notStarted.code}")
            return fail(notStarted)
        }
        engineRunning = true
        awaitRouting(current, interfaceForEngine, before)
    }

    /**
     * `establish()` returns before Android has created the VPN network and its routing rules,
     * so apps' traffic still takes the normal network for a moment. Connected waits until
     * Android reports the interface's network ready — at once on a switch or a restart, whose
     * interface Android already routes apps into.
     */
    private fun awaitRouting(current: VpnHost, routedTun: TunInterface, before: List<VpnNetwork>) {
        val id = attempt
        routing = scope.launch(blocking + failuresOf(id)) {
            val routed = withTimeoutOrNull(timing.routingTimeoutMillis) {
                while (!routedNow(current, routedTun, before)) delay(timing.routingPollMillis)
                true
            } ?: false
            send(if (routed) Event.Routed(id) else Event.RoutingTimedOut(id))
        }
    }

    /** A look at Android's view that fails (its service restarting, say) never counts as routed; the wait goes on. */
    private fun routedNow(current: VpnHost, routedTun: TunInterface, before: List<VpnNetwork>): Boolean = try {
        routedInto(routedTun, before, current.vpnNetworks())
    } catch (e: Exception) {
        false
    }

    private fun onRouted(id: Long) {
        if (id != attempt || routing == null) return
        routing = null
        connectedOnce = true
        log("connected ${state.value.profileId}")
        publish(state.value.copy(status = ConnectionStatus.Connected, step = null, connectedSinceMillis = clock(), error = null))
        host?.showStatus(state.value)
    }

    private fun onRoutingTimedOut(id: Long) {
        if (id != attempt || routing == null) return
        routing = null
        log("Android did not route apps into the interface")
        fail(ConnectionError(ConnectionError.INTERNAL, "Android did not route apps into the VPN"))
    }

    private fun onEngineFailed(event: Event.EngineFailed) {
        if (event.generation != engineGeneration || !engineRunning) return
        log("engine failed: ${event.error.code}")
        fail(event.error)
    }

    private fun onJobFailed(event: Event.JobFailed) {
        log("unexpected ${event.type} in a session job${if (event.attempt != attempt) " of an attempt that is over" else ""}")
        // A cancelled or replaced attempt's job failing late must not touch the connection that followed.
        if (event.attempt != attempt) return
        fail(ConnectionError(ConnectionError.INTERNAL, "unexpected ${event.type} in a background job"))
    }

    private fun onRevoked() {
        if (!state.value.active && tun == null) return
        val revokedHost = host
        log("revoked by Android")
        // Android has already removed the interface; close the descriptor and stop.
        end(ConnectionError(ConnectionError.VPN_REVOKED))
        revokedHost?.notifyProblem(state.value)
    }

    private fun onProfileDeleted(profileId: String) {
        val current = state.value
        if (!current.active || current.profileId != profileId) return
        log("profile $profileId deleted")
        // Its credentials are never used again: the engine stops. Once connected, traffic stays held until the user
        // stops; the profile list's "Disconnect and delete" disconnects first, so this is a safety net.
        if (connectedOnce) block(ConnectionError(ConnectionError.PROFILE_DELETED)) else end(error = null)
    }

    /** Fails the current attempt: blocked once the user has been connected, otherwise back to Disconnected with nothing left. */
    private fun fail(error: ConnectionError) {
        if (connectedOnce) block(error) else end(error)
    }

    /** The interface stays up with no engine reading it: apps' traffic is held, never sent around the proxy. */
    private fun block(error: ConnectionError) {
        cancelAttempt()
        stopEngine()
        log("blocked: ${error.code}")
        publish(state.value.copy(status = ConnectionStatus.Blocked, step = null, connectedSinceMillis = null, error = error))
        host?.showStatus(state.value)
    }

    /** Back to the normal network: engine stopped, interfaces closed, service stopped. */
    private fun end(error: ConnectionError?) {
        cancelAttempt()
        pending = null
        serviceWait?.cancel()
        serviceWait = null
        stopEngine()
        closeInterface()
        connectedOnce = false
        if (state.value.status != ConnectionStatus.Disconnected) log("disconnected${error?.let { ": ${it.code}" } ?: ""}")
        publish(state.value.copy(status = ConnectionStatus.Disconnected, step = null, connectedSinceMillis = null, error = error))
        releaseHost()
    }

    private fun cancelAttempt() {
        attempt++
        preflight?.cancel()
        preflight = null
        routing?.cancel()
        routing = null
    }

    private fun stopEngine() {
        if (!engineRunning) return
        engineRunning = false
        runCatching { engine.stop() }
    }

    private fun closeInterface() {
        tun?.close()
        tun = null
    }

    private fun releaseHost() {
        host?.stop()
        host = null
    }

    private fun publish(next: ConnectionState) {
        state.value = next
    }

    private suspend fun readSecret(profile: ProxyProfile): SecretRead = when (val read = profiles.readCredentials(profile.id, profile)) {
        CredentialsRead.NotRequired -> SecretRead.Ok(null)
        is CredentialsRead.Available -> try {
            SecretRead.Ok(EngineSecret.of(read.username, read.password))
        } catch (e: IllegalArgumentException) {
            SecretRead.Failed(ConnectionError(ConnectionError.CREDENTIAL_UNREADABLE))
        } finally {
            read.wipe()
        }
        CredentialsRead.ProfileChanged -> SecretRead.Changed
        CredentialsRead.Unreadable -> SecretRead.Failed(ConnectionError(ConnectionError.CREDENTIAL_UNREADABLE))
        CredentialsRead.TemporarilyUnavailable ->
            SecretRead.Failed(ConnectionError(ConnectionError.INTERNAL, "the Android Keystore is not available right now"))
        CredentialsRead.NoSuchProfile -> SecretRead.Failed(ConnectionError(ConnectionError.PROFILE_DELETED))
    }

    private sealed interface SecretRead {
        class Ok(val secret: EngineSecret?) : SecretRead

        class Failed(val error: ConnectionError) : SecretRead

        data object Changed : SecretRead
    }

    /** How a pre-flight check ended. */
    private sealed interface Check {
        class Answered(val answer: PreflightAnswer) : Check

        class Failed(val error: ConnectionError) : Check

        data object ProfileChanged : Check
    }

    private sealed interface Event {
        data class Connect(val profileId: String) : Event

        data object Restart : Event

        data object Disconnect : Event

        data object ClearError : Event

        class Attached(val host: VpnHost, val alwaysOn: Boolean) : Event

        class Detached(val host: VpnHost) : Event

        class Revoked(val host: VpnHost) : Event

        data class ProfileDeleted(val profileId: String) : Event

        class PreflightDone(val attempt: Long, val profile: ProxyProfile, val check: Check) : Event

        class Routed(val attempt: Long) : Event

        class RoutingTimedOut(val attempt: Long) : Event

        class EngineFailed(val generation: Long, val error: ConnectionError) : Event

        class JobFailed(val attempt: Long, val type: String) : Event

        class ServiceStartTimedOut(val token: Long) : Event

        class Barrier(val done: CompletableDeferred<Unit>) : Event
    }

    companion object {
        /**
         * What the pre-flight check opens a tunnel to (ADR-0010 §4): the host Android's own
         * connectivity check uses, on the port proxies allow by default. No byte is sent
         * through it. An engineering default inside open decision D6.
         */
        const val PREFLIGHT_TARGET = "connectivitycheck.gstatic.com:443"

        private const val PROFILE_GONE = "the profile no longer exists"
        private val BUSY = setOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting, ConnectionStatus.Connected)

        /**
         * Android routes apps into [tun]: one of its VPN networks is ready and leads into
         * [tun]'s interface, and was not already there, on that interface, before [tun] was
         * established ([before] is empty for an interface that was already in use). That
         * leaves out a network of an earlier connection still being torn down.
         */
        internal fun routedInto(tun: TunInterface, before: List<VpnNetwork>, now: List<VpnNetwork>): Boolean = now.any { network ->
            network.ready &&
                (tun.name == null || network.interfaceName == tun.name) &&
                before.none { it.id == network.id && it.interfaceName == network.interfaceName }
        }
    }
}
