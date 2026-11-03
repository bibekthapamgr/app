package app.proxyflow.core.vpn

import app.proxyflow.core.data.ConnectedMarker
import app.proxyflow.core.data.InMemoryProtectionStore
import app.proxyflow.core.data.ProtectionStore
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
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.testing.TestProfiles
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M8 (ADR-0013) with the fake engine and service of [VpnSessionTest]: a start by Android for Always-on VPN — no chosen
 * profile, traffic held from the start, attempts that go on, a refused sign-in — what Android says of its kill switch,
 * the marker a connection leaves for the next process and the interruption that process finds, and a SOCKS5 proxy
 * that leaves connection requests unanswered.
 */
class KillSwitchTest {
    private val log = CallLog()
    private val sessionLog = CallLog()
    private val engine = FakeEngine(log)
    private val host = FakeHost(log)
    private val profiles = TestProfiles(dispatcher = Dispatchers.Unconfined)
    private val state = RecordingState()
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val now = AtomicLong(NOW)
    private val history = FakeHistory()

    @Volatile private var policy = RetryPolicy(delaysMillis = listOf(30, 30, 30), jitter = 0.0)

    private val sink = object : DiagnosticsSink {
        override fun record(entry: LogEntry) {
            entries += entry
        }

        override fun connectionFailed(code: String, profileId: String?) = Unit

        override fun flushProblems() = Unit
    }

    private var protection = InMemoryProtectionStore()
    private var started: VpnSession? = null

