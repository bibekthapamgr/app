package app.proxyflow.core.vpn

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
            session.attach(host, alwaysOn = false)
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
    }

    // --- helpers ---

    private fun save(signIn: Boolean = false): ProxyProfile = runBlocking {
        profiles.repository.save(
            null,
            ProxyProfileInput(
                name = "Tokyo",
                protocol = ProxyProtocol.SOCKS5,
                server = SERVER,
                port = "1080",
                requiresSignIn = signIn,
                username = if (signIn) USER else "",
                password = if (signIn) PASSWORD else "",
            ),
        )
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
