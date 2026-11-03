package app.proxyflow.core.vpn

import app.proxyflow.core.engine.EngineException
import app.proxyflow.core.engine.EngineNative
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.model.TrafficStats
import app.proxyflow.core.testing.TestProfiles
import app.proxyflow.engine.go.pfengine.Platform
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnecting on its own, the phone's network, the session's traffic and the diagnostic log (M7, ADR-0012),
 * with the fake engine and service of [VpnSessionTest]: the escalation rule, attempts that are bounded and
 * cancellable with never two at once, Offline and back, a move to another network, rapid connects and
 * disconnects, and entries that name each failure's stage without anything secret.
 */
class ReconnectTest {
    private val log = CallLog()
    private val sessionLog = CallLog()
    private val engine = FakeEngine(log)
    private val host = FakeHost(log)
    private val profiles = TestProfiles(dispatcher = Dispatchers.Unconfined)
    private val state = RecordingState()
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val problems = CopyOnWriteArrayList<Pair<String, String?>>()
    private val flushes = AtomicLong()
    private val now = AtomicLong(NOW)

    @Volatile private var policy = RetryPolicy(delaysMillis = listOf(30, 30, 30), jitter = 0.0)

    private val sink = object : DiagnosticsSink {
        override fun record(entry: LogEntry) {
            entries += entry
        }

        override fun connectionFailed(code: String, profileId: String?) {
            problems += code to profileId
        }

        override fun flushProblems() {
            flushes.incrementAndGet()
        }
    }