    /** The session; [protection] as a test set it up first (what an earlier process left). */
    private val session: VpnSession get() = started ?: VpnSession(
        profiles = profiles.repository,
        engine = engine,
        services = {
            started?.attach(host, alwaysOn = false, startId = host.started())
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
        protection = protection,
        history = history,
    ).also { started = it }

    /** A session on [store] instead of [protection] (a store that fails, or that counts its reads). */
    private fun newSession(store: ProtectionStore) = VpnSession(
        profiles = profiles.repository,
        engine = engine,
        services = {
            started?.attach(host, alwaysOn = false, startId = host.started())
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
        protection = store,
        history = history,
    )

    @After
    fun tearDown() {
        engine.gate?.countDown()
        scope.cancel()
        executor.shutdownNow()
        assertEquals("interfaces established while another was open: ${log.all}", 0, host.overlaps.get())
    }

    // --- Android's Always-on VPN -----------------------------------------------------------------------------------

    @Test
    fun anAlwaysOnStartWithNoChosenProfileConnectsNothingAndSaysSo() {
        save() // a profile exists, but the user never chose one: ProxyFlow never picks it for them

        alwaysOnStart()
        val shown = await { it.error?.code == ConnectionError.ALWAYS_ON_NO_PROFILE }

        assertEquals(ConnectionStatus.Disconnected, shown.status)
        assertTrue("no interface: ${log.all}", host.tuns.isEmpty())
        assertEquals("no probe of any proxy", 0, log.count("probe"))
        assertEquals("the user is told", ConnectionError.ALWAYS_ON_NO_PROFILE, host.problems.single().error?.code)
        assertTrue("the service is let go: ${log.all}", log.indexOf("host stop") >= 0)
        val failed = entries.single { it.event == LogEvent.ConnectFailed }
        assertEquals(ConnectionError.ALWAYS_ON_NO_PROFILE, failed.code)
        assertEquals("always_on", failed.details[LogDetail.BY])
    }

    @Test
    fun anAlwaysOnStartHoldsTrafficBeforeTheProxyIsChecked() {
        val profile = choose(save())
        engine.gate = CountDownLatch(1)

        alwaysOnStart()
        eventually("the check runs") { log.count("probe") == 1 }

        val establish = log.indexOf("establish fd=${FakeHost.FIRST_FD}")
        assertTrue(
            "the interface exists before the proxy is asked: ${log.all}",
            establish in 0 until log.indexOf("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}"),
        )
        assertEquals(ConnectionStatus.Connecting, state.value.status)
        assertTrue("Home says traffic is held, and Cancel asks first", state.value.held)
        assertFalse("no engine yet: what apps send is held", engine.running)
        eventually("held traffic is the VPN running: the marker is written") { protection.connected != null }
        assertEquals("always_on", entries.first { it.event == LogEvent.ConnectRequested }.details[LogDetail.BY])

        engine.gate?.countDown()
        val connected = await { it.status == ConnectionStatus.Connected }
        assertEquals(profile.id, connected.profileId)
        assertFalse(connected.held)
        assertEquals("one marker for the session", 1, history.markers.get().toInt())
        assertEquals("one interface, the held one", 1, host.tuns.size)
        assertTrue("the engine reads it", engine.running)
        assertEquals(LogEvent.Connected, entries.last { it.event == LogEvent.Connected || it.event == LogEvent.Reconnected }.event)
    }

    @Test
    fun anAlwaysOnStartThatCantReachTheProxyKeepsTrafficHeldAndTriesAgain() {
        val profile = choose(save())
        policy = RetryPolicy(delaysMillis = listOf(60_000, 60_000), jitter = 0.0)
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE", detail = "no route to the proxy")
        host.killSwitch = KillSwitchReading(alwaysOn = true, blocksWithoutVpn = true)

        alwaysOnStart()
        val reconnecting = await { it.status == ConnectionStatus.Reconnecting && it.reconnect != null }

        val retry = checkNotNull(reconnecting.reconnect)
        assertEquals(ReconnectReason.AlwaysOnStart, retry.reason)
        assertEquals("PROXY_UNREACHABLE", retry.lastError?.code)
        assertFalse("the interface stays: apps get nowhere around the proxy", host.tuns.single().closed)
        assertNull("not connected yet", reconnecting.connectedSinceMillis)
        eventually("with the interface up, what Android says is read before the proxy answers") { stored()?.status == KillSwitchStatus.On }

        // The proxy answers again: the next attempt connects on the same interface.
        engine.answer = PreflightAnswer(ok = true)
        session.retryNow()
        val connected = await { it.status == ConnectionStatus.Connected }
        assertEquals(profile.id, connected.profileId)
        assertEquals(1, host.tuns.size)
        assertFalse(host.tuns.single().closed)
    }

    /** A failure that isn't the proxy's (an unexpected one) during an Always-on start's attempts ends them: bounded. */
    @Test
    fun anUnexpectedFailureDuringAnAlwaysOnStartsAttemptsBlocks() {
        choose(save())
        policy = RetryPolicy(delaysMillis = listOf(60_000, 60_000), jitter = 0.0)
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        alwaysOnStart()
        await { it.status == ConnectionStatus.Reconnecting && it.reconnect != null }

        host.guardError = IllegalStateException("bug") // the next attempt throws where nothing expects it
        session.retryNow()
        val blocked = await { it.status == ConnectionStatus.Blocked }

        assertEquals(ConnectionError.INTERNAL, blocked.error?.code)
        assertFalse("traffic stays held", host.tuns.single().closed)
        assertEquals("no attempt started over", 1, sessionLog.count("reconnecting: always_on_start"))
    }

    @Test
    fun anAlwaysOnStartWhoseSignInIsRefusedStaysBlocked() {
        choose(save())
        engine.answer = PreflightAnswer(ok = false, code = "AUTH_FAILED", detail = "proxy rejected the username or password")

        alwaysOnStart()
        val blocked = await { it.status == ConnectionStatus.Blocked }

        assertEquals("AUTH_FAILED", blocked.error?.code)
        assertNull("no retry changes a refused sign-in", blocked.reconnect)
        assertFalse("traffic stays held", host.tuns.single().closed)
    }

    @Test
    fun anAlwaysOnStartWithoutConsentLeavesNothing() {
        choose(save())
        host.establishReturnsNull = true // the user withdrew the VPN permission since

        alwaysOnStart()
        val ended = await { it.status == ConnectionStatus.Disconnected && it.error != null }

        assertEquals(ConnectionError.VPN_PERMISSION_DENIED, ended.error?.code)
        assertEquals(0, log.count("probe"))
    }

    @Test
    fun anAlwaysOnStartWhileConnectedKeepsTheConnectionAndAsksAndroidAgain() {
        val profile = connect()
        val asked = host.killSwitchQuestions.get()
        host.killSwitch = KillSwitchReading(alwaysOn = true, blocksWithoutVpn = true)

        alwaysOnStart() // the user just set ProxyFlow as Always-on VPN in Android's settings
        runBlocking { session.awaitIdle() }

        assertEquals(ConnectionStatus.Connected, state.value.status)
        assertEquals(profile.id, state.value.profileId)
        assertEquals("one probe: the connection was not started again", 1, log.count("probe"))
        assertTrue(host.killSwitchQuestions.get() > asked)
        eventually("the new answer is kept") { stored()?.status == KillSwitchStatus.On }
    }

    @Test
    fun aDisconnectedSessionHoldsNothingAfterAnAlwaysOnStart() {
        choose(save())
        alwaysOnStart()
        await { it.status == ConnectionStatus.Connected }

        session.disconnect()
        await { it.status == ConnectionStatus.Disconnected }

        assertTrue(host.tuns.single().closed)
        // A failure after a stop is an ordinary attempt again: it never holds traffic.
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        session.connect(profiles.repository.let { runBlocking { it.chosenProfileId.first() } }!!)
        val ended = await { it.status == ConnectionStatus.Disconnected && it.error != null }
        assertEquals("PROXY_UNREACHABLE", ended.error?.code)
        assertTrue("no interface is left", host.tuns.all { it.closed })
    }

    // --- Android's kill switch -------------------------------------------------------------------------------------

    @Test
    fun whatAndroidSaysOfItsKillSwitchIsKeptAndLoggedWhenItChanges() {
        host.killSwitch = KillSwitchReading(alwaysOn = true, blocksWithoutVpn = true)
        connect()
        eventually("the reading is kept") { stored() != null }

        assertEquals(SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = NOW), stored())
        val logged = entries.single { it.event == LogEvent.SystemKillSwitch }
        assertEquals(mapOf(LogDetail.ALWAYS_ON to "yes", LogDetail.BLOCKS_WITHOUT_VPN to "yes"), logged.details)

        // Asked again with nothing changed: kept with its new time, not logged again.
        now.set(NOW + 1_000)
        session.checkSystemKillSwitch()
        eventually("the time moves on") { stored()?.checkedAtMillis == NOW + 1_000 }
        assertEquals(1, entries.count { it.event == LogEvent.SystemKillSwitch })

        // Turned off in Android's settings: logged.
        host.killSwitch = KillSwitchReading(alwaysOn = false, blocksWithoutVpn = false)
        session.checkSystemKillSwitch()
        eventually("the change is kept") { stored()?.status == KillSwitchStatus.Off }
        assertEquals(2, entries.count { it.event == LogEvent.SystemKillSwitch })
    }

    @Test
    fun withoutAnInterfaceAndroidIsNotAskedAndTheLastAnswerStands() {
        val earlier = SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = NOW - 60_000)
        protection = InMemoryProtectionStore(killSwitch = earlier)
        session.checkSystemKillSwitch()
        runBlocking { session.awaitIdle() }

        assertEquals("Android says false for every app whose VPN doesn't run: not asked", 0, host.killSwitchQuestions.get())
        assertEquals(earlier, stored())
    }

