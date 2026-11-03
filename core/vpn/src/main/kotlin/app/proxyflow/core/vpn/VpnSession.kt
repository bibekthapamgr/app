package app.proxyflow.core.vpn

import androidx.annotation.VisibleForTesting
import app.proxyflow.core.data.ConnectedMarker
import app.proxyflow.core.data.CredentialsRead
import app.proxyflow.core.data.InMemoryProtectionStore
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.data.ProtectionStore
import app.proxyflow.core.engine.EngineException
import app.proxyflow.core.engine.EngineSecret
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ReconnectInfo
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.TrafficStats
import java.util.Locale
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
 * The connection state machine (ADR-0010, ADR-0012): one per process. Every transition —
 * connect, pre-flight result, establish, engine start, Android routing apps into
 * the interface, engine failure, a connection failing at the proxy, a network change,
 * a reconnection attempt, revocation, disconnect — is an event handled one
 * at a time by one coroutine on [scope], which must be serial (one thread, or
 * `limitedParallelism(1)`), so no two transitions can interleave. Blocking engine
 * calls happen there, never on the main thread; only the pre-flight check and the
 * wait for Android's routing run on [blocking], so a disconnect or a revocation is
 * never stuck behind them.
 *
 * The session owns the VPN interface — one per connection: the engine reads it, the
 * session closes it after the engine has stopped. A switch, a restart or a reconnection
 * stops the engine and starts the next one on the same interface, which keeps holding apps'
 * traffic meanwhile; a new interface would make Android bring the old one down before
 * the new one's routes exist, and Android's fall-through rule would send apps to the
 * normal network in between. Connected is reported only once the engine runs and
 * Android routes apps into the interface. Until the user has been connected, any
 * failure takes the interface down again and leaves the phone as it was; after that,
 * a failure never removes it: traffic stays held until the user restarts or
 * disconnects ("Stop and use normal network", docs/m1/03-user-journeys.md).
 *
 * Once connected (ADR-0012): connections failing at the proxy three times in a row, or the phone
 * getting a network back, make the session reconnect on its own — the engine stops, traffic is held,
 * and attempts follow [retry] with growing waits until one passes the pre-flight check (Connected
 * again, the session's time and traffic going on) or the attempts run out (Blocked). Only one
 * reconnection runs at a time: every transition cancels it, and "Retry now" only shortens its wait.
 * An answer no retry changes — the sign-in or the certificate refused — blocks at once. With no
 * network the session waits Offline, traffic held; a move to another network ends the engine's
 * connections so apps reconnect on the new one.
 *
 * Android's Always-on VPN (M8, ADR-0013): a start by Android connects only the profile the user chose, and holds
 * traffic from the start — the interface is up before the proxy is checked, and attempts that fail in a way that may
 * pass go on as after a connection that broke; with no chosen profile it connects nothing and says so. While an
 * interface exists the session reads what Android says of its kill switch (Always-on VPN, "Block connections without
 * VPN") and keeps it in [protection]; while connected, [protection] holds a marker the next process reads, so a
 * process that ended without the connection being stopped is found and logged with Android's reason ([history]).
 *
 * @param state written only here; the screens and the notification read it.
 * @param services starts [ProxyFlowVpnService], which then attaches itself as the [VpnHost].
 * @param log the lifecycle for logcat, without credentials, server names or payloads.
 * @param retry the reconnection policy, read when a reconnection starts.
 * @param random jitter for the reconnection waits, 0 ≤ random() < 1.
 * @param diagnostics the diagnostic log the Logs screen shows (sanitized again when written).
 * @param protection what outlives the process about the connection (M8): the kill switch as Android last reported
 *   it, the connected marker, an interruption the user has not seen yet. Written in order, off the event loop.
 * @param history this process's id and boot count for the marker, and how an earlier process ended (M8).
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
    private val retry: () -> RetryPolicy = { RetryPolicy() },
    private val random: () -> Double = Math::random,
    private val diagnostics: DiagnosticsSink = DiagnosticsSink.None,
    private val protection: ProtectionStore = InMemoryProtectionStore(),
    private val history: ProcessHistory = ProcessHistory.None,
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

    /** The phone's network as Android reports it to the service ([underlyingNetwork]). */
    data class NetworkInfo(val id: String, val transport: String)

    private val events = Channel<Event>(Channel.UNLIMITED)

    // A job that fails in a way nothing caught must not take the process down with it (which would drop held
    // traffic): it becomes a failure of the attempt it belongs to, and is only logged once that attempt is over.
    // Every job catches what it expects; this is the last resort.
    private fun failuresOf(attempt: Long) = CoroutineExceptionHandler { _, e -> send(Event.JobFailed(attempt, e.javaClass.simpleName)) }

    // Touched only by the event loop, except where @Volatile says otherwise.
    private var host: VpnHost? = null

    /** The service the session last let go of, and its last start then: a start of it up to that one is stale. */
    private var released: VpnHost? = null
    private var releasedStart = 0
    private var pending: ProxyProfile? = null
    private var serviceWait: Job? = null
    private var serviceToken = 0L
    private var attempt = 0L
    private var attemptStartedMillis = 0L
    private var preflight: Job? = null
    private var routing: Job? = null
    private var tun: TunInterface? = null

    @Volatile private var engineRunning = false
    private var engineGeneration = 0L

    /** The user has been connected since the session last ended: failures hold traffic instead of releasing it. */
    private var connectedOnce = false

    /** Set while the session reconnects on its own. */
    private var recovery: Recovery? = null

    /** Since when the connection has carried no traffic (reconnecting, offline, blocked); null while connected. */
    private var heldSinceMillis: Long? = null
    private var retryWait: Job? = null
    private var retryToken = 0L

    /** The phone's network: its id, or null without one; [networkKnown] once Android has said. */
    private var network: String? = null
    private var networkKnown = false

    /** The phone's network as last reported, or null (none, or not reported yet). For device tests and diagnosis. */
    @Volatile var lastNetwork: NetworkInfo? = null
        private set

    /** The connected session's time and the traffic of the engines it already stopped (read by [traffic] from any thread). */
    @Volatile private var sessionTotals: SessionTotals? = null

    /** Android started this connection for Always-on VPN and it has not connected yet: traffic is held from the start (M8). */
    private var startedByAlwaysOn = false

    /** Android's VPN networks from before the interface held for Always-on VPN existed, for its first routing wait. */
    private var routingBefore: List<VpnNetwork>? = null

    /** What Android last said of its kill switch, here or in an earlier process; null until it said once. */
    private var lastKillSwitch: SystemKillSwitch? = null

    /** A connected marker was written for the session that runs, so its end removes it. */
    private var markerWritten = false

    /** Whose session that marker is: a switch to another profile writes that profile's, a reconnection or restart keeps it. */
    private var markerProfile: String? = null

    /** Since when that marker says the session ran: when it connected, or until it has, since traffic was held for it. */
    private var markerSince: Long? = null

    /** [protection]'s writes, in the order the session makes them (DataStore keeps them in order only one at a time). */
    private val writes = Channel<suspend ProtectionStore.() -> Unit>(Channel.UNLIMITED)
    private val loop: Job
    private val writer: Job

    /** Makes [traffic]'s look at the totals and the running engine one step, so an engine's stop never shows as a dip. */
    private val trafficLock = Any()

    init {
        // dispatch() turns every failure into a failed attempt; should the loop still end, the process stays up.
        loop = scope.launch(CoroutineExceptionHandler { _, e -> log("event loop ended: ${e.javaClass.simpleName}") }) {
            for (event in events) dispatch(event)
        }
        // What an earlier process left comes first, off the event loop (Android's answer about how that process ended
        // can take a while right after it): a marker found becomes an interruption in one write, so a process that ends
        // meanwhile leaves the marker to the next. Then every write in order; a store that fails loses that write only.
        writer = scope.launch(blocking + CoroutineExceptionHandler { _, e -> log("protection store ended: ${e.javaClass.simpleName}") }) {
            val seen = runCatching { protection.killSwitch.first() }.getOrNull()
            val interruption = runCatching { protection.connectedMarker() }.getOrNull()?.let { interruptionOf(it, seen) }
            if (interruption != null) {
                try {
                    protection.interrupted(interruption)
                    log("the interruption is kept for Home")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("protection store write failed: ${e.javaClass.simpleName}")
                }
            }
            send(Event.Started(interruption, seen))
            for (write in writes) {
                try {
                    protection.write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("protection store write failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Connects [profileId]. Nothing happens while it is already connecting or connected; another profile replaces the session. */
    fun connect(profileId: String) = send(Event.Connect(profileId))

    /** Runs the connect sequence again for the blocked profile (Restart, Try again). */
    fun restart() = send(Event.Restart)

    /** Disconnects, cancels a connection being set up or a reconnection, or stops a blocked one. */
    fun disconnect() = send(Event.Disconnect)

    /** Forgets the last error after a connection attempt ended; a blocked connection keeps its error. */
    fun clearError() = send(Event.ClearError)

    /** While reconnecting on its own, runs the next attempt now ("Retry now", board B5). */
    fun retryNow() = send(Event.RetryNow)

    /**
     * The service started (or Android started it for Always-on VPN) and can establish interfaces;
     * [startId] is that start's (`onStartCommand`), so a start that reached the service before the
     * session let it go is told apart from a newer one.
     */
    fun attach(host: VpnHost, alwaysOn: Boolean, startId: Int) = send(Event.Attached(host, alwaysOn, startId))

    /** The service was destroyed. */
    fun detached(host: VpnHost) = send(Event.Detached(host))

    /** Android revoked the VPN: another VPN app started, or the user removed the permission. */
    fun revoked(host: VpnHost) = send(Event.Revoked(host))

    /** A profile was deleted; when it is the connection's, its credentials are no longer used (J11). */
    fun profileDeleted(profileId: String) = send(Event.ProfileDeleted(profileId))

    /** The phone's default network as Android reports it to the service: null when it has none. */
    fun underlyingNetwork(network: NetworkInfo?) = send(Event.Network(network))

    /**
     * The user has seen the interrupted connection [shown] (Home), or connected again while Home showed it: it is
     * cleared, in order with the session's writes — unless another has replaced it meanwhile, which the user hasn't
     * seen (one the next process found just then).
     */
    fun dismissInterruption(shown: Interruption) = persist { if (interruption.first() == shown) setInterruption(null) }

    /** Asks Android about its kill switch now, when an interface exists (a screen showing the status, M8); otherwise nothing. */
    fun checkSystemKillSwitch() = send(Event.CheckKillSwitch)

    /**
     * What the connection carried since it was established, the running engine's counters added to
     * those of the engines it already stopped; null without a connection. Safe from any thread: the
     * engine is read directly, never through the event loop.
     */
    fun traffic(): TrafficStats? = synchronized(trafficLock) {
        val totals = sessionTotals ?: return null
        val live = if (engineRunning) runCatching { engine.traffic() }.getOrNull() else null
        val sum = live?.let { totals.carried + it } ?: totals.carried
        TrafficStats(sum.bytesUp, sum.bytesDown, live?.activeConnections ?: 0, totals.sinceMillis)
    }

    /**
     * Coroutines the session runs besides its event loop and that have not finished: the
     * pre-flight check (also a cancelled one whose engine call is still returning), the wait
     * for Android's routing, the wait for the service and the wait before a reconnection
     * attempt. 0 whenever no connection is being set up (lifecycle tests).
     */
    @VisibleForTesting
    fun activeJobs(): Int = scope.coroutineContext[Job]?.children?.count { !it.isCompleted && it !== loop && it !== writer } ?: 0

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
                Event.RetryNow -> onRetryNow()
                is Event.Attached -> onAttached(event.host, event.alwaysOn, event.startId)
                is Event.Detached -> onDetached(event.host)
                is Event.Revoked -> onRevoked()
                is Event.ProfileDeleted -> onProfileDeleted(event.profileId)
                is Event.Network -> onNetwork(event.network)
                is Event.PreflightDone -> onPreflightDone(event)
                is Event.Routed -> onRouted(event.attempt)
                is Event.RoutingTimedOut -> onRoutingTimedOut(event.attempt)
                is Event.EngineFailed -> onEngineFailed(event)
                is Event.ProxyFailure -> onProxyFailure(event)
                is Event.RetryDue -> onRetryDue(event.token)
                is Event.JobFailed -> onJobFailed(event)
                is Event.ServiceStartTimedOut -> onServiceStartTimedOut(event.token)
                is Event.Started -> onStarted(event.interruption, event.seen)
                Event.CheckKillSwitch -> checkKillSwitch()
                is Event.Unanswered -> onUnanswered(event)
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
        if (current.profileId == profileId && current.status in BUSY && recovery == null) return
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

    /** [alwaysOn]: Android started it for Always-on VPN, so traffic is held from the start (M8). */
    private suspend fun beginConnect(profileId: String, alwaysOn: Boolean = false) {
        // Looked up before anything is stopped: a profile that is gone leaves a running connection alone.
        val profile = profiles.get(profileId) ?: return if (alwaysOn) {
            host?.let { alwaysOnWithoutProfile(it) } ?: Unit // deleted since it was read as the chosen one
        } else {
            profileMissing(profileId)
        }
        cancelAttempt()
        recovery = null
        startedByAlwaysOn = alwaysOn
        // Switching or restarting: flows end now. Once connected, the interface stays, so traffic is held until the next engine runs on it.
        stopEngine()
        if (sessionTotals?.profileId.let { it != null && it != profile.id }) closeSession() // a switch ends the old profile's session
        if (!connectedOnce) closeInterface() // an attempt that never connected leaves nothing behind
        val reconnecting = tun != null
        // The interface stays with no engine: from now traffic is held (a switch's, or a restart's while connected).
        if (reconnecting && heldSinceMillis == null) heldSinceMillis = clock()
        // Held for this profile from now: after a switch (or a switch back) the marker names it, since now (ADR-0013 §4).
        if (reconnecting) writeMarker(profile.id, clock())
        log("connect ${profile.id}${if (reconnecting) " (reconnect)" else ""}")
        attemptStartedMillis = clock()
        val previous = state.value
        publish(
            ConnectionState(
                status = if (reconnecting) ConnectionStatus.Reconnecting else ConnectionStatus.Connecting,
                profileId = profile.id,
                profileName = profile.displayName,
                profile = profile,
                step = ConnectStep.Starting,
            ),
        )
        note(
            LogEntry.Level.Info,
            LogEvent.ConnectRequested,
            details = mapOf(
                LogDetail.PROTOCOL to profile.protocol.name,
                LogDetail.TLS to if (profile.useTls) "on" else "off",
                LogDetail.SIGN_IN to if (profile.requiresSignIn) "yes" else "no",
                LogDetail.SWITCH to when {
                    !reconnecting -> null
                    previous.profileId == profile.id -> "restart"
                    else -> "other_profile"
                },
                LogDetail.BY to if (alwaysOn) BY_ALWAYS_ON else null,
            ),
        )
        stageReached()
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

    private suspend fun onAttached(attached: VpnHost, alwaysOn: Boolean, startId: Int) {
        if (attached === released && startId <= releasedStart) {
            // This start reached the service before the session let it go, and the stop that followed ends the service
            // unless a newer start reached it — which attaches it again. A connection must not begin on a service that
            // is going away (connecting and disconnecting quickly queues several starts).
            log("service start $startId came before its stop: not used")
            return
        }
        host = attached
        serviceWait?.cancel()
        serviceWait = null
        val waiting = pending
        pending = null
        when {
            waiting != null -> runPreflight(attached, waiting)
            state.value.active -> {
                attached.showStatus(state.value)
                // Android starting the service for Always-on VPN while connected: the user just turned it on.
                if (alwaysOn) checkKillSwitch()
            }
            alwaysOn -> {
                // Always-on VPN: only the profile the user explicitly chose, never a fallback (ADR-0010 §8).
                val chosen = profiles.chosenProfileId.first()
                log("always-on start${if (chosen == null) ": no chosen profile" else ""}")
                if (chosen != null) beginConnect(chosen, alwaysOn = true) else alwaysOnWithoutProfile(attached)
            }
            else -> releaseHost() // started for a connection that was cancelled meanwhile
        }
    }

    /**
     * Android started ProxyFlow for Always-on VPN with no profile chosen: nothing is connected (ProxyFlow never picks a
     * proxy for the user), and the user is told — with "Block connections without VPN" on, apps have no network.
     */
    private fun alwaysOnWithoutProfile(attached: VpnHost) {
        publish(ConnectionState(error = ConnectionError(ConnectionError.ALWAYS_ON_NO_PROFILE)))
        note(
            LogEntry.Level.Error,
            LogEvent.ConnectFailed,
            stage = ConnectionStage.Starting,
            code = ConnectionError.ALWAYS_ON_NO_PROFILE,
            profileId = null,
            details = mapOf(LogDetail.BY to BY_ALWAYS_ON),
        )
        attached.notifyProblem(state.value)
        releaseHost()
    }

    private fun onDetached(detached: VpnHost) {
        if (released === detached) released = null
        if (host !== detached) return
        host = null
        // Android keeps a VPN service bound while its interface exists, so this happens with a connection only when
        // Android itself stopped the service (a process that is killed gets no call): the session ends and says so,
        // as on a revocation, and the marker goes with it — the user sees this end, unlike a process death.
        if (tun != null || preflight != null || routing != null || pending != null || recovery != null) {
            log("service destroyed during a connection")
            end(ConnectionError(ConnectionError.INTERNAL, "the VPN service stopped"))
        }
    }

    private fun runPreflight(current: VpnHost, profile: ProxyProfile) {
        publish(state.value.copy(step = ConnectStep.VpnPermission))
        if (recovery == null) stageReached()
        // No consent: the user withdrew it, so Android holds no VPN of ours that could hold traffic. (Another VPN app
        // taking over arrives as a revocation instead; for an app the user allowed before, prepare() takes it back.)
        if (!current.hasConsent()) return end(ConnectionError(ConnectionError.VPN_PERMISSION_DENIED))
        current.clearProblem()
        // Running in the foreground is part of setting up the VPN: refused, the attempt fails at this stage, before any
        // proxy is contacted.
        if (!current.showStatus(state.value)) {
            return failAttempt(ConnectionError(ConnectionError.INTERNAL, "Android did not allow the foreground service"))
        }
        // Always-on VPN: the interface comes up now, before the proxy is checked, so nothing gets out meanwhile.
        if (startedByAlwaysOn && tun == null && !holdTraffic(current)) return
        publish(state.value.copy(step = ConnectStep.ProxyHandshake))
        if (recovery == null) stageReached()
        current.showStatus(state.value)
        val id = ++attempt
        val guard = current.socketGuard()
        preflight?.cancel() // never two checks at once; a replaced one's answer is ignored (its attempt is over)
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
            is Check.Failed -> return failAttempt(check.error)
            is Check.Answered -> {
                log("check ${profile.id}: ${check.answer.code ?: "ok"}")
                if (!check.answer.passes) return failAttempt(check.answer.error)
            }
        }
        val current = host ?: return failAttempt(ConnectionError(ConnectionError.INTERNAL, "the VPN service stopped"))
        publish(state.value.copy(step = ConnectStep.Authenticated))
        if (recovery == null) stageReached()
        current.showStatus(state.value)

        // Read before the interface exists, so nothing can fail between establishing it and starting the engine but the engine.
        val secret = when (val read = readSecret(profile)) {
            is SecretRead.Ok -> read.secret
            is SecretRead.Failed -> return failAttempt(read.error)
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

    /**
     * Establishes the interface before the proxy check, for a connection Android started for Always-on VPN (M8): apps'
     * traffic is held from now on; failures follow the rules of a connection that was up. False when the attempt ended.
     */
    private fun holdTraffic(current: VpnHost): Boolean {
        val before = try {
            current.vpnNetworks()
        } catch (e: Exception) {
            failAttempt(ConnectionError(ConnectionError.INTERNAL, "Android's networks could not be read"))
            return false
        }
        val fresh = try {
            current.establish(engine.tunConfig())
        } catch (e: Exception) {
            failAttempt(ConnectionError(ConnectionError.INTERNAL, "Android could not create the VPN interface"))
            return false
        }
        if (fresh == null) {
            end(ConnectionError(ConnectionError.VPN_PERMISSION_DENIED))
            return false
        }
        log("established ${fresh.name ?: "an interface"} for Always-on VPN: traffic held until the proxy answers")
        tun = fresh
        routingBefore = before
        connectedOnce = true
        val since = clock()
        if (heldSinceMillis == null) heldSinceMillis = since
        publish(state.value.copy(held = true))
        // Traffic held is the VPN running: a process that ends now leaves apps to the normal network too.
        writeMarker(state.value.profileId, since)
        // With an interface Android answers: Home can say what it does while the proxy is checked or retried.
        checkKillSwitch()
        return true
    }

    /**
     * The marker the next process finds if this one ends with the VPN up (M8), one per profile's session: written as
     * traffic is first held for a profile — an Always-on VPN start, a switch to it (replacing the marker before) — and
     * when its session first connects ([connected], with the session's start); a reconnection or a restart of the same
     * profile keeps it.
     */
    private fun writeMarker(profileId: String?, sinceMillis: Long, connected: Boolean = false) {
        if (markerWritten && markerProfile == profileId && (!connected || markerSince == sinceMillis)) return
        val marker = try {
            history.marker(profileId, sinceMillis)
        } catch (e: Exception) {
            log("no connected marker: ${e.javaClass.simpleName}")
            return
        }
        markerWritten = true
        markerProfile = profileId
        markerSince = sinceMillis
        persist { markConnected(marker) }
    }

    private fun startProtection(current: VpnHost, profile: ProxyProfile, secret: EngineSecret?) {
        val config = engine.tunConfig()
        // A switch, a restart or a reconnection keeps the interface; only a first connect creates one.
        val existing = tun
        if (existing != null) {
            // Held means dropped: what apps sent meanwhile must not reach the next proxy later.
            val dropped = existing.drain()
            if (dropped > 0) log("dropped $dropped packets held while no engine ran")
        }
        // An interface held for Always-on VPN has not been routed yet: its first wait leaves out what was there before it.
        val held = routingBefore
        routingBefore = null
        val before = if (held != null) {
            held
        } else if (existing != null) {
            emptyList()
        } else {
            try {
                current.vpnNetworks()
            } catch (e: Exception) {
                // Without Android's view from before the interface exists, a network still being torn down could pass for its.
                return failAttempt(ConnectionError(ConnectionError.INTERNAL, "Android's networks could not be read"))
            }
        }
        val interfaceForEngine = existing ?: run {
            val fresh = try {
                current.establish(config)
            } catch (e: Exception) {
                return failAttempt(ConnectionError(ConnectionError.INTERNAL, "Android could not create the VPN interface"))
            }
            // Not prepared any more: the user withdrew consent, or another VPN app took over (its revocation follows).
            if (fresh == null) return end(ConnectionError(ConnectionError.VPN_PERMISSION_DENIED))
            log("established ${fresh.name ?: "an interface"}")
            tun = fresh
            fresh
        }

        val generation = ++engineGeneration
        val profileId = profile.id
        val notStarted = try {
            engine.start(interfaceForEngine, config, profile, secret, current::protectSocket) { signal ->
                when (signal) {
                    is EngineSignal.Failed -> send(Event.EngineFailed(generation, signal.error))
                    is EngineSignal.ProxyFailure -> send(Event.ProxyFailure(generation, signal.error))
                    // Counted for the log straight away: as many as apps make, and nothing the session acts on.
                    is EngineSignal.ConnectionFailure -> diagnostics.connectionFailed(signal.code, profileId)
                    // A site's failure for the log too; the session looks at how many came in a row (M8).
                    is EngineSignal.Unanswered -> {
                        diagnostics.connectionFailed(signal.error.code, profileId)
                        send(Event.Unanswered(generation, signal.error))
                    }
                }
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
            return failAttempt(notStarted)
        }
        engineRunning = true
        awaitRouting(current, interfaceForEngine, before)
    }

    /**
     * `establish()` returns before Android has created the VPN network and its routing rules,
     * so apps' traffic still takes the normal network for a moment. Connected waits until
     * Android reports the interface's network ready — at once on a switch, a restart or a
     * reconnection, whose interface Android already routes apps into.
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
        val now = clock()
        val profileId = state.value.profileId
        val existing = sessionTotals?.takeIf { it.profileId == profileId }
        val totals = existing ?: SessionTotals(profileId, sinceMillis = now).also { sessionTotals = it }
        // A reconnection only when this profile's session was connected before: a profile connected for the first time
        // (a switch that failed and was retried on its own, say) starts its session here.
        val recovered = recovery?.takeIf { existing != null }
        val outageStarted = heldSinceMillis ?: recovered?.startedMillis
        recovery = null
        heldSinceMillis = null
        startedByAlwaysOn = false
        if (recovered != null) {
            log("reconnected $profileId after ${recovered.attempt} attempts")
            sessionTotals = totals.copy(reconnects = totals.reconnects + 1)
        } else {
            log("connected $profileId")
        }
        publish(
            state.value.copy(
                status = ConnectionStatus.Connected,
                step = null,
                connectedSinceMillis = totals.sinceMillis,
                error = null,
                reconnect = null,
                held = false,
            ),
        )
        if (recovered != null) {
            note(
                LogEntry.Level.Info,
                LogEvent.Reconnected,
                details = mapOf(
                    LogDetail.REASON to recovered.reason.key,
                    LogDetail.ATTEMPT to recovered.attempt.toString(),
                    LogDetail.OUTAGE_MS to (now - (outageStarted ?: recovered.startedMillis)).toString(),
                ),
            )
        } else {
            note(LogEntry.Level.Info, LogEvent.Connected, details = mapOf(LogDetail.SETUP_MS to (now - attemptStartedMillis).toString()))
        }
        host?.showStatus(state.value)
        // A new session (not a reconnection of this one): the next process finds it if this one ends without stopping it.
        writeMarker(profileId, totals.sinceMillis, connected = true)
        checkKillSwitch()
    }

    private fun onRoutingTimedOut(id: Long) {
        if (id != attempt || routing == null) return
        routing = null
        log("Android did not route apps into the interface")
        failAttempt(ConnectionError(ConnectionError.INTERNAL, "Android did not route apps into the VPN"))
    }

    private fun onEngineFailed(event: Event.EngineFailed) {
        if (event.generation != engineGeneration || !engineRunning) return
        log("engine failed: ${event.error.code}")
        fail(event.error)
    }

    /**
     * The escalation rule (docs/m1/04 §4, ADR-0012): a refused sign-in or certificate blocks at once;
     * other failures at the proxy make the session reconnect once three came in a row, as the engine
     * counts them (a working connection resets its count).
     */
    private fun onProxyFailure(event: Event.ProxyFailure) {
        if (event.generation != engineGeneration || !engineRunning || state.value.status != ConnectionStatus.Connected) return
        val error = event.error
        if (error.code in RetryPolicy.BLOCK_AT_ONCE) {
            log("proxy refused while connected: ${error.code}")
            return block(error)
        }
        val inARow = engine.consecutiveProxyFailures()
        if (inARow < RetryPolicy.ESCALATION_FAILURES) return
        log("proxy failing while connected: ${error.code} ($inARow in a row)")
        startRecovery(ReconnectReason.ProxyFailures, error, immediate = false, inARow = inARow)
    }

    /**
     * A SOCKS5 proxy that answers its sign-in but leaves connection requests unanswered (its own way out broken): once
     * [RetryPolicy.UNANSWERED_ESCALATION] in a row went unanswered, to at least [RetryPolicy.UNANSWERED_SITES] different
     * sites, with nothing working between them (the engine counts), the session reconnects (M8). One slow site can't
     * make it: it is one site, and anything that works meanwhile starts the count over.
     */
    private fun onUnanswered(event: Event.Unanswered) {
        if (event.generation != engineGeneration || !engineRunning || state.value.status != ConnectionStatus.Connected) return
        val unanswered = engine.unansweredRequests()
        if (unanswered.inARow < RetryPolicy.UNANSWERED_ESCALATION || unanswered.sites < RetryPolicy.UNANSWERED_SITES) return
        log("proxy leaving connection requests unanswered: ${unanswered.inARow} in a row to ${unanswered.sites} sites")
        startRecovery(
            ReconnectReason.ProxyUnanswered,
            event.error,
            immediate = false,
            inARow = unanswered.inARow,
            sites = unanswered.sites,
        )
    }

    // --- what outlives the process (M8, ADR-0013) ---------------------------------------------------------------

    /** Queues a write to [protection]; the writer runs them in order, off the event loop. */
    private fun persist(write: suspend ProtectionStore.() -> Unit) {
        writes.trySend(write)
    }

    /**
     * How the process that left [left] ended — it ended while connected, without the connection being stopped: Android
     * removed the VPN with it, so from then until ProxyFlow connected again apps used the normal network, unless
     * Android's kill switch blocked them ([seen], what ProxyFlow last read before). Called by the writer, off the loop.
     */
    private fun interruptionOf(left: ConnectedMarker, seen: SystemKillSwitch?): Interruption {
        val asked = System.nanoTime()
        val ending = runCatching { history.ending(left) }.getOrElse { ProcessHistory.Ending(ExitReason.Unknown, null) }
        log(
            "the last process ended while connected: ${ending.reason.key} (Android answered in ${(System.nanoTime() - asked) / 1_000_000} ms)",
        )
        return Interruption(
            profileId = left.profileId,
            connectedSinceMillis = left.sinceMillis,
            endedAtMillis = ending.atMillis,
            reason = ending.reason,
            detectedAtMillis = clock(),
            killSwitch = seen?.status,
        )
    }

    /**
     * The first event: what an earlier process left, already kept for Home ([interruption], or null), is logged; the
     * kill switch as last stored becomes this session's last reading.
     */
    private fun onStarted(interruption: Interruption?, seen: SystemKillSwitch?) {
        if (lastKillSwitch == null) lastKillSwitch = seen
        if (interruption == null) return
        val ended = interruption.endedAtMillis
        val now = clock()
        note(
            LogEntry.Level.Error,
            LogEvent.SessionInterrupted,
            stage = ConnectionStage.Stopped,
            profileId = interruption.profileId,
            details = mapOf(
                LogDetail.REASON to interruption.reason.key,
                LogDetail.END_TIME to if (ended != null) "exact" else "unknown",
                LogDetail.DURATION_MS to ended?.let { (it - interruption.connectedSinceMillis).coerceAtLeast(0).toString() },
                LogDetail.BLOCKS_WITHOUT_VPN to interruption.killSwitch?.let { if (it == KillSwitchStatus.On) "yes" else "no" },
            ),
            // Dated when it ended where Android says, unless that is older than the log keeps (the next start may come days later).
            timeMillis = ended?.takeIf { now - it < INTERRUPTION_DATING_LIMIT_MS } ?: now,
        )
    }

    /**
     * Reads what Android says of its kill switch while an interface exists (Android answers only then), keeps it, and
     * logs it when it changed. Without an interface, nothing: what was read last stands.
     */
    private fun checkKillSwitch() {
        val current = host ?: return
        if (tun == null) return
        val reading = try {
            current.systemKillSwitch()
        } catch (e: Exception) {
            null
        } ?: return
        val value = SystemKillSwitch(reading.alwaysOn, reading.blocksWithoutVpn, clock())
        val changed = lastKillSwitch?.status != value.status
        lastKillSwitch = value
        if (changed) {
            log("system kill switch: ${value.status.key}")
            note(
                LogEntry.Level.Info,
                LogEvent.SystemKillSwitch,
                details = mapOf(
                    LogDetail.ALWAYS_ON to if (value.alwaysOn) "yes" else "no",
                    LogDetail.BLOCKS_WITHOUT_VPN to if (value.blocksWithoutVpn) "yes" else "no",
                ),
            )
        }
        persist { recordKillSwitch(value) }
    }

    private fun onJobFailed(event: Event.JobFailed) {
        log("unexpected ${event.type} in a session job${if (event.attempt != attempt) " of an attempt that is over" else ""}")
        // A cancelled or replaced attempt's job failing late must not touch the connection that followed.
        if (event.attempt != attempt) return
        // The job is over: nothing waits for its answer any more, so the next attempt (or Retry now) may run.
        preflight = null
        routing = null
        failAttempt(ConnectionError(ConnectionError.INTERNAL, "unexpected ${event.type} in a background job"))
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
        if (connectedOnce) block(ConnectionError(ConnectionError.PROFILE_DELETED)) else end(error = null, stoppedBy = "profile_deleted")
    }

    // --- reconnecting on its own (ADR-0012) -------------------------------------------------------------------

    /**
     * Starts reconnecting: the engine stops (traffic held at the interface), and attempt 1 runs after its wait,
     * or at once when [immediate]. A reconnection already running is replaced, its attempts starting over.
     */
    private fun startRecovery(
        reason: ReconnectReason,
        error: ConnectionError?,
        immediate: Boolean,
        inARow: Long? = null,
        sites: Int? = null,
    ) {
        if (state.value.profile == null || tun == null) return block(error ?: ConnectionError(ConnectionError.INTERNAL))
        cancelAttempt()
        stopEngine()
        if (heldSinceMillis == null) heldSinceMillis = clock()
        val policy = retry()
        recovery = Recovery(reason, policy, startedMillis = clock(), attempt = 1, lastError = error)
        log("reconnecting: ${reason.key}${error?.let { " ${it.code}" } ?: ""}")
        note(
            LogEntry.Level.Warning,
            LogEvent.ReconnectStarted,
            stage = ConnectionStage.Reconnecting,
            code = error?.code,
            details = mapOf(
                LogDetail.REASON to reason.key,
                LogDetail.CONSECUTIVE to inARow?.toString(),
                LogDetail.SITES to sites?.toString(),
                LogDetail.MAX_ATTEMPTS to policy.maxAttempts.toString(),
            ),
        )
        scheduleAttempt(if (immediate) 0 else policy.delayBefore(1, random()))
    }

    private fun scheduleAttempt(waitMillis: Long) {
        val r = recovery ?: return
        val token = ++retryToken
        publishRecovery(r, nextAttemptAtMillis = clock() + waitMillis)
        host?.showStatus(state.value)
        retryWait = scope.launch(failuresOf(attempt)) {
            delay(waitMillis)
            send(Event.RetryDue(token))
        }
    }

    private fun publishRecovery(r: Recovery, nextAttemptAtMillis: Long?) {
        publish(
            state.value.copy(
                status = ConnectionStatus.Reconnecting,
                step = if (nextAttemptAtMillis == null) ConnectStep.ProxyHandshake else null,
                error = null,
                reconnect = ReconnectInfo(r.reason, r.attempt, r.policy.maxAttempts, nextAttemptAtMillis, r.lastError),
            ),
        )
    }

    private fun onRetryDue(token: Long) {
        val r = recovery ?: return
        if (token != retryToken) return
        if (preflight != null || routing != null) return // an attempt runs already: never two at once
        retryWait = null
        val profile = state.value.profile ?: return block(ConnectionError(ConnectionError.INTERNAL, PROFILE_GONE))
        val current = host ?: return block(ConnectionError(ConnectionError.INTERNAL, "the VPN service stopped"))
        log("reconnect attempt ${r.attempt} of ${r.policy.maxAttempts}")
        attemptStartedMillis = clock()
        publishRecovery(r, nextAttemptAtMillis = null)
        runPreflight(current, profile)
    }

    private fun onRetryNow() {
        if (recovery == null || preflight != null || routing != null) return // nothing waiting, or an attempt is running
        retryWait?.cancel()
        retryWait = null
        // A new token: the wait's own RetryDue, should its timer have fired already, finds it changed and does nothing.
        onRetryDue(++retryToken)
    }

    /** An attempt failed: the next one after its wait, or — with no attempt left, or an answer no retry changes — blocked. */
    private fun attemptFailed(r: Recovery, error: ConnectionError) {
        log("reconnect attempt ${r.attempt} failed: ${error.code}")
        if (!RetryPolicy.recoverable(error.code)) {
            recovery = null
            return block(error)
        }
        if (r.attempt >= r.policy.maxAttempts) {
            recovery = null
            log("reconnecting stopped after ${r.attempt} attempts")
            note(
                LogEntry.Level.Error,
                LogEvent.ReconnectGaveUp,
                stage = ConnectionStage.Reconnecting,
                code = error.code,
                details = mapOf(LogDetail.ATTEMPT to r.attempt.toString(), LogDetail.DETAIL to error.detail),
            )
            return block(error.copy(detail = "reconnecting stopped after ${r.attempt} attempts"), logged = true)
        }
        val wait = r.policy.delayBefore(r.attempt + 1, random())
        note(
            LogEntry.Level.Warning,
            LogEvent.ReconnectAttemptFailed,
            stage = ConnectionStage.Reconnecting,
            code = error.code,
            details = mapOf(
                LogDetail.ATTEMPT to r.attempt.toString(),
                LogDetail.MAX_ATTEMPTS to r.policy.maxAttempts.toString(),
                LogDetail.NEXT_TRY_MS to wait.toString(),
                LogDetail.DETAIL to error.detail,
            ),
        )
        r.attempt++
        r.lastError = error
        stopEngine() // an attempt that started its engine and then failed leaves none running
        scheduleAttempt(wait)
    }

    // --- the phone's network ----------------------------------------------------------------------------------

    private fun onNetwork(info: NetworkInfo?) {
        val previous = network
        val known = networkKnown
        network = info?.id
        networkKnown = true
        lastNetwork = info
        if (info?.id != previous || !known) log("network: ${info?.transport ?: "none"}${if (known) "" else " (first report)"}")
        if (!known) return // Android's first report says where the phone is, not that anything changed
        val status = state.value.status
        val heldForRetry = status == ConnectionStatus.Blocked && tun != null && RetryPolicy.recoverable(state.value.error?.code)
        if (info == null) {
            if (status == ConnectionStatus.Connected || recovery != null || heldForRetry) goOffline()
            return
        }
        if (info.id == previous) return
        when {
            status == ConnectionStatus.Offline -> {
                noteNetwork(info, reset = null)
                startRecovery(ReconnectReason.NetworkBack, error = null, immediate = true)
            }
            recovery != null || heldForRetry -> {
                noteNetwork(info, reset = null)
                startRecovery(ReconnectReason.NetworkChanged, recovery?.lastError ?: state.value.error, immediate = true)
            }
            status == ConnectionStatus.Connected && engineRunning -> {
                // The engine keeps running; its connections end so apps make new ones, which go out on the new network.
                val reset = engine.resetFlows()
                log("network changed: $reset connections reset")
                noteNetwork(info, reset)
            }
        }
    }

    private fun noteNetwork(info: NetworkInfo, reset: Int?) {
        note(
            LogEntry.Level.Info,
            LogEvent.NetworkChanged,
            details = mapOf(
                LogDetail.NETWORK to info.transport,
                LogDetail.RESET to reset?.toString(),
            ),
        )
    }

    /** No network: the engine stops and traffic is held at the interface until one is back (board B7). */
    private fun goOffline() {
        cancelAttempt()
        recovery = null
        stopEngine()
        if (heldSinceMillis == null) heldSinceMillis = clock()
        log("offline")
        publish(
            state.value.copy(
                status = ConnectionStatus.Offline,
                step = null,
                error = ConnectionError(ConnectionError.OFFLINE),
                reconnect = null,
            ),
        )
        note(LogEntry.Level.Warning, LogEvent.Offline, stage = ConnectionStage.Reconnecting, code = ConnectionError.OFFLINE)
        host?.showStatus(state.value)
    }

    // --- endings ------------------------------------------------------------------------------------------------

    /** An attempt failed: the next reconnection attempt while reconnecting, otherwise as [fail]. */
    private fun failAttempt(error: ConnectionError) {
        val r = recovery
        if (r != null) attemptFailed(r, error) else fail(error)
    }

    /**
     * Fails the current attempt: blocked once the user has been connected, otherwise back to Disconnected with nothing
     * left. A connection Android started for Always-on VPN holds traffic from the start, and one that failed in a way that
     * may pass is tried again as one that broke (M8).
     */
    private fun fail(error: ConnectionError) {
        when {
            // Its first failure only: a failure during the attempts that follow (an unexpected one) ends them, bounded.
            startedByAlwaysOn && tun != null && recovery == null && RetryPolicy.recoverable(error.code) ->
                startRecovery(ReconnectReason.AlwaysOnStart, error, immediate = false)
            connectedOnce -> block(error)
            else -> end(error)
        }
    }

    /** The interface stays up with no engine reading it: apps' traffic is held, never sent around the proxy. */
    private fun block(error: ConnectionError, logged: Boolean = false) {
        val stage = state.value.stage
        cancelAttempt()
        recovery = null
        stopEngine()
        if (heldSinceMillis == null) heldSinceMillis = clock()
        log("blocked: ${error.code}")
        publish(
            state.value.copy(status = ConnectionStatus.Blocked, step = null, connectedSinceMillis = null, error = error, reconnect = null),
        )
        if (!logged) {
            note(
                LogEntry.Level.Error,
                LogEvent.Blocked,
                stage = stage,
                code = error.code,
                details = mapOf(LogDetail.DETAIL to error.detail),
            )
        }
        host?.showStatus(state.value)
    }

    /** Back to the normal network: engine stopped, interfaces closed, service stopped. */
    private fun end(error: ConnectionError?, stoppedBy: String = "user") {
        val before = state.value
        cancelAttempt()
        recovery = null
        pending = null
        serviceWait?.cancel()
        serviceWait = null
        stopEngine()
        closeInterface()
        connectedOnce = false
        startedByAlwaysOn = false
        heldSinceMillis = null
        if (markerWritten) {
            markerWritten = false
            markerProfile = null
            markerSince = null
            persist { clearConnected() }
        }
        if (before.status != ConnectionStatus.Disconnected) log("disconnected${error?.let { ": ${it.code}" } ?: ""}")
        publish(
            before.copy(status = ConnectionStatus.Disconnected, step = null, connectedSinceMillis = null, error = error, reconnect = null),
        )
        if (before.status != ConnectionStatus.Disconnected) {
            when {
                error == null -> note(
                    LogEntry.Level.Info,
                    LogEvent.Stopped,
                    stage = ConnectionStage.Stopped,
                    details = mapOf(LogDetail.BY to stoppedBy),
                )
                error.code == ConnectionError.VPN_REVOKED -> note(
                    LogEntry.Level.Warning,
                    LogEvent.Revoked,
                    stage = ConnectionStage.Stopped,
                    code = error.code,
                )
                else -> note(
                    LogEntry.Level.Error,
                    LogEvent.ConnectFailed,
                    stage = before.stage,
                    code = error.code,
                    details = mapOf(LogDetail.DETAIL to error.detail),
                )
            }
        }
        closeSession()
        releaseHost()
    }

    /** Logs the summary of the connected session that ends (its time and traffic) and forgets it. */
    private fun closeSession() {
        val totals = sessionTotals ?: return
        sessionTotals = null
        diagnostics.flushProblems()
        val t = totals.carried
        note(
            LogEntry.Level.Info,
            LogEvent.SessionSummary,
            stage = ConnectionStage.Stopped,
            profileId = totals.profileId,
            details = mapOf(
                LogDetail.DURATION_MS to (clock() - totals.sinceMillis).toString(),
                LogDetail.BYTES_UP to t.bytesUp.toString(),
                LogDetail.BYTES_DOWN to t.bytesDown.toString(),
                LogDetail.OPENED to t.opened.toString(),
                LogDetail.FAILED to t.failed.toString(),
                LogDetail.UDP_BLOCKED to t.udpBlocked.toString(),
                LogDetail.PINGS_DROPPED to t.pingsDropped.toString(),
                LogDetail.RECONNECTS to totals.reconnects.toString(),
            ),
        )
    }

    private fun cancelAttempt() {
        attempt++
        preflight?.cancel()
        preflight = null
        routing?.cancel()
        routing = null
        retryWait?.cancel()
        retryWait = null
        retryToken++
    }

    /**
     * Stops the engine, its traffic counted into the session's first: read while it still runs (a stopped
     * engine has no counters), and in one step with [traffic]'s readers, so the counters never go back.
     */
    private fun stopEngine() {
        if (!engineRunning) return
        synchronized(trafficLock) {
            val last = runCatching { engine.traffic() }.getOrNull()
            engineRunning = false
            val totals = sessionTotals
            if (last != null && totals != null) sessionTotals = totals.copy(carried = totals.carried + last)
        }
        runCatching { engine.stop() }
    }

    private fun closeInterface() {
        tun?.close()
        tun = null
        routingBefore = null
    }

    private fun releaseHost() {
        val current = host ?: return
        host = null
        releasedStart = current.stop()
        released = current
    }

    private fun publish(next: ConnectionState) {
        state.value = next
    }

    private fun stageReached() {
        note(LogEntry.Level.Info, LogEvent.StageReached)
    }

    /** One entry of the diagnostic log, at the current stage and for the current profile unless told otherwise. */
    private fun note(
        level: LogEntry.Level,
        event: LogEvent,
        stage: ConnectionStage? = state.value.stage,
        code: String? = null,
        profileId: String? = state.value.profileId,
        details: Map<String, String?> = emptyMap(),
        timeMillis: Long = clock(),
    ) {
        diagnostics.record(
            LogEntry(
                timeMillis = timeMillis,
                level = level,
                event = event,
                stage = stage,
                code = code,
                profileId = profileId,
                details = details.filterValues { it != null }.mapValues { it.value!! },
            ),
        )
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

    /** A reconnection in progress: its policy, when it started, the attempt next or running, and the last attempt's error. */
    private class Recovery(
        val reason: ReconnectReason,
        val policy: RetryPolicy,
        val startedMillis: Long,
        var attempt: Int,
        var lastError: ConnectionError?,
    )

    /** The connected session: its profile, since when, the traffic of the engines it stopped, and its reconnections. */
    private data class SessionTotals(
        val profileId: String?,
        val sinceMillis: Long,
        val carried: EngineTraffic = EngineTraffic(),
        val reconnects: Int = 0,
    )

    private sealed interface Event {
        data class Connect(val profileId: String) : Event

        data object Restart : Event

        data object Disconnect : Event

        data object ClearError : Event

        data object RetryNow : Event

        class Attached(val host: VpnHost, val alwaysOn: Boolean, val startId: Int) : Event

        class Detached(val host: VpnHost) : Event

        class Revoked(val host: VpnHost) : Event

        data class ProfileDeleted(val profileId: String) : Event

        class Network(val network: NetworkInfo?) : Event

        class PreflightDone(val attempt: Long, val profile: ProxyProfile, val check: Check) : Event

        class Routed(val attempt: Long) : Event

        class RoutingTimedOut(val attempt: Long) : Event

        class EngineFailed(val generation: Long, val error: ConnectionError) : Event

        class ProxyFailure(val generation: Long, val error: ConnectionError) : Event

        class RetryDue(val token: Long) : Event

        class JobFailed(val attempt: Long, val type: String) : Event

        class ServiceStartTimedOut(val token: Long) : Event

        /** What an earlier process left behind, and the kill switch as last stored: the first event (M8). */
        class Started(val interruption: Interruption?, val seen: SystemKillSwitch?) : Event

        data object CheckKillSwitch : Event

        class Unanswered(val generation: Long, val error: ConnectionError) : Event

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

        /** [LogDetail.BY] for a connection Android started for Always-on VPN. */
        private const val BY_ALWAYS_ON = "always_on"

        /** An interruption found later than this is logged when found: the log keeps 24 hours (ADR-0012). */
        private const val INTERRUPTION_DATING_LIMIT_MS = 23 * 60 * 60 * 1000L
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

/** The key a reconnect reason is logged with. */
internal val ReconnectReason.key: String
    get() = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(Locale.ROOT)
