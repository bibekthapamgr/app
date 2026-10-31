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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The connection state machine (ADR-0010): one per process. Every transition —
 * connect, pre-flight result, establish, engine start, engine failure,
 * revocation, disconnect — is an event handled one at a time by one coroutine
 * on [scope], which must be serial (one thread, or `limitedParallelism(1)`),
 * so no two transitions can interleave. Blocking engine calls happen there,
 * never on the main thread; only the pre-flight check runs on [blocking], so a
 * disconnect or a revocation is not stuck behind it.
 *
 * The session owns the VPN interface: the engine reads it, the session closes it
 * after the engine has stopped. It never reports Connected before the engine
 * runs on a new interface, and once an interface is up it never removes it on
 * a failure: traffic stays blocked until the user restarts or disconnects
 * ("Stop and use normal network", docs/m1/03-user-journeys.md).
 *
 * @param state written only here; the screens and the notification read it.
 * @param services starts [ProxyFlowVpnService], which then attaches itself as the [VpnHost].
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
) {
    /** Starts the VPN service; false when Android refused (for example, the app is in the background). */
    fun interface ServiceStarter {
        fun start(): Boolean
    }

    /**
     * @param preflightTimeoutMillis the whole pre-flight check: TCP, TLS and the proxy handshake.
     * @param serviceStartTimeoutMillis how long a connection waits for the service to attach.
     */
    data class Timing(val preflightTimeoutMillis: Long = 15_000, val serviceStartTimeoutMillis: Long = 10_000)

    private val events = Channel<Event>(Channel.UNLIMITED)

    // Touched only by the event loop.
    private var host: VpnHost? = null
    private var pending: ProxyProfile? = null
    private var serviceWait: Job? = null
    private var serviceToken = 0L
    private var attempt = 0L
    private var preflight: Job? = null
    private var tun: TunInterface? = null
    private var engineRunning = false
    private var engineGeneration = 0L

    init {
        scope.launch { for (event in events) dispatch(event) }
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

    /** A profile was deleted; when it is the connection's, the connection ends (J11). */
    fun profileDeleted(profileId: String) = send(Event.ProfileDeleted(profileId))

    /**
     * Coroutines the session runs besides its event loop: the pre-flight check and the
     * wait for the service. 0 whenever no connection is being set up (lifecycle tests).
     */
    @VisibleForTesting
    fun activeJobs(): Int = (scope.coroutineContext[Job]?.children?.count { it.isActive } ?: 1) - 1

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
                Event.Disconnect -> end(error = null)
                Event.ClearError -> if (state.value.status == ConnectionStatus.Disconnected) publish(state.value.copy(error = null))
                is Event.Attached -> onAttached(event.host, event.alwaysOn)
                is Event.Detached -> onDetached(event.host)
                is Event.Revoked -> onRevoked()
                is Event.ProfileDeleted -> if (state.value.active && state.value.profileId == event.profileId) end(error = null)
                is Event.PreflightDone -> onPreflightDone(event)
                is Event.EngineFailed -> onEngineFailed(event)
                is Event.ServiceStartTimedOut -> onServiceStartTimedOut(event.token)
                is Event.Barrier -> event.done.complete(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
        } catch (e: LinkageError) {
            unexpected(e) // the engine library failed to load
        }
    }

    // A bug, or a platform call that threw where none was expected. The class name only: messages may carry data.
    private fun unexpected(e: Throwable) {
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

    private suspend fun beginConnect(profileId: String) {
        cancelAttempt()
        val profile = profiles.get(profileId) ?: return fail(ConnectionError(ConnectionError.INTERNAL, PROFILE_GONE))
        val reconnecting = tun != null
        // Switching or restarting: flows end now, and the interface stays, so traffic is held until the new engine runs.
        stopEngine()
        publish(
            ConnectionState(
                status = if (reconnecting) ConnectionStatus.Reconnecting else ConnectionStatus.Connecting,
                profileId = profile.id,
                profileName = profile.displayName,
                step = ConnectStep.CheckingProxy,
            ),
        )
        val current = host
        if (current == null) waitForService(profile) else runPreflight(current, profile)
    }

    private fun waitForService(profile: ProxyProfile) {
        pending = profile
        if (serviceWait?.isActive == true) return
        if (!services.start()) {
            pending = null
            return fail(ConnectionError(ConnectionError.INTERNAL, "Android did not start the VPN service"))
        }
        val token = ++serviceToken
        serviceWait = scope.launch {
            delay(timing.serviceStartTimeoutMillis)
            send(Event.ServiceStartTimedOut(token))
        }
    }

    private fun onServiceStartTimedOut(token: Long) {
        if (token != serviceToken || pending == null) return
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
        if (tun != null || preflight != null || pending != null) {
            end(ConnectionError(ConnectionError.INTERNAL, "the VPN service stopped"))
        }
    }

    private suspend fun runPreflight(current: VpnHost, profile: ProxyProfile) {
        if (!current.hasConsent()) return fail(ConnectionError(ConnectionError.VPN_PERMISSION_DENIED))
        current.clearProblem()
        if (!current.showStatus(state.value)) {
            return fail(ConnectionError(ConnectionError.INTERNAL, "Android did not allow the foreground service"))
        }
        val secret = when (val read = readSecret(profile.id)) {
            is SecretRead.Failed -> return fail(read.error)
            is SecretRead.Ok -> read.secret
        }
        val id = ++attempt
        preflight = scope.launch(blocking) {
            val answer = try {
                engine.probe(profile, secret, PREFLIGHT_TARGET, timing.preflightTimeoutMillis, current::protectSocket)
            } catch (e: Exception) {
                PreflightAnswer(ok = false, code = ConnectionError.INTERNAL, detail = "unexpected ${e.javaClass.simpleName}")
            } catch (e: LinkageError) {
                PreflightAnswer(ok = false, code = ConnectionError.INTERNAL, detail = "the packet engine could not be loaded")
            } finally {
                secret?.close() // the probe consumed it already; this covers a probe that never ran
            }
            send(Event.PreflightDone(id, profile, answer))
        }
    }

    private suspend fun onPreflightDone(event: Event.PreflightDone) {
        if (event.attempt != attempt) return // cancelled, or a newer connect replaced it
        preflight = null
        if (!event.answer.passes) return fail(event.answer.error)
        val current = host ?: return fail(ConnectionError(ConnectionError.INTERNAL, "the VPN service stopped"))
        val profile = event.profile
        publish(state.value.copy(step = ConnectStep.StartingProtection))
        current.showStatus(state.value)

        // Read before the interface exists, so nothing can fail between establishing it and starting the engine but the engine.
        val secret = when (val read = readSecret(profile.id)) {
            is SecretRead.Failed -> return fail(read.error)
            is SecretRead.Ok -> read.secret
        }
        try {
            val config = engine.tunConfig()
            val fresh = try {
                current.establish(config)
            } catch (e: Exception) {
                return fail(ConnectionError(ConnectionError.INTERNAL, "Android could not create the VPN interface"))
            }
            // No consent any more: Android revoked the VPN or another app took over, so any old interface is gone too.
            if (fresh == null) return end(ConnectionError(ConnectionError.VPN_PERMISSION_DENIED))
            val previous = tun
            tun = fresh // Android routes traffic into the new interface from here; an old one is deactivated

            val generation = ++engineGeneration
            try {
                engine.start(fresh, config, profile, secret, current::protectSocket) { error ->
                    send(Event.EngineFailed(generation, error))
                }
            } catch (e: EngineException) {
                return failWithoutEngine(previous, fresh, ConnectionError(e.code, e.detail))
            }
            engineRunning = true
            previous?.close()
            publish(state.value.copy(status = ConnectionStatus.Connected, step = null, connectedSinceMillis = clock(), error = null))
            current.showStatus(state.value)
        } finally {
            secret?.close() // the engine consumed it; this wipes one that was never handed over
        }
    }

    /** The new interface is up but no engine runs on it. */
    private fun failWithoutEngine(previous: TunInterface?, fresh: TunInterface, error: ConnectionError) {
        if (previous == null) {
            // Never connected: take the new interface down again, as when the check fails (board B6b).
            tun = null
            fresh.close()
            end(error)
        } else {
            // A reconnect: the new interface stays without an engine, so traffic stays held.
            previous.close()
            block(error)
        }
    }

    private fun onEngineFailed(event: Event.EngineFailed) {
        if (event.generation != engineGeneration || !engineRunning) return
        block(event.error)
    }

    private fun onRevoked() {
        if (!state.value.active && tun == null) return
        val revokedHost = host
        // Android has already removed the interface; close the descriptor and stop.
        end(ConnectionError(ConnectionError.VPN_REVOKED))
        revokedHost?.notifyProblem(state.value)
    }

    /** Fails the current attempt: blocked when an interface is up, otherwise back to Disconnected. */
    private fun fail(error: ConnectionError) {
        if (tun != null) block(error) else end(error)
    }

    /** The interface stays up with no engine reading it: apps' traffic is held, never sent around the proxy. */
    private fun block(error: ConnectionError) {
        cancelAttempt()
        stopEngine()
        publish(state.value.copy(status = ConnectionStatus.Blocked, step = null, connectedSinceMillis = null, error = error))
        host?.showStatus(state.value)
    }

    /** Back to the normal network: engine stopped, interface closed, service stopped. */
    private fun end(error: ConnectionError?) {
        cancelAttempt()
        pending = null
        serviceWait?.cancel()
        serviceWait = null
        stopEngine()
        tun?.close()
        tun = null
        publish(state.value.copy(status = ConnectionStatus.Disconnected, step = null, connectedSinceMillis = null, error = error))
        releaseHost()
    }

    private fun cancelAttempt() {
        attempt++
        preflight?.cancel()
        preflight = null
    }

    private fun stopEngine() {
        if (!engineRunning) return
        engineRunning = false
        runCatching { engine.stop() }
    }

    private fun releaseHost() {
        host?.stop()
        host = null
    }

    private fun publish(next: ConnectionState) {
        state.value = next
    }

    private suspend fun readSecret(profileId: String): SecretRead = when (val read = profiles.readCredentials(profileId)) {
        CredentialsRead.NotRequired -> SecretRead.Ok(null)
        is CredentialsRead.Available -> try {
            SecretRead.Ok(EngineSecret.of(read.username, read.password))
        } catch (e: IllegalArgumentException) {
            SecretRead.Failed(ConnectionError(ConnectionError.CREDENTIAL_UNREADABLE))
        } finally {
            read.wipe()
        }
        CredentialsRead.Unreadable -> SecretRead.Failed(ConnectionError(ConnectionError.CREDENTIAL_UNREADABLE))
        CredentialsRead.TemporarilyUnavailable ->
            SecretRead.Failed(ConnectionError(ConnectionError.INTERNAL, "the Android Keystore is not available right now"))
        CredentialsRead.NoSuchProfile -> SecretRead.Failed(ConnectionError(ConnectionError.INTERNAL, PROFILE_GONE))
    }

    private sealed interface SecretRead {
        class Ok(val secret: EngineSecret?) : SecretRead

        class Failed(val error: ConnectionError) : SecretRead
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

        class PreflightDone(val attempt: Long, val profile: ProxyProfile, val answer: PreflightAnswer) : Event

        class EngineFailed(val generation: Long, val error: ConnectionError) : Event

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
    }
}