    @Test
    fun aReadingAndroidCantGiveChangesNothing() {
        host.killSwitch = null
        connect()
        runBlocking { session.awaitIdle() }
        assertNull(stored())
        assertTrue(entries.none { it.event == LogEvent.SystemKillSwitch })
    }

    // --- what outlives the process ---------------------------------------------------------------------------------

    @Test
    fun aConnectionLeavesAMarkerUntilItIsStopped() {
        val profile = connect()
        eventually("the marker is written") { protection.connected != null }
        assertEquals(ConnectedMarker(profile.id, NOW, pid = FakeHistory.PID, bootCount = FakeHistory.BOOT), protection.connected)

        // Held traffic still needs the marker: the process ending now would release it.
        engine.failure!!(ConnectionError(ConnectionError.ENGINE_STOPPED))
        await { it.status == ConnectionStatus.Blocked }
        runBlocking { session.awaitIdle() }
        assertNotNull(protection.connected)

        session.disconnect()
        await { it.status == ConnectionStatus.Disconnected }
        eventually("the marker is gone") { protection.connected == null }
    }

    @Test
    fun aReconnectionKeepsTheMarkerOfItsSession() {
        val profile = connect()
        eventually("the marker is written") { protection.connected != null }
        engine.inARow = 3
        now.set(NOW + 5_000)
        checkNotNull(engine.signals)(EngineSignal.ProxyFailure(ConnectionError("PROXY_UNREACHABLE")))
        await { it.status == ConnectionStatus.Reconnecting }
        session.retryNow()
        await { it.status == ConnectionStatus.Connected }
        runBlocking { session.awaitIdle() }

        assertEquals(
            "the session's start, not the reconnection's",
            ConnectedMarker(profile.id, NOW, FakeHistory.PID, FakeHistory.BOOT),
            protection.connected,
        )
        assertEquals(1, history.markers.get().toInt())
    }

