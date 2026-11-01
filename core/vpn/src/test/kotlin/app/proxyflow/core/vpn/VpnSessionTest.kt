package app.proxyflow.core.vpn

import app.proxyflow.core.data.CredentialsRead
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.engine.EngineException
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.testing.TestProfiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Before
import org.junit.Test

/**
 * The connection state machine (ADR-0010) with a fake engine and a fake
 * service: every transition, and the rules that make it safe — Connected only
 * after the engine runs on a new interface, nothing changed on the phone when
 * the check fails, an interface once up never removed by a failure, every
 * engine, interface and service released on the way out.
 */
class VpnSessionTest {
    private val log = CallLog()
    private val engine = FakeEngine(log)
    private val host = FakeHost(log)
    private val profiles = TestProfiles(dispatcher = Dispatchers.Unconfined)
    private val state = RecordingState()
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val serviceStarts = AtomicInteger()

    @Volatile private var serviceStartAllowed = true

    @Volatile private var serviceAttaches = true
    private lateinit var session: VpnSession

    @Before
    fun setUp() {
        session = newSession()
    }

    private fun newSession(
        timing: VpnSession.Timing = TIMING,
        blocking: CoroutineDispatcher = Dispatchers.IO,
        repository: ProfileRepository = profiles.repository,
        sessionLog: (String) -> Unit = {},
    ) = VpnSession(
        profiles = repository,
        engine = engine,
        services = {
            serviceStarts.incrementAndGet()
            if (serviceStartAllowed && serviceAttaches) session.attach(host, alwaysOn = false)
            serviceStartAllowed
        },
        state = state,
        // A scope of its own, as in VpnRuntime: activeJobs() counts this session's coroutines only.
        scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])),
        blocking = blocking,
        clock = { NOW },
        timing = timing,
        log = sessionLog,
    )

    @After
    fun tearDown() {
        engine.gate?.countDown()
        scope.cancel()
        executor.shutdownNow()
        // One interface per connection, in every test: a second one would open a gap while Android moves the routes.
        assertEquals("interfaces established while another was open: ${log.all}", 0, host.overlaps.get())
    }

    @Test
    fun connectsOnlyAfterThePreflightCheckAndTheEngineRunningOnANewInterface() {
        val profile = save(signIn = true)

        session.connect(profile.id)
        val connected = awaitStatus(ConnectionStatus.Connected)

        assertEquals(profile.id, connected.profileId)
        assertEquals("Tokyo", connected.profileName)
        assertEquals(NOW, connected.connectedSinceMillis)
        assertNull(connected.error)
        assertEquals(
            listOf(
                "clear problem",
                "probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}",
                "establish fd=100",
                "start ${profile.id} fd=100 mtu=9000",
            ),
            log.all,
        )
        assertEquals("credentials go to the check and to the engine", listOf(true, true), engine.secrets)
        assertEquals("the check's socket is kept out of the VPN", listOf(FakeEngine.PROBE_SOCKET), host.protectedSockets)
        assertEquals("and guarded, so a cancel can cut it short", listOf(FakeEngine.PROBE_SOCKET), host.trackedSockets)
        assertEquals("the guard is let go after the check", 0, host.guardsOpen.get())
        assertEquals(0, host.aborts.get())
        assertEquals(TunConfig("198.18.0.1", "fd00:66::1", "198.18.0.2", mtu = 9000), host.lastConfig)
        assertEquals(
            "status before Connected: checking, then starting protection",
            listOf(ConnectStep.CheckingProxy, ConnectStep.StartingProtection),
            state.history.filter { it.status == ConnectionStatus.Connecting }.map { it.step }.distinct(),
        )
        assertEquals(ConnectionStatus.Connected, host.statuses.last().status)
    }

    @Test
    fun aProxyLevelFailureNeverEstablishesAndNeverShowsConnected() {
        val profile = save(signIn = true)
        engine.answer = PreflightAnswer(ok = false, code = "AUTH_FAILED", detail = "the proxy rejected the credentials")

        session.connect(profile.id)
        val ended = awaitError()

        assertEquals(ConnectionStatus.Disconnected, ended.status)
        assertEquals(ConnectionError("AUTH_FAILED", "the proxy rejected the credentials"), ended.error)
        assertTrue("no interface was created", host.tuns.isEmpty())
        assertNeverConnected()
        assertEquals("the service is let go", 1, log.count("host stop"))
    }

    /**
     * Only an opened tunnel passes. Providers answer an expired plan or an address they don't
     * allow with a refusal or a general failure, and a proxy without a way out to the Internet
     * can't reach the check's destination: each would show Connected while nothing works.
     */
    @Test
    fun everyAnswerButAnOpenedTunnelFailsTheCheck() {
        val profile = save()
        val codes = listOf("TARGET_UNREACHABLE", "TARGET_REFUSED", "TARGET_NOT_ALLOWED", "PROXY_GENERAL_FAILURE", "TARGET_UNSUPPORTED")
        for (code in codes) {
            engine.answer = PreflightAnswer(ok = false, code = code, detail = "proxy answered HTTP 502")
            val ended = connectUntilItEnds(profile.id)
            assertEquals(ConnectionStatus.Disconnected, ended.status)
            assertEquals(code, ended.error?.code)
        }
        assertTrue("no interface was created", host.tuns.isEmpty())
        assertNeverConnected()
    }

    @Test
    fun aRefusalOrAGeneralFailureFromTheProxyFailsTheCheck() {
        val profile = save()
        for (code in listOf("TARGET_NOT_ALLOWED", "PROXY_GENERAL_FAILURE", "TARGET_UNSUPPORTED")) {
            engine.answer = PreflightAnswer(ok = false, code = code, detail = "proxy answered HTTP 402")
            val ended = connectUntilItEnds(profile.id)
            assertEquals(ConnectionStatus.Disconnected, ended.status)
            assertEquals(code, ended.error?.code)
        }
        assertTrue("no interface was created", host.tuns.isEmpty())
        assertNeverConnected()
    }

    @Test
    fun anInternalPreflightFailureFailsTheCheck() {
        val profile = save()
        engine.answer = PreflightAnswer(ok = false, code = "INTERNAL", detail = "invalid configuration")

        session.connect(profile.id)

        assertEquals("INTERNAL", awaitError().error?.code)
        assertTrue(host.tuns.isEmpty())
    }

    @Test
    fun disconnectDuringTheCheckCancelsItWithoutEverConnecting() {
        val profile = save()
        engine.gate = CountDownLatch(1)

        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connecting)
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")
        session.disconnect()
        val cancelled = awaitStatus(ConnectionStatus.Disconnected)
        engine.gate?.countDown() // the check answers OK, too late
        runBlocking { session.awaitIdle() }
        Thread.sleep(100)
        runBlocking { session.awaitIdle() }

        assertNull("a cancel is not an error", cancelled.error)
        assertEquals(ConnectionStatus.Disconnected, state.value.status)
        assertTrue(host.tuns.isEmpty())
        assertNeverConnected()
    }

    @Test
    fun aSecondConnectForTheSameProfileIsIgnored() {
        val profile = save()
        engine.gate = CountDownLatch(1)

        session.connect(profile.id)
        session.connect(profile.id)
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")
        session.connect(profile.id)
        engine.gate?.countDown()
        awaitStatus(ConnectionStatus.Connected)
        session.connect(profile.id)
        runBlocking { session.awaitIdle() }

        assertEquals(1, log.count("probe"))
        assertEquals(1, host.tuns.size)
        assertEquals(1, log.count("start"))
        assertEquals("one service start for one connection", 1, serviceStarts.get())
    }

    @Test
    fun anotherProfileReplacesTheSessionWithoutAGapInTheTunnel() {
        val first = save(name = "Tokyo")
        val second = save(name = "Office", protocol = ProxyProtocol.HTTP)
        session.connect(first.id)
        awaitStatus(ConnectionStatus.Connected)
        engine.gate = CountDownLatch(1)

        session.connect(second.id)
        val switching = awaitStatus(ConnectionStatus.Reconnecting)
        awaitLog("probe ${second.id} ${VpnSession.PREFLIGHT_TARGET}")

        assertEquals(second.id, switching.profileId)
        assertFalse("existing flows end when switching", engine.running)
        assertFalse("the first interface holds traffic during the check", host.tuns[0].closed)
        host.tuns[0].queued = 3 // apps keep sending while traffic is held
        engine.gate?.countDown()
        val connected = awaitStatus(ConnectionStatus.Connected)

        assertEquals(second.id, connected.profileId)
        // One interface for the whole connection: a new one would let Android drop the old one's routes first.
        assertEquals("the switch creates no second interface", 1, log.count("establish"))
        assertFalse(host.tuns.single().closed)
        assertTrue(log.indexOf("start ${first.id} fd=100 mtu=9000") < log.indexOf("start ${second.id} fd=100 mtu=9000"))
        // What was held is dropped before the next engine runs, never delivered through the next proxy later.
        assertEquals(0, host.tuns.single().queued)
        assertTrue(log.indexOf("drain fd=100") in 0 until log.indexOf("start ${second.id} fd=100 mtu=9000"))
    }

    @Test
    fun aFailedSwitchKeepsTrafficBlockedUntilRestartOrStop() {
        val first = save(name = "Tokyo")
        val second = save(name = "Office")
        session.connect(first.id)
        awaitStatus(ConnectionStatus.Connected)
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")

        session.connect(second.id)
        val blocked = awaitStatus(ConnectionStatus.Blocked)

        assertEquals(second.id, blocked.profileId)
        assertEquals("PROXY_UNREACHABLE", blocked.error?.code)
        assertFalse("the interface stays: nothing goes around the proxy", host.tuns[0].closed)
        assertFalse(engine.running)
        assertEquals(ConnectionStatus.Blocked, host.statuses.last().status)

        engine.answer = PreflightAnswer(ok = true)
        session.restart()
        awaitStatus(ConnectionStatus.Connected)
        assertEquals("still the one interface", 1, host.tuns.size)
        assertFalse(host.tuns.single().closed)
        assertEquals("start ${second.id} fd=100 mtu=9000", log.all.last { it.startsWith("start") })
    }

    @Test
    fun anEngineFailureBlocksTrafficAndRestartRecovers() {
        val profile = save()
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        engine.failure!!(ConnectionError(ConnectionError.ENGINE_STOPPED, "reading the TUN interface failed"))
        val blocked = awaitStatus(ConnectionStatus.Blocked)

        assertEquals(ConnectionError(ConnectionError.ENGINE_STOPPED, "reading the TUN interface failed"), blocked.error)
        assertNull(blocked.connectedSinceMillis)
        assertEquals("the failed engine is stopped to release it", 1, log.count("stop"))
        assertFalse("the interface stays up, so apps' traffic is held", host.tuns[0].closed)
        assertEquals(ConnectionStatus.Blocked, host.statuses.last().status)

        host.tuns[0].queued = 5 // what apps sent while traffic was held
        session.restart()
        awaitStatus(ConnectionStatus.Connected)
        assertEquals("the engine restarts on the same interface", 1, host.tuns.size)
        assertFalse(host.tuns.single().closed)
        assertEquals(2, log.count("start ${profile.id} fd=100"))
        // Held traffic is dropped before the restarted engine reads the interface, never delivered later.
        assertEquals(0, host.tuns.single().queued)
        val secondStart = log.all.indexOfLast { it.startsWith("start ${profile.id} fd=100") }
        assertTrue(log.indexOf("drain fd=100") in 0 until secondStart)
    }

    @Test
    fun aFailureReportedByAnEarlierEngineIsIgnored() {
        val profile = save()
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)
        val firstEngine = engine.failure!!
        firstEngine(ConnectionError(ConnectionError.ENGINE_STOPPED))
        awaitStatus(ConnectionStatus.Blocked)
        session.restart()
        awaitStatus(ConnectionStatus.Connected)

        firstEngine(ConnectionError(ConnectionError.ENGINE_STOPPED))
        runBlocking { session.awaitIdle() }

        assertEquals(ConnectionStatus.Connected, state.value.status)
    }

    @Test
    fun revocationReleasesEverythingAndSaysSo() {
        val profile = save()
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        session.revoked(host)
        val revoked = awaitError()

        assertEquals(ConnectionStatus.Disconnected, revoked.status)
        assertEquals(ConnectionError.VPN_REVOKED, revoked.error?.code)
        assertFalse(engine.running)
        assertTrue(host.tuns.single().closed)
        assertEquals(1, log.count("host stop"))
        assertEquals(ConnectionError.VPN_REVOKED, host.problems.single().error?.code)
    }

    @Test
    fun disconnectStopsTheEngineBeforeClosingTheInterfaceThenTheService() {
        val profile = save()
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        session.disconnect()
        val ended = awaitStatus(ConnectionStatus.Disconnected)

        assertNull(ended.error)
        assertEquals(listOf("stop", "close fd=100", "host stop"), log.all.takeLast(3))
    }

    @Test
    fun withoutConsentNothingStarts() {
        val profile = save()
        host.consent = false

        session.connect(profile.id)

        assertEquals(ConnectionError.VPN_PERMISSION_DENIED, awaitError().error?.code)
        assertEquals(0, log.count("probe"))
        assertTrue(host.tuns.isEmpty())
    }

    @Test
    fun anInterfaceAndroidRefusesEndsWithPermissionDenied() {
        val profile = save()
        host.establishReturnsNull = true

        session.connect(profile.id)

        assertEquals(ConnectionError.VPN_PERMISSION_DENIED, awaitError().error?.code)
        assertEquals(0, log.count("start"))
        assertNeverConnected()
    }

    @Test
    fun anInterfaceThatCannotBeCreatedFailsTheAttempt() {
        val profile = save()
        host.establishError = IllegalStateException("no tun")

        session.connect(profile.id)

        val ended = awaitError()
        assertEquals(ConnectionStatus.Disconnected, ended.status)
        assertEquals(ConnectionError.INTERNAL, ended.error?.code)
        assertNeverConnected()
    }

    @Test
    fun anEngineThatCannotStartTakesTheNewInterfaceDownAgain() {
        val profile = save()
        engine.startError = EngineException("INTERNAL", "invalid TUN descriptor")

        session.connect(profile.id)

        assertEquals(ConnectionError("INTERNAL", "invalid TUN descriptor"), awaitError().error)
        assertTrue("never connected, so the phone goes back to its normal network", host.tuns.single().closed)
        assertNeverConnected()
    }

    @Test
    fun anyKindOfEngineStartFailureTakesTheNewInterfaceDownAgain() {
        val profile = save()
        // An Error too (a bug, say): the attempt fails instead of the event loop ending, which would kill the process.
        val failures = listOf(IllegalStateException("trust manager"), UnsatisfiedLinkError("libgojni.so"), AssertionError("bug"))
        for (failure in failures) {
            engine.startError = failure
            val ended = connectUntilItEnds(profile.id)
            assertEquals(ConnectionStatus.Disconnected, ended.status)
            assertEquals(ConnectionError.INTERNAL, ended.error?.code)
        }
        assertTrue("every new interface was closed again", host.tuns.all { it.closed })
        assertNeverConnected()

        // The session still works.
        engine.startError = null
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)
    }

    @Test
    fun anEngineThatCannotStartOnASwitchHoldsTrafficOnTheInterface() {
        val first = save(name = "Tokyo")
        val second = save(name = "Office")
        session.connect(first.id)
        awaitStatus(ConnectionStatus.Connected)
        engine.startError = IllegalStateException("trust manager")

        session.connect(second.id)
        val blocked = awaitStatus(ConnectionStatus.Blocked)

        assertEquals(ConnectionError.INTERNAL, blocked.error?.code)
        assertFalse("the interface holds traffic", host.tuns.single().closed)
        assertFalse(engine.running)
    }

    @Test
    fun unreadableCredentialsStopBeforeTheProxyIsContacted() {
        val profile = save(signIn = true)
        profiles.cipher.deleteKey("k1")

        session.connect(profile.id)

        assertEquals(ConnectionError.CREDENTIAL_UNREADABLE, awaitError().error?.code)
        assertEquals(0, log.count("probe"))
    }

    @Test
    fun aProfileWithoutSignInSendsNoCredentials() {
        val profile = save(signIn = false)

        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        assertEquals(listOf(false, false), engine.secrets)
    }

    @Test
    fun aDeletedProfileCannotBeConnected() {
        session.connect("missing")

        val ended = awaitError()
        assertEquals(ConnectionError.INTERNAL, ended.error?.code)
        assertEquals(0, log.count("probe"))
    }

    @Test
    fun waitsForTheServiceToStartBeforeTheCheck() {
        val profile = save()
        serviceAttaches = false

        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connecting)
        runBlocking { session.awaitIdle() }
        assertEquals(0, log.count("probe"))

        session.attach(host, alwaysOn = false)
        awaitStatus(ConnectionStatus.Connected)
        assertEquals(1, serviceStarts.get())
    }

    @Test
    fun aServiceThatNeverStartsEndsTheAttempt() {
        val profile = save()
        serviceAttaches = false

        session.connect(profile.id)

        val ended = awaitError()
        assertEquals(ConnectionError("INTERNAL", "the VPN service did not start"), ended.error)
        assertNeverConnected()
    }

    @Test
    fun aServiceAndroidRefusesToStartEndsTheAttempt() {
        val profile = save()
        serviceStartAllowed = false

        session.connect(profile.id)

        assertEquals(ConnectionError("INTERNAL", "Android did not start the VPN service"), awaitError().error)
    }

    @Test
    fun aRefusedForegroundServiceEndsTheAttemptBeforeTheProxyIsContacted() {
        val profile = save()
        host.foregroundAllowed = false

        session.connect(profile.id)

        assertEquals(ConnectionError.INTERNAL, awaitError().error?.code)
        assertEquals(0, log.count("probe"))
    }

    @Test
    fun alwaysOnConnectsOnlyTheProfileTheUserChose() {
        save(name = "Fallback")
        session.attach(host, alwaysOn = true)
        runBlocking { session.awaitIdle() }
        assertEquals("no explicit choice: the service stops", 1, log.count("host stop"))
        assertEquals(0, log.count("probe"))

        val chosen = save(name = "Chosen")
        runBlocking { profiles.repository.setActive(chosen.id) }
        session.attach(host, alwaysOn = true)

        assertEquals(chosen.id, awaitStatus(ConnectionStatus.Connected).profileId)
    }

    @Test
    fun deletingTheConnectionsProfileStopsItsEngineAndHoldsTraffic() {
        val profile = save()
        val other = save(name = "Other")
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        session.profileDeleted(other.id)
        runBlocking { session.awaitIdle() }
        assertEquals(ConnectionStatus.Connected, state.value.status)

        delete(profile.id)
        session.profileDeleted(profile.id)
        val blocked = awaitStatus(ConnectionStatus.Blocked)
        assertEquals(ConnectionError.PROFILE_DELETED, blocked.error?.code)
        assertFalse("its credentials are no longer used", engine.running)
        assertFalse("leaving the protected state takes the user's Stop", host.tuns.single().closed)

        session.restart()
        runBlocking { session.awaitIdle() }
        assertEquals("nothing to restart", ConnectionStatus.Blocked, state.value.status)
        assertEquals(ConnectionError.PROFILE_DELETED, state.value.error?.code)
        assertEquals(1, log.count("probe"))

        session.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)
        assertTrue(host.tuns.single().closed)
    }

    @Test
    fun deletingTheProfileOfAConnectionBeingSetUpCancelsIt() {
        val profile = save()
        engine.gate = CountDownLatch(1)
        session.connect(profile.id)
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")

        delete(profile.id)
        session.profileDeleted(profile.id)
        val ended = awaitStatus(ConnectionStatus.Disconnected)

        assertNull(ended.error)
        assertTrue(host.tuns.isEmpty())
        assertNeverConnected()
    }

    @Test
    fun connectingAMissingProfileLeavesTheRunningConnectionAlone() {
        val profile = save()
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        session.connect("missing")
        runBlocking { session.awaitIdle() }

        assertEquals(ConnectionStatus.Connected, state.value.status)
        assertEquals(profile.id, state.value.profileId)
        assertTrue(engine.running)
        assertEquals(0, log.count("stop"))
    }

    @Test
    fun aDestroyedServiceReleasesWhatIsLeft() {
        val profile = save()
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        session.detached(host)

        assertEquals(ConnectionError("INTERNAL", "the VPN service stopped"), awaitError().error)
        assertTrue(host.tuns.single().closed)
        assertFalse(engine.running)
    }

    @Test
    fun clearErrorForgetsAnEndedAttemptButNotABlockedOne() {
        val profile = save()
        engine.answer = PreflightAnswer(ok = false, code = "PROXY_UNREACHABLE")
        session.connect(profile.id)
        awaitError()

        session.clearError()
        runBlocking { session.awaitIdle() }
        assertNull(state.value.error)

        engine.answer = PreflightAnswer(ok = true)
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)
        engine.failure!!(ConnectionError(ConnectionError.ENGINE_STOPPED))
        awaitStatus(ConnectionStatus.Blocked)
        session.clearError()
        runBlocking { session.awaitIdle() }
        assertEquals(ConnectionError.ENGINE_STOPPED, state.value.error?.code)
    }

    @Test
    fun repeatedCyclesReleaseEveryInterfaceEngineAndService() {
        val profile = save(signIn = true)
        repeat(CYCLES) {
            session.connect(profile.id)
            awaitStatus(ConnectionStatus.Connected)
            session.disconnect()
            awaitStatus(ConnectionStatus.Disconnected)
        }

        assertEquals(CYCLES, host.tuns.size)
        assertTrue("every interface closed", host.tuns.all { it.closed })
        assertEquals(CYCLES, log.count("start"))
        assertEquals("every engine stopped", CYCLES, log.count("stop"))
        assertEquals("the service let go each time", CYCLES, log.count("host stop"))
        assertFalse(engine.running)
        eventually("no coroutine left behind") { session.activeJobs() == 0 }
    }

    @Test
    fun aCancelledCheckIsCutShortAndLeavesNoJobBehind() {
        val profile = save()
        val gate = CountDownLatch(1)
        engine.gate = gate
        host.onAbort = { gate.countDown() } // shutting the sockets down makes the engine's call return
        session.connect(profile.id)
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")
        assertEquals("the check runs", 1, session.activeJobs())

        session.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)

        eventually("the check has returned") { session.activeJobs() == 0 }
        assertEquals("its sockets were shut down", 1, host.aborts.get())
        assertEquals(0, host.guardsOpen.get())
    }

    /** activeJobs() counts a cancelled check whose engine call has not returned yet. */
    @Test
    fun aCheckThatHasNotReturnedStillCounts() {
        val profile = save()
        val gate = CountDownLatch(1)
        engine.gate = gate
        session.connect(profile.id)
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")

        session.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)
        Thread.sleep(100)
        assertEquals("cancelled, but still in the engine", 1, session.activeJobs())

        gate.countDown()
        eventually("the check has returned") { session.activeJobs() == 0 }
        runBlocking { session.awaitIdle() }
        assertEquals(ConnectionStatus.Disconnected, state.value.status)
        assertTrue(host.tuns.isEmpty())
    }

    @Test
    fun aCheckCancelledBeforeItRanNeverReadsTheCredentials() {
        val counting = CountingProfiles(profiles.repository)
        val held = CountDownLatch(1)
        val queue = Executors.newSingleThreadExecutor()
        queue.execute { held.await() } // the check's dispatcher is busy until released
        session = newSession(blocking = queue.asCoroutineDispatcher(), repository = counting)
        try {
            val profile = save(signIn = true)
            session.connect(profile.id)
            awaitStatus(ConnectionStatus.Connecting)
            session.disconnect()
            awaitStatus(ConnectionStatus.Disconnected)
            held.countDown()
            eventually("the cancelled check is gone") { session.activeJobs() == 0 }

            assertEquals("no credentials were decrypted", 0, counting.credentialReads.get())
            assertEquals(0, log.count("probe"))
            assertTrue(engine.secrets.isEmpty())
        } finally {
            held.countDown()
            queue.shutdownNow()
        }
    }

    @Test
    fun editingTheProfileDuringTheCheckChecksTheNewServerBeforeUsingItsPassword() {
        val profile = save(signIn = true)
        val gate = CountDownLatch(1)
        engine.gate = gate
        session.connect(profile.id)
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")

        // The user moves the profile to another server with another password while the check runs.
        runBlocking { profiles.repository.save(profile.id, input(signIn = true, server = "new.example.com", password = "new-secret")) }
        engine.gate = null
        gate.countDown()
        val connected = awaitStatus(ConnectionStatus.Connected)

        assertEquals("new.example.com", connected.profile?.host)
        assertEquals(
            "the new server is checked before the engine gets its password; the old server never gets it",
            listOf("probe proxy.example.com", "probe new.example.com", "start new.example.com"),
            engine.servers,
        )
        assertEquals(1, host.tuns.size)
    }

    @Test
    fun connectedWaitsUntilAndroidRoutesAppsIntoTheInterface() {
        val profile = save()
        host.routes = false

        session.connect(profile.id)
        awaitLog("start ${profile.id} fd=100 mtu=9000")
        Thread.sleep(200)
        assertEquals("the engine runs, but apps still use the normal network", ConnectionStatus.Connecting, state.value.status)
        assertEquals(ConnectStep.StartingProtection, state.value.step)

        host.routes = true
        val connected = awaitStatus(ConnectionStatus.Connected)
        assertEquals(profile.id, connected.profileId)
    }

    @Test
    fun aFreshInterfaceAndroidNeverRoutesIsTakenDownAgain() {
        val profile = save()
        host.routes = false
        session = newSession(timing = TIMING.copy(routingTimeoutMillis = 300))

        session.connect(profile.id)
        val ended = awaitError()

        assertEquals(ConnectionStatus.Disconnected, ended.status)
        assertEquals(ConnectionError("INTERNAL", "Android did not route apps into the VPN"), ended.error)
        assertTrue(host.tuns.single().closed)
        assertFalse(engine.running)
        assertNeverConnected()
        eventually("the routing wait has finished") { session.activeJobs() == 0 }
    }

    @Test
    fun aSwitchWhoseInterfaceAndroidNoLongerRoutesIsBlocked() {
        val first = save(name = "Tokyo")
        val second = save(name = "Office")
        session = newSession(timing = TIMING.copy(routingTimeoutMillis = 300))
        session.connect(first.id)
        awaitStatus(ConnectionStatus.Connected)
        host.routes = false

        session.connect(second.id)
        val blocked = awaitStatus(ConnectionStatus.Blocked)

        assertEquals(ConnectionError("INTERNAL", "Android did not route apps into the VPN"), blocked.error)
        assertFalse(engine.running)
        assertFalse("the interface stays: nothing is released without the user's Stop", host.tuns.single().closed)
    }

    @Test
    fun anEngineFailureBeforeAndroidRoutesEndsAFreshAttempt() {
        val profile = save()
        host.routes = false
        session.connect(profile.id)
        awaitLog("start ${profile.id} fd=100 mtu=9000")

        engine.failure!!(ConnectionError(ConnectionError.ENGINE_STOPPED))
        val ended = awaitError()

        assertEquals(ConnectionStatus.Disconnected, ended.status)
        assertEquals(ConnectionError.ENGINE_STOPPED, ended.error?.code)
        assertTrue(host.tuns.single().closed)
        assertNeverConnected()
    }

    @Test
    fun disconnectWhileWaitingForAndroidReleasesTheInterface() {
        val profile = save()
        host.routes = false
        session.connect(profile.id)
        awaitLog("start ${profile.id} fd=100 mtu=9000")

        session.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)

        assertTrue(host.tuns.single().closed)
        assertFalse(engine.running)
        // Cancelled, the routing wait finishes on its own dispatcher a moment later.
        eventually("the routing wait has finished") { session.activeJobs() == 0 }
        assertNeverConnected()
    }

    @Test
    fun routingIsRecognisedOnlyOnTheNewInterfacesNetwork() {
        val fresh = FakeTun(101, log)
        val unnamed = object : TunInterface {
            override val fd = 102
            override val name: String? = null

            override fun drain() = 0

            override fun close() = Unit
        }
        val old = VpnNetwork("netId 7", "tun0", ready = true)
        val routed = { tun: TunInterface, before: List<VpnNetwork>, now: List<VpnNetwork> -> VpnSession.routedInto(tun, before, now) }

        assertTrue("a new network on the new interface", routed(fresh, emptyList(), listOf(VpnNetwork("netId 8", "tun1", true))))
        assertFalse("not ready yet", routed(fresh, emptyList(), listOf(VpnNetwork("netId 8", "tun1", false))))
        assertFalse("another interface", routed(fresh, emptyList(), listOf(VpnNetwork("netId 8", "tun9", true))))
        val lingering = VpnNetwork("netId 7", "tun1", ready = true)
        assertFalse("an earlier connection's network on that name", routed(fresh, listOf(lingering), listOf(lingering)))
        assertTrue("an interface already in use", routed(fresh, emptyList(), listOf(lingering)))
        assertTrue("name unknown: a new network", routed(unnamed, listOf(old), listOf(old, VpnNetwork("netId 9", "tun2", true))))
        assertFalse("name unknown, nothing new", routed(unnamed, listOf(old), listOf(old)))
    }

    @Test
    fun anExceptionInTheCheckFailsTheAttemptNotTheApp() {
        val throwing = ThrowingProfiles(profiles.repository)
        session = newSession(repository = throwing)
        val profile = save()
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        // During a switch, the failure holds traffic like any other.
        throwing.failReads = true
        session.connect(save(name = "Office").id)
        val blocked = awaitStatus(ConnectionStatus.Blocked)
        // Caught in the check itself, not by the last resort for the session's jobs.
        assertEquals(ConnectionError("INTERNAL", "unexpected IllegalStateException in the proxy check"), blocked.error)
        assertFalse(host.tuns.single().closed)

        session.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)
        val ended = connectUntilItEnds(profile.id)
        assertEquals(ConnectionError("INTERNAL", "unexpected IllegalStateException in the proxy check"), ended.error)
        eventually("no job left") { session.activeJobs() == 0 }
    }

    @Test
    fun androidsNetworksThatCannotBeReadNeverCountAsRouted() {
        val profile = save()
        // The service throws when Android can't answer (an empty list would mean "no VPN network").
        host.failNetworkReads(IllegalStateException("system server gone"))

        // Unreadable before the interface exists: a network still being torn down could pass for the new one's.
        val ended = connectUntilItEnds(profile.id)

        assertEquals(ConnectionError("INTERNAL", "Android's networks could not be read"), ended.error)
        assertEquals("nothing was established", 0, log.count("establish"))
        assertNeverConnected()

        // Readable before the interface exists, unreadable after: still never Connected.
        host.failNetworkReads(IllegalStateException("gone"), after = 1)
        val later = connectUntilItEnds(profile.id)
        assertEquals(ConnectionError("INTERNAL", "Android did not route apps into the VPN"), later.error)
        assertTrue(host.tuns.all { it.closed })
        assertNeverConnected()
    }

    /** A look at Android's networks that fails now and then doesn't end the wait: the next look counts. */
    @Test
    fun aPassingFailureToReadAndroidsNetworksOnlyDelaysConnected() {
        val profile = save()
        host.failNetworkReads(IllegalStateException("system server restarting"), after = 1, times = 3)

        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        assertEquals(1, host.tuns.size)
    }

    /**
     * A cancelled check whose engine call later fails in a way nothing in it catches (an Error)
     * reaches the last resort, which must leave the connection that replaced it alone.
     */
    @Test
    fun aLateFailureOfAnEndedAttemptsJobLeavesTheNextConnectionAlone() {
        val sessionLog = CallLog()
        session = newSession(sessionLog = sessionLog::add)
        val first = save(name = "Tokyo")
        val silent = save(name = "Silent")
        val last = save(name = "Office")
        val release = CountDownLatch(1)
        engine.probeHook = { profile ->
            if (profile.id == silent.id) {
                release.await()
                throw AssertionError("late")
            }
        }
        session.connect(first.id)
        awaitStatus(ConnectionStatus.Connected)
        session.connect(silent.id)
        awaitLog("probe ${silent.id} ${VpnSession.PREFLIGHT_TARGET}")
        session.connect(last.id) // cancels the silent proxy's check, whose engine call keeps blocking
        awaitStatus(ConnectionStatus.Connected)

        release.countDown()
        eventually("the late failure reached the session") {
            sessionLog.all.any { it == "unexpected AssertionError in a session job of an attempt that is over" }
        }
        runBlocking { session.awaitIdle() }

        assertEquals(ConnectionStatus.Connected, state.value.status)
        assertEquals(last.id, state.value.profileId)
        assertNull(state.value.error)
        assertTrue(engine.running)
        assertFalse(host.tuns.single().closed)
        eventually("no job left") { session.activeJobs() == 0 }
    }

    /** A switch's re-check after an edit finds the profile deleted: the engine stays stopped and traffic stays held. */
    @Test
    fun aProfileDeletedWhileASwitchRechecksItsEditHoldsTraffic() {
        val first = save(name = "Tokyo")
        val second = save(name = "Office")
        val vanishing = VanishingDuringRecheck(profiles.repository, second.id)
        session = newSession(repository = vanishing)
        session.connect(first.id)
        awaitStatus(ConnectionStatus.Connected)

        session.connect(second.id)
        val blocked = awaitStatus(ConnectionStatus.Blocked)

        assertEquals(second.id, blocked.profileId)
        assertEquals(ConnectionError.PROFILE_DELETED, blocked.error?.code)
        assertFalse("the interface holds traffic", host.tuns.single().closed)
        assertFalse(engine.running)
        assertEquals("the deleted profile's engine never started", 0, log.count("start ${second.id}"))
    }

    /** The cancel reaches the check's sockets even when the dispatcher's only thread is busy in the engine call. */
    @Test
    fun aCancelledCheckIsCutShortEvenOnASingleThread() {
        val one = Executors.newSingleThreadExecutor()
        session = newSession(blocking = one.asCoroutineDispatcher())
        try {
            val profile = save()
            val gate = CountDownLatch(1)
            engine.gate = gate
            host.onAbort = { gate.countDown() }
            session.connect(profile.id)
            awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")

            session.disconnect()
            awaitStatus(ConnectionStatus.Disconnected)

            eventually("the check has returned") { session.activeJobs() == 0 }
            assertEquals(1, host.aborts.get())
        } finally {
            one.shutdownNow()
        }
    }

    /** A re-check after an edit finds the profile deleted: the attempt ends instead of waiting forever. */
    @Test
    fun aProfileDeletedWhileItsEditIsBeingCheckedEndsTheAttempt() {
        val vanishing = VanishingProfiles(profiles.repository)
        session = newSession(repository = vanishing)
        val profile = save()

        session.connect(profile.id)
        eventually("the attempt ended") { log.count("host stop") == 1 }
        runBlocking { session.awaitIdle() }

        assertEquals(ConnectionStatus.Disconnected, state.value.status)
        assertNull(state.value.error)
        assertTrue(host.tuns.isEmpty())
        eventually("no job left") { session.activeJobs() == 0 }
    }

    /** Without consent Android has already dropped the VPN: the app must not claim to hold traffic. */
    @Test
    fun consentGoneDuringASwitchEndsTheConnection() {
        val first = save(name = "Tokyo")
        val second = save(name = "Office")
        session.connect(first.id)
        awaitStatus(ConnectionStatus.Connected)
        host.consent = false

        session.connect(second.id)
        val ended = awaitError()

        assertEquals(ConnectionStatus.Disconnected, ended.status)
        assertEquals(ConnectionError.VPN_PERMISSION_DENIED, ended.error?.code)
        assertTrue(host.tuns.single().closed)
        assertFalse(engine.running)
    }

    private fun save(name: String = "Tokyo", signIn: Boolean = false, protocol: ProxyProtocol = ProxyProtocol.SOCKS5): ProxyProfile =
        runBlocking { profiles.repository.save(null, input(name, signIn, protocol)) }

    private fun input(
        name: String = "Tokyo",
        signIn: Boolean = false,
        protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
        server: String = "proxy.example.com",
        password: String = "secret",
    ) = ProxyProfileInput(
        name = name,
        protocol = protocol,
        server = server,
        port = "1080",
        requiresSignIn = signIn,
        username = if (signIn) "user" else "",
        password = if (signIn) password else "",
    )

    private fun delete(profileId: String) = runBlocking { profiles.repository.delete(profileId) }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting until $what" }
            Thread.sleep(5)
        }
    }

    private fun awaitStatus(status: ConnectionStatus): ConnectionState = await { it.status == status }

    private fun awaitError(): ConnectionState = await { it.error != null && it.status != ConnectionStatus.Connecting }

    /** Connects and returns the state this attempt ended in, never one an earlier attempt left behind. */
    private fun connectUntilItEnds(profileId: String): ConnectionState {
        val mark = state.history.size
        session.connect(profileId)
        var ended: ConnectionState? = null
        eventually("the attempt ended") {
            ended = state.history.drop(mark).lastOrNull { it.status == ConnectionStatus.Disconnected && it.error != null }
            ended != null
        }
        runBlocking { session.awaitIdle() }
        return checkNotNull(ended)
    }

    /** The first state matching [predicate], once the event that published it has been handled to its end. */
    private fun await(predicate: (ConnectionState) -> Boolean): ConnectionState = runBlocking {
        val matched = withTimeout(TIMEOUT_MS) { state.first(predicate) }
        session.awaitIdle()
        matched
    }

    private fun awaitLog(entry: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (log.indexOf(entry) < 0) {
            check(System.currentTimeMillis() < deadline) { "never logged: $entry; log ${log.all}" }
            Thread.sleep(5)
        }
    }

    private fun assertNeverConnected() {
        assertTrue("Connected was never shown: ${state.history}", state.history.none { it.status == ConnectionStatus.Connected })
    }

    /** The repository, failing credential reads on demand (storage or Keystore throwing something unexpected). */
    private class ThrowingProfiles(private val delegate: ProfileRepository) : ProfileRepository by delegate {
        @Volatile var failReads = false

        override suspend fun readCredentials(id: String, expected: ProxyProfile): CredentialsRead {
            check(!failReads) { "storage failed" }
            return delegate.readCredentials(id, expected)
        }
    }

    /** The profile is edited during the check (the next read says so), then deleted before it is looked up again. */
    private class VanishingProfiles(private val delegate: ProfileRepository) : ProfileRepository by delegate {
        private val lookups = AtomicInteger()
        private val reads = AtomicInteger()

        override suspend fun get(id: String): ProxyProfile? = if (lookups.incrementAndGet() == 1) delegate.get(id) else null

        override suspend fun readCredentials(id: String, expected: ProxyProfile): CredentialsRead =
            if (reads.incrementAndGet() == 2) CredentialsRead.ProfileChanged else delegate.readCredentials(id, expected)
    }

    /** [id] is edited during its first check (the read says so), then deleted before it is looked up again. */
    private class VanishingDuringRecheck(private val delegate: ProfileRepository, private val id: String) : ProfileRepository by delegate {
        private val lookups = AtomicInteger()
        private val reads = AtomicInteger()

        override suspend fun get(id: String): ProxyProfile? = if (id == this.id && lookups.incrementAndGet() > 1) null else delegate.get(id)

        override suspend fun readCredentials(id: String, expected: ProxyProfile): CredentialsRead =
            if (id == this.id && reads.incrementAndGet() == 1) CredentialsRead.ProfileChanged else delegate.readCredentials(id, expected)
    }

    /** The repository, counting how often credentials are decrypted for a connection. */
    private class CountingProfiles(private val delegate: ProfileRepository) : ProfileRepository by delegate {
        val credentialReads = AtomicInteger()

        override suspend fun readCredentials(id: String, expected: ProxyProfile): CredentialsRead {
            credentialReads.incrementAndGet()
            return delegate.readCredentials(id, expected)
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val TIMEOUT_MS = 5_000L
        const val CYCLES = 20
        val TIMING = VpnSession.Timing(
            preflightTimeoutMillis = 2_000,
            serviceStartTimeoutMillis = 1_000,
            routingTimeoutMillis = 2_000,
            routingPollMillis = 10,
        )
    }
}