    private val session: VpnSession = VpnSession(
        profiles = profiles.repository,
        engine = engine,
        services = {
            session.attach(host, alwaysOn = false, startId = host.started())
            true
        },
        state = state,
        scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])),
        clock = { now.get() },
        timing = VpnSession.Timing(
            preflightTimeoutMillis = 2_000,
            serviceStartTimeoutMillis = 1_000,
            routingTimeoutMillis = 2_000,
            routingPollMillis = 5,
        ),
        log = sessionLog::add,
        retry = { policy },
        random = { 0.5 },
        diagnostics = sink,
    )

    @After
    fun tearDown() {
        engine.gate?.countDown()
        scope.cancel()
        executor.shutdownNow()
        assertEquals("interfaces established while another was open: ${log.all}", 0, host.overlaps.get())
    }

    @Test
    fun aConnectionLogsEachStageInOrderAndNothingSecret() {
        val profile = connect(signIn = true)

        assertEquals(
            listOf(
                LogEvent.ConnectRequested,
                LogEvent.StageReached,
                LogEvent.StageReached,
                LogEvent.StageReached,
                LogEvent.StageReached,
                LogEvent.Connected,
                // M8: what Android said of its kill switch, the first time (FakeHost: off).
                LogEvent.SystemKillSwitch,
            ),
            entries.map { it.event },
        )
        assertEquals(
            listOf(
                ConnectionStage.Starting,
                ConnectionStage.Starting,
                ConnectionStage.VpnPermission,
                ConnectionStage.ProxyHandshake,
                ConnectionStage.Authenticated,
            ),
            entries.take(5).map { it.stage },
        )
        assertEquals(ConnectionStage.Connected, entries.last().stage)
        assertEquals(mapOf(LogDetail.PROTOCOL to "SOCKS5", LogDetail.TLS to "off", LogDetail.SIGN_IN to "yes"), entries.first().details)
        assertTrue(entries.all { it.profileId == profile.id })
        assertNothingSecretLogged()
    }

    @Test
    fun aFailedCheckLogsItsStageAndCode() {
        val profile = save(signIn = true)
        engine.answer = PreflightAnswer(ok = false, code = "AUTH_FAILED", detail = "proxy rejected the username or password (HTTP 407)")

        session.connect(profile.id)
        await { it.status == ConnectionStatus.Disconnected && it.error != null }

        val failed = entries.single { it.event == LogEvent.ConnectFailed }
        assertEquals(ConnectionStage.ProxyHandshake, failed.stage)
        assertEquals("AUTH_FAILED", failed.code)
        assertEquals(LogEntry.Level.Error, failed.level)
        assertNothingSecretLogged()
    }

    @Test
    fun twoProxyFailuresInARowAreToleratedTheThirdReconnects() {
        policy = RetryPolicy(delaysMillis = listOf(60_000, 60_000, 60_000), jitter = 0.0)
        connect()
        engine.inARow = 2
        proxyFailure("PROXY_UNREACHABLE")
        assertEquals(ConnectionStatus.Connected, state.value.status)

        engine.inARow = 3
        proxyFailure("PROXY_UNREACHABLE")
        val reconnecting = await { it.status == ConnectionStatus.Reconnecting }

        val retry = checkNotNull(reconnecting.reconnect)
        assertEquals(ReconnectReason.ProxyFailures, retry.reason)
        assertEquals(1, retry.attempt)
        assertEquals(3, retry.maxAttempts)
        assertEquals("attempt 1 waits its delay", NOW + 60_000, retry.nextAttemptAtMillis)
        assertEquals(ConnectionError("PROXY_UNREACHABLE"), retry.lastError)
        assertFalse("traffic is held: no engine runs", engine.running)
        assertFalse("the interface stays", host.tuns.single().closed)
        assertEquals("the session's time goes on", NOW, reconnecting.connectedSinceMillis)
        val started = entries.single { it.event == LogEvent.ReconnectStarted }
        assertEquals("PROXY_UNREACHABLE", started.code)
        assertEquals("3", started.details[LogDetail.CONSECUTIVE])
    }

    @Test
    fun aRefusedSignInOrCertificateBlocksAtOnce() {
        for (code in listOf("AUTH_FAILED", "TLS_UNTRUSTED")) {
            connect()
            proxyFailure(code)
            val blocked = await { it.status == ConnectionStatus.Blocked }

            assertEquals(code, blocked.error?.code)
            assertNull("no retry changes the answer", blocked.reconnect)
            assertEquals(ConnectionStage.Connected, entries.last { it.event == LogEvent.Blocked }.stage)
            session.disconnect()
            await { it.status == ConnectionStatus.Disconnected }
        }
    }

    @Test
    fun anAttemptThatPassesTheCheckConnectsAgainOnTheSameInterfaceWithTheSessionGoingOn() {
        val profile = connect()
        engine.trafficNow = EngineTraffic(bytesUp = 100, bytesDown = 200, activeConnections = 2, opened = 3)
        now.set(NOW + 60_000)
        escalate()

        val back = await { it.status == ConnectionStatus.Connected }

        assertEquals("the session's start, not the reconnection's", NOW, back.connectedSinceMillis)
        assertEquals(listOf("establish fd=100"), log.all.filter { it.startsWith("establish") })
        assertEquals(2, log.count("start ${profile.id} fd=100"))
        assertEquals(1, entries.count { it.event == LogEvent.Reconnected })
        // The stopped engine's traffic is the session's too.
        engine.trafficNow = EngineTraffic(bytesUp = 10, bytesDown = 20, activeConnections = 1, opened = 1)
        val traffic = checkNotNull(session.traffic())
        assertEquals(110, traffic.bytesUp)
        assertEquals(220, traffic.bytesDown)
        assertEquals(1, traffic.activeConnections)
        assertEquals(NOW, traffic.sinceMillis)

        session.disconnect()
        await { it.status == ConnectionStatus.Disconnected }
        val summary = entries.single { it.event == LogEvent.SessionSummary }
        assertEquals("110", summary.details[LogDetail.BYTES_UP])
        assertEquals("220", summary.details[LogDetail.BYTES_DOWN])
        assertEquals("4", summary.details[LogDetail.OPENED])
        assertEquals("1", summary.details[LogDetail.RECONNECTS])
        assertEquals((60_000).toString(), summary.details[LogDetail.DURATION_MS])
        assertNull("no session, no traffic", session.traffic())
        assertTrue("the minute's site failures are written with the summary", flushes.get() > 0)
    }

    @Test
    fun theTrafficNeverGoesBackWhileOneEngineStopsAndTheNextStarts() {
        connect()
        engine.trafficNow = EngineTraffic(bytesUp = 500, bytesDown = 700, activeConnections = 1)
        engine.stopMillis = 100 // a slow stop: readers keep reading meanwhile
        val seen = CopyOnWriteArrayList<TrafficStats>()
        val reading = AtomicBoolean(true)
        val reader = thread(name = "traffic-reader") { while (reading.get()) session.traffic()?.let(seen::add) }
        eventually("the reader reads the running engine") { seen.isNotEmpty() }

        escalate()
        await { it.status == ConnectionStatus.Connected }
        engine.trafficNow = EngineTraffic(bytesUp = 10, bytesDown = 20, activeConnections = 1) // the next engine's own
        eventually("the next engine's traffic is read") { seen.lastOrNull()?.bytesUp == 510L }
        reading.set(false)
        reader.join()

        assertTrue("read throughout: ${seen.size}", seen.size > 10)
        val dips = seen.zipWithNext().filter { (a, b) -> b.bytesUp < a.bytesUp || b.bytesDown < a.bytesDown }
        assertEquals("the counters never went back", emptyList<Pair<TrafficStats, TrafficStats>>(), dips.take(3))
        assertEquals(720, seen.last().bytesDown)
    }

    @Test
    fun attemptsStopAtTheLimitWithTrafficStillHeld() {
        val profile = connect()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE", detail = "no route to the proxy")
        escalate()

        val blocked = await { it.status == ConnectionStatus.Blocked }
        Thread.sleep(200) // several of the policy's waits: nothing more may happen

        assertEquals("one check per attempt, no more", 1 + policy.maxAttempts, log.count("probe ${profile.id}"))
        assertEquals("PROXY_UNREACHABLE", blocked.error?.code)
        assertEquals("reconnecting stopped after 3 attempts", blocked.error?.detail)
        assertFalse(host.tuns.single().closed)
        assertFalse(engine.running)
        assertEquals(listOf(1, 2, 3), state.history.mapNotNull { it.reconnect?.attempt }.distinct())
        assertEquals(2, entries.count { it.event == LogEvent.ReconnectAttemptFailed })
        assertEquals("3", entries.single { it.event == LogEvent.ReconnectGaveUp }.details[LogDetail.ATTEMPT])
        assertEquals(0, session.activeJobs())
    }

    @Test
    fun anAttemptRefusedForGoodBlocksWithoutMoreAttempts() {
        val profile = connect()
        engine.answer = PreflightAnswer(ok = false, code = "AUTH_FAILED")
        escalate()

        val blocked = await { it.status == ConnectionStatus.Blocked }

        assertEquals("AUTH_FAILED", blocked.error?.code)
        assertEquals(2, log.count("probe ${profile.id}"))
    }

    @Test
    fun disconnectingCancelsTheReconnectionWhileItWaits() {
        policy = RetryPolicy(delaysMillis = listOf(200, 200, 200), jitter = 0.0)
        val profile = connect()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        escalate()
        await { it.status == ConnectionStatus.Reconnecting }

        session.disconnect()
        await { it.status == ConnectionStatus.Disconnected }
        Thread.sleep(400)

        assertEquals("no attempt after the cancel", 1, log.count("probe ${profile.id}"))
        assertTrue(host.tuns.single().closed)
        assertEquals(0, session.activeJobs())
    }

    @Test
    fun disconnectingCancelsAnAttemptThatRuns() {
        val profile = connect()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        engine.gate = CountDownLatch(1)
        host.onAbort = { engine.gate?.countDown() }
        escalate()
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}", times = 2)

        session.disconnect()
        await { it.status == ConnectionStatus.Disconnected }

        assertEquals(1, host.aborts.get())
        eventually("the cancelled attempt's job ended") { session.activeJobs() == 0 }
        assertEquals(2, log.count("probe ${profile.id}"))
    }

    @Test
    fun retryNowRunsTheWaitingAttemptAndNeverASecondOne() {
        policy = RetryPolicy(delaysMillis = listOf(60_000, 60_000, 60_000), jitter = 0.0)
        val profile = connect()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        engine.gate = CountDownLatch(1)
        escalate()
        await { it.status == ConnectionStatus.Reconnecting && it.reconnect?.nextAttemptAtMillis != null }

        session.retryNow()
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}", times = 2)
        session.retryNow()
        session.retryNow()
        runBlocking { session.awaitIdle() }

        assertEquals("one attempt running, the extra taps ignored", 2, log.count("probe ${profile.id}"))
        assertNull(state.value.reconnect?.nextAttemptAtMillis)
        engine.gate?.countDown()
        val next = await { it.reconnect?.let { r -> r.attempt == 2 && r.nextAttemptAtMillis != null } == true }
        assertEquals(NOW + 60_000, next.reconnect?.nextAttemptAtMillis)
        eventually("one wait at a time") { session.activeJobs() == 1 }
    }

    /**
     * Retry now tapped just as the wait ends — its timer fired while the session's thread was busy: the attempt runs
     * once, never a second time beside itself.
     */
    @Test
    fun retryNowJustAsTheWaitEndsRunsTheAttemptOnce() {
        policy = RetryPolicy(delaysMillis = listOf(300, 300, 300), jitter = 0.0)
        val profile = connect()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        engine.gate = CountDownLatch(1) // attempts wait in their check, so a second one beside the first would show
        host.onAbort = { engine.gate?.countDown() }
        escalate()
        await { it.reconnect?.nextAttemptAtMillis != null }

        val release = CountDownLatch(1)
        executor.execute { release.await() } // the session's thread is busy when the wait ends...
        Thread.sleep(500)
        session.retryNow() // ...and Retry now comes before the thread gets to the wait's end
        release.countDown()
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}", times = 2)
        Thread.sleep(100)
        runBlocking { session.awaitIdle() }

        assertEquals("one attempt: ${sessionLog.all}", 1, sessionLog.count("reconnect attempt 1 of 3"))
        assertEquals("the connect's check and the attempt's: ${log.all}", 2, log.count("probe ${profile.id}"))
        session.disconnect()
        await { it.status == ConnectionStatus.Disconnected }
        assertEquals(1, host.aborts.get())
        eventually("no check left running") { session.activeJobs() == 0 }
    }

    /** The time without a connection counts from when traffic was first held, through Offline and every attempt after. */
    @Test
    fun theOutageCountsFromWhenTrafficWasFirstHeld() {
        connect()
        session.underlyingNetwork(WIFI)
        now.set(NOW + 1_000)
        session.underlyingNetwork(null)
        await { it.status == ConnectionStatus.Offline }
        now.set(NOW + 5_000)
        session.underlyingNetwork(MOBILE)
        await { it.status == ConnectionStatus.Connected }

        val reconnected = entries.single { it.event == LogEvent.Reconnected }
        assertEquals("4000", reconnected.details[LogDetail.OUTAGE_MS])
        assertEquals(ReconnectReason.NetworkBack.key, reconnected.details[LogDetail.REASON])
    }

    /**
     * A switch to another profile that failed, then was tried again on its own after a network change, connects that
     * profile for the first time: logged as a connection with its set-up time, its session starting then.
     */
    @Test
    fun aProfileFirstConnectedByARetryIsLoggedAsConnectedNotReconnected() {
        connect()
        session.underlyingNetwork(WIFI)
        val other = save()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        session.connect(other.id)
        assertEquals("PROXY_UNREACHABLE", await { it.status == ConnectionStatus.Blocked && it.profileId == other.id }.error?.code)

        engine.answer = PreflightAnswer(ok = true)
        now.set(NOW + 9_000)
        session.underlyingNetwork(MOBILE)
        val connected = await { it.status == ConnectionStatus.Connected }

        assertEquals(other.id, connected.profileId)
        assertEquals("its session starts now", NOW + 9_000, connected.connectedSinceMillis)
        val last = entries.last { it.event == LogEvent.Connected || it.event == LogEvent.Reconnected }
        assertEquals(LogEvent.Connected, last.event)
        assertEquals(other.id, last.profileId)
        assertEquals("0", last.details[LogDetail.SETUP_MS])
        assertEquals(0, entries.count { it.event == LogEvent.Reconnected })
    }

    /** The network goes away while an attempt runs: the attempt is cancelled, traffic waits Offline, and no attempt runs until it is back. */
    @Test
    fun losingTheNetworkWhileAnAttemptRunsCancelsItAndWaitsOffline() {
        val profile = connect()
        session.underlyingNetwork(WIFI)
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        engine.gate = CountDownLatch(1)
        host.onAbort = { engine.gate?.countDown() }
        escalate()
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}", times = 2)

        session.underlyingNetwork(null)
        val offline = await { it.status == ConnectionStatus.Offline }
        assertNull(offline.reconnect)
        assertEquals(1, host.aborts.get())
        eventually("the cancelled attempt's job ended") { session.activeJobs() == 0 }
        Thread.sleep(200) // several of the policy's waits: no attempt without a network
        assertEquals(2, log.count("probe ${profile.id}"))
        assertFalse(engine.running)
        assertFalse(host.tuns.single().closed)

        engine.answer = PreflightAnswer(ok = true)
        session.underlyingNetwork(MOBILE)
        await { it.status == ConnectionStatus.Connected }
        assertEquals(3, log.count("probe ${profile.id}"))
    }

    /** After the last attempt traffic stays held: a move to another network tries again, and losing the network waits Offline. */
    @Test
    fun afterTheLastAttemptANetworkChangeTriesAgainAndALossWaitsOffline() {
        val profile = connect()
        session.underlyingNetwork(WIFI)
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        escalate()
        await { it.status == ConnectionStatus.Blocked }
        val probes = log.count("probe ${profile.id}")

        session.underlyingNetwork(MOBILE)
        await { it.status == ConnectionStatus.Blocked && log.count("probe ${profile.id}") == probes + policy.maxAttempts }
        assertEquals(ReconnectReason.NetworkChanged, state.history.last { it.reconnect != null }.reconnect?.reason)

        session.underlyingNetwork(null)
        await { it.status == ConnectionStatus.Offline }
        engine.answer = PreflightAnswer(ok = true)
        session.underlyingNetwork(WIFI)
        val back = await { it.status == ConnectionStatus.Connected }
        assertEquals(NOW, back.connectedSinceMillis)
        assertFalse(host.tuns.single().closed)
    }

    /**
     * What no retry changes stays blocked whatever the network does: a refused sign-in or certificate, an engine
     * that stopped, a deleted profile (whose credentials are never used again). Only the user acts on these.
     */
    @Test
    fun aNetworkChangeNeverRetriesWhatNoRetryChanges() {
        for (cause in listOf("AUTH_FAILED", "TLS_UNTRUSTED", "ENGINE_STOPPED", "PROFILE_DELETED")) {
            val profile = connect()
            session.underlyingNetwork(WIFI)
            when (cause) {
                "ENGINE_STOPPED" -> checkNotNull(engine.signals)(EngineSignal.Failed(ConnectionError(cause, "tun closed")))
                "PROFILE_DELETED" -> session.profileDeleted(profile.id)
                else -> proxyFailure(cause)
            }
            await { it.status == ConnectionStatus.Blocked }
            val probes = log.count("probe ${profile.id}")

            session.underlyingNetwork(MOBILE)
            session.underlyingNetwork(null)
            session.underlyingNetwork(WIFI)
            runBlocking { session.awaitIdle() }

            assertEquals(cause, ConnectionStatus.Blocked, state.value.status)
            assertEquals(cause, cause, state.value.error?.code)
            assertEquals("$cause: no attempt", probes, log.count("probe ${profile.id}"))
            assertEquals(cause, 0, entries.count { it.event == LogEvent.ReconnectStarted })
            session.disconnect()
            await { it.status == ConnectionStatus.Disconnected }
        }
    }

    /** Disconnect or a revocation while Offline or reconnecting ends everything, and a network coming back later changes nothing. */
    @Test
    fun disconnectOrRevocationWhileOfflineOrReconnectingEndsEverything() {
        policy = RetryPolicy(delaysMillis = listOf(60_000, 60_000, 60_000), jitter = 0.0)
        for (revoke in listOf(false, true)) {
            for (offline in listOf(true, false)) {
                val what = "${if (revoke) "revoked" else "disconnected"} while ${if (offline) "offline" else "reconnecting"}"
                connect()
                session.underlyingNetwork(WIFI)
                if (offline) {
                    session.underlyingNetwork(null)
                    await { it.status == ConnectionStatus.Offline }
                } else {
                    escalate()
                    await { it.status == ConnectionStatus.Reconnecting }
                }

                if (revoke) session.revoked(host) else session.disconnect()
                val ended = await { it.status == ConnectionStatus.Disconnected }
                session.underlyingNetwork(MOBILE)
                runBlocking { session.awaitIdle() }

                assertEquals(what, if (revoke) ConnectionError(ConnectionError.VPN_REVOKED) else null, ended.error)
                assertEquals(what, ConnectionStatus.Disconnected, state.value.status)
                assertTrue(what, host.tuns.last().closed)
                assertFalse(what, engine.running)
                eventually("$what: every job ended") { session.activeJobs() == 0 }
            }
        }
    }

    /** An attempt whose engine does not start, or that Android does not route in time, fails alone: the next attempt follows. */
    @Test
    fun anAttemptWhoseEngineFailsToStartOrIsNotRoutedFailsAlone() {
        for (failure in listOf("engine", "routing")) {
            val profile = connect()
            val established = log.count("establish")
            if (failure == "engine") engine.startError = EngineException("INTERNAL", "invalid TUN descriptor") else host.routes = false
            escalate()
            await { it.reconnect?.attempt == 2 }
            engine.startError = null
            host.routes = true
            val back = await { it.status == ConnectionStatus.Connected }

            assertEquals(failure, NOW, back.connectedSinceMillis)
            val failed = entries.single { it.event == LogEvent.ReconnectAttemptFailed }
            assertEquals(failure, "INTERNAL", failed.code)
            assertEquals("$failure: the same interface throughout", established, log.count("establish"))
            assertFalse(failure, host.tuns.last().closed)
            assertEquals(failure, 3, log.count("probe ${profile.id}"))
            session.disconnect()
            await { it.status == ConnectionStatus.Disconnected }
        }
    }

    /**
     * The profile is edited while an attempt checks it: its new settings are checked from the start, so the new
     * server is the one connected and no credential goes to the old one after the edit.
     */
    @Test
    fun anEditDuringAReconnectionChecksTheNewSettingsFromTheStart() {
        val profile = connect(signIn = true)
        engine.gate = CountDownLatch(1)
        escalate()
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}", times = 2)

        runBlocking { profiles.repository.save(profile.id, input(server = "other.example.net", signIn = true)) }
        engine.gate?.countDown()
        await { it.status == ConnectionStatus.Connected && it.profile?.host == "other.example.net" }

        assertEquals(
            listOf("probe $SERVER", "start $SERVER", "probe $SERVER", "probe other.example.net", "start other.example.net"),
            engine.servers.toList(),
        )
        assertFalse(host.tuns.single().closed)
    }

    /**
     * The traffic of an engine that failed stays in the session's counters, while Blocked and after a restart (the next
     * engine's is added to it): they never go back. The fake engine reports a failed engine's counters until it is
     * stopped, as the real one does — which [aFailedEnginesCountersAreStillRead] checks of the real one.
     */
    @Test
    fun anEngineFailureKeepsItsTrafficInTheSession() {
        connect()
        engine.trafficNow = EngineTraffic(bytesUp = 300, bytesDown = 400, activeConnections = 1)
        checkNotNull(engine.signals)(EngineSignal.Failed(ConnectionError("ENGINE_STOPPED", "tun closed")))
        assertEquals("ENGINE_STOPPED", await { it.status == ConnectionStatus.Blocked }.error?.code)
        assertEquals(300L, session.traffic()?.bytesUp)

        session.restart()
        await { it.status == ConnectionStatus.Connected }
        engine.trafficNow = EngineTraffic(bytesUp = 5, bytesDown = 6)
        assertEquals(305L, session.traffic()?.bytesUp)
        assertEquals(406L, session.traffic()?.bytesDown)
    }

    /** The real engine's counters are read while it runs and after it failed (until it is stopped), never once it stopped. */
    @Test
    fun aFailedEnginesCountersAreStillRead() {
        val native = StatsNative()
        val tunnel = ProxyTunnelEngine(ProxyEngine(native))
        native.stats = """{"state":"running","bytes_up":7,"bytes_down":9,"tcp_active":1}"""
        assertEquals(7L, tunnel.traffic()?.bytesUp)
        native.stats = """{"state":"failed","bytes_up":8,"bytes_down":11,"tcp_active":0}"""
        assertEquals(8L, tunnel.traffic()?.bytesUp)
        assertEquals(11L, tunnel.traffic()?.bytesDown)
        assertNull("Home's diagnostics are for a running engine only", tunnel.diagnostics())
        native.stats = "{}"
        assertNull(tunnel.traffic())
    }

    @Test
    fun proxyFailuresWhileReconnectingStartNoSecondReconnection() {
        policy = RetryPolicy(delaysMillis = listOf(60_000, 60_000, 60_000), jitter = 0.0)
        connect()
        val signals = checkNotNull(engine.signals)
        escalate()
        await { it.status == ConnectionStatus.Reconnecting }

        repeat(5) { signals(EngineSignal.ProxyFailure(ConnectionError("PROXY_UNREACHABLE"))) }
        runBlocking { session.awaitIdle() }

        assertEquals(1, entries.count { it.event == LogEvent.ReconnectStarted })
        eventually("one wait, no second reconnection") { session.activeJobs() == 1 }
        assertEquals(1, state.value.reconnect?.attempt)
    }

    @Test
    fun withoutANetworkTrafficWaitsOfflineAndReconnectsWhenOneIsBack() {
        val profile = connect()
        session.underlyingNetwork(WIFI)
        runBlocking { session.awaitIdle() }
        assertEquals("Android's first report changes nothing", ConnectionStatus.Connected, state.value.status)

        session.underlyingNetwork(null)
        val offline = await { it.status == ConnectionStatus.Offline }
        assertEquals(ConnectionError.OFFLINE, offline.error?.code)
        assertFalse(engine.running)
        assertFalse(host.tuns.single().closed)
        assertEquals(NOW, offline.connectedSinceMillis)

        session.underlyingNetwork(MOBILE)
        val back = await { it.status == ConnectionStatus.Connected }

        assertEquals(NOW, back.connectedSinceMillis)
        assertEquals(2, log.count("start ${profile.id} fd=100"))
        assertEquals(ReconnectReason.NetworkBack, state.history.firstNotNullOf { it.reconnect }.reason)
        assertEquals("attempt 1 runs at once", NOW, state.history.firstNotNullOf { it.reconnect }.nextAttemptAtMillis)
        assertEquals(
            listOf(LogEvent.Offline, LogEvent.NetworkChanged, LogEvent.ReconnectStarted, LogEvent.Reconnected),
            entries.map { it.event }.filter { it in NETWORK_EVENTS },
        )
    }

    @Test
    fun aMoveToAnotherNetworkEndsTheEnginesConnectionsAndStaysConnected() {
        connect()
        engine.openConnections = 7
        session.underlyingNetwork(WIFI)
        session.underlyingNetwork(MOBILE)
        runBlocking { session.awaitIdle() }

        assertEquals(ConnectionStatus.Connected, state.value.status)
        assertTrue(engine.running)
        assertEquals(1, engine.resets.get())
        val changed = entries.single { it.event == LogEvent.NetworkChanged }
        assertEquals("cellular", changed.details[LogDetail.NETWORK])
        assertEquals("7", changed.details[LogDetail.RESET])

        session.underlyingNetwork(MOBILE)
        runBlocking { session.awaitIdle() }
        assertEquals("the same network again is no change", 1, engine.resets.get())
    }

    @Test
    fun aMoveToAnotherNetworkWhileReconnectingStartsTheAttemptsOver() {
        policy = RetryPolicy(delaysMillis = listOf(60_000, 60_000, 60_000), jitter = 0.0)
        connect()
        session.underlyingNetwork(WIFI)
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        escalate()
        await { it.status == ConnectionStatus.Reconnecting }

        session.underlyingNetwork(MOBILE)
        await { it.reconnect?.let { r -> r.reason == ReconnectReason.NetworkChanged && r.nextAttemptAtMillis == NOW + 60_000 } == true }

        // Each attempt as published: due, running (nothing due), and the next one due.
        val again = state.history.mapNotNull { it.reconnect }.filter { it.reason == ReconnectReason.NetworkChanged }
        assertEquals(
            "attempt 1 at once, then attempt 2 after its wait",
            listOf(1 to NOW, 1 to null, 2 to NOW + 60_000),
            again.map { it.attempt to it.nextAttemptAtMillis }.distinct(),
        )
        assertEquals(2, entries.count { it.event == LogEvent.ReconnectStarted })
        eventually("one wait at a time") { session.activeJobs() == 1 }
    }

    @Test
    fun networkReportsWithoutAConnectionChangeNothing() {
        session.underlyingNetwork(WIFI)
        session.underlyingNetwork(null)
        session.underlyingNetwork(MOBILE)
        runBlocking { session.awaitIdle() }

        assertEquals(ConnectionState.Idle, state.value)
        assertEquals(emptyList<LogEntry>(), entries.toList())
        assertEquals("the last report is kept for diagnosis", MOBILE, session.lastNetwork)
    }

    @Test
    fun rapidConnectsAndDisconnectsLeaveOneConsistentEnd() {
        val profile = save()
        repeat(CYCLES) {
            session.connect(profile.id)
            if (it % 3 == 0) session.underlyingNetwork(if (it % 2 == 0) WIFI else MOBILE)
            session.disconnect()
        }
        runBlocking { session.awaitIdle() }
        eventually("every job ended") { session.activeJobs() == 0 }

        assertEquals(ConnectionStatus.Disconnected, state.value.status)
        assertNull(state.value.reconnect)
        assertTrue(host.tuns.all { it.closed })
        assertFalse(engine.running)
        assertNull(session.traffic())
        val requested = entries.count { it.event == LogEvent.ConnectRequested }
        val ended = entries.count { it.event in setOf(LogEvent.Stopped, LogEvent.ConnectFailed) }
        assertEquals("every connect logged ends logged", requested, ended)
    }

    @Test
    fun sitesConnectionFailuresAreCountedForTheLogWithoutTouchingTheSession() {
        val profile = connect()
        val signals = checkNotNull(engine.signals)

        repeat(4) { signals(EngineSignal.ConnectionFailure("TARGET_UNREACHABLE")) }
        runBlocking { session.awaitIdle() }

        assertEquals(List(4) { "TARGET_UNREACHABLE" to profile.id }, problems.toList())
        assertEquals(ConnectionStatus.Connected, state.value.status)
    }

    @Test
    fun theRetryPolicyBacksOffWithBoundedJitter() {
        val default = RetryPolicy()
        assertEquals(8, default.maxAttempts)
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000), (1..8).map { default.delayBefore(it, 0.5) })
        assertEquals(800, default.delayBefore(1, 0.0))
        assertEquals(1_200, default.delayBefore(1, 1.0))
        assertTrue((1..8).all { n -> default.delayBefore(n, 0.99) <= default.delaysMillis[n - 1] * 1.2 })
        assertEquals("about two minutes of attempts in all", 121_000L, default.delaysMillis.sum())
        for (code in RetryPolicy.BLOCK_AT_ONCE) assertFalse(code, RetryPolicy.recoverable(code))
        assertTrue(RetryPolicy.recoverable("PROXY_UNREACHABLE"))
    }

    @Test
    fun theEnginesEventsBecomeSignals() {
        assertEquals(
            EngineSignal.Failed(ConnectionError("ENGINE_STOPPED", "tun closed")),
            ProxyTunnelEngine.signalOf("engine_failed", "ENGINE_STOPPED", "tun closed"),
        )
        assertEquals(
            EngineSignal.ProxyFailure(ConnectionError("HANDSHAKE_TIMEOUT", "late")),
            ProxyTunnelEngine.signalOf("conn_fail", "HANDSHAKE_TIMEOUT", "late"),
        )
        assertEquals(EngineSignal.ConnectionFailure("TARGET_REFUSED"), ProxyTunnelEngine.signalOf("conn_fail", "TARGET_REFUSED", null))
        assertNull("a set-up the engine cancelled is no failure", ProxyTunnelEngine.signalOf("conn_fail", "CANCELED", null))
        assertNull(ProxyTunnelEngine.signalOf("conn_open", null, null))
        // M8: a SOCKS5 request the proxy left unanswered is its own signal; a proxy-level failure stays one.
        assertEquals(
            EngineSignal.Unanswered(ConnectionError("TARGET_UNREACHABLE", "no reply")),
            ProxyTunnelEngine.signalOf("conn_fail", "TARGET_UNREACHABLE", "no reply", unanswered = true),
        )
        assertEquals(
            EngineSignal.ProxyFailure(ConnectionError("HANDSHAKE_TIMEOUT", null)),
            ProxyTunnelEngine.signalOf("conn_fail", "HANDSHAKE_TIMEOUT", null, unanswered = true),
        )
        assertNull(ProxyTunnelEngine.signalOf("conn_fail", "CANCELED", null, unanswered = true))
    }

    // --- helpers ---

    private fun save(signIn: Boolean = false): ProxyProfile = runBlocking { profiles.repository.save(null, input(signIn = signIn)) }

    private fun input(server: String = SERVER, signIn: Boolean = false) = ProxyProfileInput(
        name = "Tokyo",
        protocol = ProxyProtocol.SOCKS5,
        server = server,
        port = "1080",
        requiresSignIn = signIn,
        username = if (signIn) USER else "",
        password = if (signIn) PASSWORD else "",
    )

    /** The Go engine's functions, of which only its counters are read. */
    private class StatsNative : EngineNative {
        @Volatile var stats = "{}"

        override fun stats(): String = stats

        override fun start(tunFd: Long, configJson: String, secret: ByteArray, platform: Platform) = error("not used")

        override fun stop() = Unit

        override fun status(): String = "{}"

        override fun flows(): String = "[]"

        override fun resetFlows(): Long = 0

        override fun probe(configJson: String, secret: ByteArray, target: String, timeoutMs: Long, platform: Platform): String =
            error("not used")

        override fun selfTest(
            configJson: String,
            secret: ByteArray,
            target: String,
            payload: ByteArray,
            expect: Long,
            timeoutMs: Long,
            platform: Platform,
        ): String = error("not used")

        override fun networkPlan(): String = error("not used")

        override fun version(): String = "test"
    }

    private fun connect(signIn: Boolean = false): ProxyProfile {
        entries.clear()
        val profile = save(signIn)
        session.connect(profile.id)
        await { it.status == ConnectionStatus.Connected }
        return profile
    }

    private fun proxyFailure(code: String) {
        checkNotNull(engine.signals)(EngineSignal.ProxyFailure(ConnectionError(code)))
        runBlocking { session.awaitIdle() }
    }

    /** Three failures at the proxy in a row: the session starts reconnecting. */
    private fun escalate() {
        engine.inARow = 3
        proxyFailure("PROXY_UNREACHABLE")
    }

    private fun await(predicate: (ConnectionState) -> Boolean): ConnectionState = runBlocking {
        val matched = withTimeout(TIMEOUT_MS) { state.first(predicate) }
        session.awaitIdle()
        matched
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting until $what" }
            Thread.sleep(5)
        }
    }

    private fun awaitLog(entry: String, times: Int = 1) = eventually("logged $times× $entry; log ${log.all}") { log.count(entry) >= times }

    private fun assertNothingSecretLogged() {
        val text = entries.joinToString("\n")
        for (secret in listOf(SERVER, USER, PASSWORD)) assertFalse("\"$secret\" in the log: $text", text.contains(secret))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val TIMEOUT_MS = 5_000L
        const val CYCLES = 30
        const val SERVER = "proxy.example.com"
        const val USER = "alice"
        const val PASSWORD = "hunter2-secret"
        val WIFI = VpnSession.NetworkInfo("net 101", "wifi")
        val MOBILE = VpnSession.NetworkInfo("net 102", "cellular")
        val NETWORK_EVENTS = setOf(LogEvent.Offline, LogEvent.NetworkChanged, LogEvent.ReconnectStarted, LogEvent.Reconnected)
    }
}