    @Test
    fun aFailedFirstAttemptLeavesNoMarker() {
        val profile = save()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        session.connect(profile.id)
        await { it.status == ConnectionStatus.Disconnected && it.error != null }
        runBlocking { session.awaitIdle() }
        assertNull(protection.connected)
        assertEquals(0, history.markers.get().toInt())
    }

    @Test
    fun theMarkerOfAProcessThatEndedWhileConnectedIsLoggedAndKeptForHome() {
        val left = ConnectedMarker(profileId = "p-left", sinceMillis = NOW - 600_000, pid = 4242, bootCount = FakeHistory.BOOT)
        protection = InMemoryProtectionStore(connected = left)
        history.ending = ProcessHistory.Ending(ExitReason.LowMemory, atMillis = NOW - 60_000)

        session.checkSystemKillSwitch() // anything: the session starts
        eventually("the interruption is kept") { runBlocking { protection.interruption.first() } != null }

        assertEquals(
            Interruption(
                profileId = "p-left",
                connectedSinceMillis = NOW - 600_000,
                endedAtMillis = NOW - 60_000,
                reason = ExitReason.LowMemory,
                detectedAtMillis = NOW,
            ),
            runBlocking { protection.interruption.first() },
        )
        assertEquals(left, history.asked.single())
        assertNull("the marker became the interruption", protection.connected)
        eventually("logged") { entries.any { it.event == LogEvent.SessionInterrupted } }
        val logged = entries.single { it.event == LogEvent.SessionInterrupted }
        assertEquals(LogEntry.Level.Error, logged.level)
        assertEquals(ConnectionStage.Stopped, logged.stage)
        assertEquals("dated when Android says the process ended", NOW - 60_000, logged.timeMillis)
        assertEquals("p-left", logged.profileId)
        assertEquals(
            mapOf(LogDetail.REASON to "low_memory", LogDetail.END_TIME to "exact", LogDetail.DURATION_MS to "540000"),
            logged.details,
        )
        // Found and kept, and nothing more: the next start connects nothing of its own accord (ADR-0013 §5).
        Thread.sleep(50)
        assertEquals(ConnectionStatus.Disconnected, state.value.status)
        assertTrue("no interface: ${log.all}", host.tuns.isEmpty())
        assertEquals("no check of the proxy, no engine: ${log.all}", 0, log.count("probe ") + log.count("start ") + log.count("establish"))
    }

    /** What applied to apps after the process ended is what ProxyFlow last read then, not what it reads later. */
    @Test
    fun anInterruptionKeepsTheKillSwitchAsItWasWhenTheProcessEnded() {
        val before = SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = NOW - 700_000)
        protection = InMemoryProtectionStore(
            killSwitch = before,
            connected = ConnectedMarker("p-left", NOW - 600_000, pid = 4242, bootCount = FakeHistory.BOOT),
        )
        history.ending = ProcessHistory.Ending(ExitReason.Killed, atMillis = NOW - 60_000)

