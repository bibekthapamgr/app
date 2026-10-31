package app.proxyflow.core.vpn

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        session = VpnSession(
            profiles = profiles.repository,
            engine = engine,
            services = {
                serviceStarts.incrementAndGet()
                if (serviceStartAllowed && serviceAttaches) session.attach(host, alwaysOn = false)
                serviceStartAllowed
            },
            state = state,
            scope = scope,
            blocking = Dispatchers.IO,
            clock = { NOW },
            timing = VpnSession.Timing(preflightTimeoutMillis = 2_000, serviceStartTimeoutMillis = 1_000),
        )
    }

    @After
    fun tearDown() {
        engine.gate?.countDown()
        scope.cancel()
        executor.shutdownNow()
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

    @Test
    fun aTargetLevelAnswerProvesTheProxyIsReachable() {
        val profile = save()
        for (code in PreflightAnswer.TARGET_LEVEL) {
            engine.answer = PreflightAnswer(ok = false, code = code)
            session.connect(profile.id)
            awaitStatus(ConnectionStatus.Connected)
            session.disconnect()
            awaitStatus(ConnectionStatus.Disconnected)
        }
        assertEquals(PreflightAnswer.TARGET_LEVEL.size, host.tuns.size)
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
        engine.gate?.countDown()
        val connected = awaitStatus(ConnectionStatus.Connected)

        assertEquals(second.id, connected.profileId)
        assertTrue(host.tuns[0].closed)
        assertFalse(host.tuns[1].closed)
        // The new interface exists before the old one is closed, and the engine runs on it first.
        assertTrue(log.indexOf("establish fd=101") < log.indexOf("start ${second.id} fd=101 mtu=9000"))
        assertTrue(log.indexOf("start ${second.id} fd=101 mtu=9000") < log.indexOf("close fd=100"))
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
        assertTrue(host.tuns[0].closed)
        assertFalse(host.tuns[1].closed)
        assertEquals(1, host.tuns.count { !it.closed })
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

        session.restart()
        awaitStatus(ConnectionStatus.Connected)
        assertTrue(host.tuns[0].closed)
        assertFalse(host.tuns[1].closed)
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
    fun deletingTheConnectedProfileDisconnects() {
        val profile = save()
        val other = save(name = "Other")
        session.connect(profile.id)
        awaitStatus(ConnectionStatus.Connected)

        session.profileDeleted(other.id)
        runBlocking { session.awaitIdle() }
        assertEquals(ConnectionStatus.Connected, state.value.status)

        session.profileDeleted(profile.id)
        awaitStatus(ConnectionStatus.Disconnected)
        assertTrue(host.tuns.single().closed)
        assertFalse(engine.running)
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
        assertEquals("no coroutine left behind", 0, session.activeJobs())
    }

    @Test
    fun aCancelledCheckLeavesNoJobBehind() {
        val profile = save()
        engine.gate = CountDownLatch(1)
        session.connect(profile.id)
        awaitLog("probe ${profile.id} ${VpnSession.PREFLIGHT_TARGET}")
        assertEquals("the check runs", 1, session.activeJobs())

        session.disconnect()
        awaitStatus(ConnectionStatus.Disconnected)

        assertEquals(0, session.activeJobs())
    }

    private fun save(name: String = "Tokyo", signIn: Boolean = false, protocol: ProxyProtocol = ProxyProtocol.SOCKS5): ProxyProfile =
        runBlocking {
            profiles.repository.save(
                null,
                ProxyProfileInput(
                    name = name,
                    protocol = protocol,
                    server = "proxy.example.com",
                    port = "1080",
                    requiresSignIn = signIn,
                    username = if (signIn) "user" else "",
                    password = if (signIn) "secret" else "",
                ),
            )
        }

    private fun awaitStatus(status: ConnectionStatus): ConnectionState = await { it.status == status }

    private fun awaitError(): ConnectionState = await { it.error != null && it.status != ConnectionStatus.Connecting }

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

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val TIMEOUT_MS = 5_000L
        const val CYCLES = 20
    }
}