        session.checkSystemKillSwitch()
        eventually("the interruption is kept") { runBlocking { protection.interruption.first() } != null }
        assertEquals(KillSwitchStatus.On, runBlocking { protection.interruption.first() }?.killSwitch)
        eventually("logged") { entries.any { it.event == LogEvent.SessionInterrupted } }
        assertEquals("yes", entries.single { it.event == LogEvent.SessionInterrupted }.details[LogDetail.BLOCKS_WITHOUT_VPN])

        // Android says otherwise now: the interruption still says what applied then.
        host.killSwitch = KillSwitchReading(alwaysOn = false, blocksWithoutVpn = false)
        connect()
        eventually("the new reading is kept") { stored()?.status == KillSwitchStatus.Off }
        assertEquals(KillSwitchStatus.On, runBlocking { protection.interruption.first() }?.killSwitch)
    }

    /** One write turns the marker into the interruption: a process that ends before it leaves the marker to the next. */
    @Test
    fun aMarkerStaysUntilItsInterruptionIsWritten() {
        val left = ConnectedMarker("p-left", NOW - 600_000, pid = 4242, bootCount = FakeHistory.BOOT)
        protection = InMemoryProtectionStore(connected = left)
        val base = protection
        started = newSession(
            object : ProtectionStore by base {
                override suspend fun interrupted(value: Interruption) = throw java.io.IOException("disk full")
            },
        )

        session.checkSystemKillSwitch()
        eventually("Android is asked") { history.asked.size == 1 }
        runBlocking { session.awaitIdle() }
        assertEquals("not written: the marker is still there for the next process", left, runBlocking { base.connectedMarker() })
    }

    /** Android's answer about the earlier process is asked off the event loop: a slow one never holds the session up. */
    @Test
    fun aSlowAnswerAboutTheLastProcessNeverHoldsUpTheSession() {
        protection = InMemoryProtectionStore(connected = ConnectedMarker("p-left", NOW - 600_000, pid = 4242, bootCount = null))
        val answer = CountDownLatch(1)
        history.gate = answer

        val profile = connect()
        assertEquals("connected while Android had not answered yet", ConnectionStatus.Connected, state.value.status)
        answer.countDown()
        eventually("the interruption is kept once Android answers") { runBlocking { protection.interruption.first() } != null }
        assertEquals(profile.id, state.value.profileId)
    }

    @Test
    fun anInterruptionAndroidDoesNotDateIsLoggedWhenFound() {
        protection = InMemoryProtectionStore(connected = ConnectedMarker("p", NOW - 10_000, pid = 1, bootCount = null))
        history.ending = ProcessHistory.Ending(ExitReason.Unknown, atMillis = null)

        session.checkSystemKillSwitch()
        eventually("logged") { entries.any { it.event == LogEvent.SessionInterrupted } }

        val logged = entries.single { it.event == LogEvent.SessionInterrupted }
        assertEquals(NOW, logged.timeMillis)
        assertEquals(mapOf(LogDetail.REASON to "unknown", LogDetail.END_TIME to "unknown"), logged.details)
    }

    @Test
    fun aStoppedConnectionLeavesNothingForTheNextProcess() {
        val reads = AtomicLong()
        val base = protection
        started = newSession(
            object : ProtectionStore by base {
                override suspend fun connectedMarker(): ConnectedMarker? = base.connectedMarker().also { reads.incrementAndGet() }
            },
        )
        session.checkSystemKillSwitch()
        eventually("the session looked for a marker") { reads.get() == 1L }
        runBlocking { session.awaitIdle() }
        assertTrue(entries.none { it.event == LogEvent.SessionInterrupted })
        assertNull(runBlocking { protection.interruption.first() })
        assertTrue(history.asked.isEmpty())
    }

    @Test
    fun theStoreAndTheLoopAreNotCountedAsTheSessionsJobs() {
        session.checkSystemKillSwitch()
        runBlocking { session.awaitIdle() }
        assertEquals(0, session.activeJobs())
    }

    // --- a SOCKS5 proxy leaving connection requests unanswered -------------------------------------------------------

    @Test
    fun unansweredRequestsToOneSiteNeverReconnect() {
        connect()
        engine.unanswered = UnansweredRequests(inARow = 5, sites = 1)
        unanswered()
        assertEquals(ConnectionStatus.Connected, state.value.status)
    }

    @Test
    fun twoUnansweredRequestsAreToleratedTheThirdToAnotherSiteReconnects() {
        policy = RetryPolicy(delaysMillis = listOf(60_000), jitter = 0.0)
        connect()
        engine.unanswered = UnansweredRequests(inARow = 2, sites = 2)
        unanswered()
        assertEquals(ConnectionStatus.Connected, state.value.status)

        engine.unanswered = UnansweredRequests(inARow = 3, sites = 2)
        unanswered()
        val reconnecting = await { it.status == ConnectionStatus.Reconnecting && it.reconnect != null }

        assertEquals(ReconnectReason.ProxyUnanswered, reconnecting.reconnect?.reason)
        assertEquals("TARGET_UNREACHABLE", reconnecting.reconnect?.lastError?.code)
        assertFalse("traffic is held while it reconnects", engine.running)
        val logged = entries.single { it.event == LogEvent.ReconnectStarted }
        assertEquals("proxy_unanswered", logged.details[LogDetail.REASON])
        assertEquals("3", logged.details[LogDetail.CONSECUTIVE])
        assertEquals("2", logged.details[LogDetail.SITES])
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private fun stored(): SystemKillSwitch? = runBlocking { protection.killSwitch.first() }

    private fun unanswered() {
        checkNotNull(
            engine.signals,
        )(EngineSignal.Unanswered(ConnectionError("TARGET_UNREACHABLE", "the proxy did not reach the site in time")))
        runBlocking { session.awaitIdle() }
    }

    private fun alwaysOnStart() = session.attach(host, alwaysOn = true, startId = host.started())

    private fun save(): ProxyProfile = runBlocking {
        profiles.repository.save(
            null,
            ProxyProfileInput(name = "Tokyo", protocol = ProxyProtocol.SOCKS5, server = "proxy.example.com", port = "1080"),
        )
    }

    private fun choose(profile: ProxyProfile): ProxyProfile = profile.also { runBlocking { profiles.repository.setActive(it.id) } }

    private fun connect(): ProxyProfile {
        val profile = save()
        session.connect(profile.id)
        await { it.status == ConnectionStatus.Connected }
        return profile
    }

    private fun await(predicate: (ConnectionState) -> Boolean): ConnectionState = runBlocking {
        val matched = withTimeout(TIMEOUT_MS) { state.first(predicate) }
        session.awaitIdle()
        matched
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting until $what; log ${log.all}; session ${sessionLog.all}" }
            Thread.sleep(5)
        }
    }

    /** This process is [PID] after boot [BOOT]; an earlier one ended as [ending] says. */
    private class FakeHistory : ProcessHistory {
        @Volatile var ending = ProcessHistory.Ending(ExitReason.Unknown, null)

        @Volatile var gate: CountDownLatch? = null
        val asked = CopyOnWriteArrayList<ConnectedMarker>()
        val markers = AtomicLong()

        override fun marker(profileId: String?, sinceMillis: Long): ConnectedMarker {
            markers.incrementAndGet()
            return ConnectedMarker(profileId, sinceMillis, PID, BOOT)
        }

        override fun ending(marker: ConnectedMarker): ProcessHistory.Ending {
            asked += marker
            gate?.await(5, java.util.concurrent.TimeUnit.SECONDS) // Android answering slowly right after a process died
            return ending
        }

        companion object {
            const val PID = 31337
            const val BOOT = 7
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val TIMEOUT_MS = 5_000L
    }
}
