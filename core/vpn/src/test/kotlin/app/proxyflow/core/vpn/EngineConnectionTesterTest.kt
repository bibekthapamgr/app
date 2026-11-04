package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.testing.TestProfiles
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Test connection" on the engine (M9, ADR-0014): the pre-flight check, without the VPN; a saved password only with its own
 * username, to the server it was saved for; a result kept with the profile it tested while that stays as tested, and
 * never a pass the engine didn't give.
 */
class EngineConnectionTesterTest {
    private val log = CallLog()
    private val engine = FakeEngine(log)
    private val profiles = TestProfiles()
    private val repository = profiles.repository
    private val guard = RecordingGuard()
    private val tester = EngineConnectionTester(
        profiles = repository,
        engine = engine,
        clock = { NOW },
        guards = { guard.also { it.opened.incrementAndGet() } },
        dispatcher = Dispatchers.Default,
    )

    private val withSignIn = ProxyProfileInput(
        name = "Office",
        protocol = ProxyProtocol.SOCKS5,
        server = "proxy.example.com",
        port = "1080",
        requiresSignIn = true,
        username = "user",
        password = "secret",
    )

    @Test
    fun aSavedProfileIsTestedWithItsSavedSignInAndTheResultIsKept() = runBlocking {
        val saved = repository.save(null, withSignIn)
        engine.answer = PreflightAnswer(ok = true, latencyMillis = 182)

        assertEquals(ConnectionTestResult.Passed(182), tester.testSaved(saved.id))
        assertEquals(listOf("probe ${saved.id} ${VpnSession.PREFLIGHT_TARGET}"), log.all)
        assertEquals("the saved sign-in went with it", listOf(true), engine.secrets.toList())
        assertEquals(mapOf(saved.id to ProfileTest(NOW, code = null, latencyMillis = 182)), repository.lastTests.first())
        assertEquals("the test's sockets were let go", 1, guard.closes.get())
    }

    @Test
    fun aFailureAtTheProxyIsKeptButOneOfThisPhonesOwnIsNot() = runBlocking {
        val saved = repository.save(null, withSignIn)
        engine.answer = PreflightAnswer(ok = false, code = "AUTH_FAILED", detail = "proxy rejected the credentials")
        assertEquals(
            ConnectionTestResult.Failed(ConnectionError("AUTH_FAILED", "proxy rejected the credentials"), fromEngine = true),
            tester.testSaved(saved.id),
        )
        assertEquals(ProfileTest(NOW, code = "AUTH_FAILED", latencyMillis = null), repository.lastTests.first()[saved.id])

        // The engine failing to load, or failing unexpectedly, is this phone's failure: shown, never a pass, never kept.
        engine.probeHook = { throw LinkageError("no libpfengine.so") }
        val broken = tester.testSaved(saved.id)
        assertEquals(ConnectionError.INTERNAL, (broken as ConnectionTestResult.Failed).code)
        assertTrue("the engine was asked", broken.fromEngine)
        engine.probeHook = { throw IllegalStateException("unexpected") }
        assertEquals(ConnectionError.INTERNAL, (tester.testSaved(saved.id) as ConnectionTestResult.Failed).code)
        assertEquals("the proxy's answer stays", "AUTH_FAILED", repository.lastTests.first()[saved.id]?.code)
    }

    @Test
    fun theFormKeepsAResultOnlyForTheSavedProfileAsSaved() = runBlocking {
        val saved = repository.save(null, withSignIn)
        val asSaved = withSignIn.copy(password = "") // an empty password stands for the saved one

        assertEquals(ConnectionTestResult.Passed(1), tester.test(saved.id, asSaved.copy(name = "Renamed")))
        assertEquals("the name isn't what was tested", setOf(saved.id), repository.lastTests.first().keys)

        repository.save(saved.id, asSaved) // saving again keeps it: the same server, the same sign-in
        assertEquals(setOf(saved.id), repository.lastTests.first().keys)
        engine.answer = PreflightAnswer(ok = false, code = "AUTH_FAILED")
        // A password typed into the form is not the saved one: its result is shown, not kept (the form keeps it on Save).
        assertEquals("AUTH_FAILED", (tester.test(saved.id, withSignIn.copy(password = "typed")) as ConnectionTestResult.Failed).code)
        assertTrue("still the saved password's", repository.lastTests.first().getValue(saved.id).passed)
        // Another port is another proxy: not the saved profile's result either.
        tester.test(saved.id, withSignIn.copy(port = "1081", password = "typed"))
        assertTrue(repository.lastTests.first().getValue(saved.id).passed)
    }

    @Test
    fun aSavedPasswordGoesOnlyWithItsOwnUsernameToItsOwnServer() = runBlocking {
        val saved = repository.save(null, withSignIn)
        // Found here, before any proxy was asked: never worded or kept as the proxy's answer (ADR-0014 §2).
        val required = ConnectionTestResult.Failed(ConnectionError("AUTH_REQUIRED"), fromEngine = false)

        assertEquals(required, tester.test(saved.id, withSignIn.copy(username = "someone else", password = "")))
        assertEquals(required, tester.test(saved.id, withSignIn.copy(server = "other.example.com", password = "")))
        assertEquals("a new profile has no saved password", required, tester.test(null, withSignIn.copy(password = "")))
        assertEquals("nothing was sent to any proxy", emptyList<String>(), log.all)
        assertTrue("and nothing kept", repository.lastTests.first().isEmpty())
    }

    @Test
    fun aTypedSignInGoesToTheTestedProxyAndAProfileWithoutOneSendsNone() = runBlocking {
        assertEquals(ConnectionTestResult.Passed(1), tester.test(null, withSignIn))
        assertEquals(listOf("probe proxy.example.com"), engine.servers.toList())
        assertEquals(listOf(true), engine.secrets.toList())

        val open = repository.save(null, withSignIn.copy(requiresSignIn = false, username = "", password = ""))
        assertEquals(ConnectionTestResult.Passed(1), tester.testSaved(open.id))
        assertEquals(listOf(true, false), engine.secrets.toList())
        assertEquals("a profile tested as saved keeps it", setOf(open.id), repository.lastTests.first().keys)
    }

    @Test
    fun aProfileSavedAgainDuringItsTestDoesntGetThatResult() = runBlocking {
        val saved = repository.save(null, withSignIn)
        // Saved with another port while the test runs: the result describes settings that are gone.
        engine.probeHook = { runBlocking { repository.save(saved.id, withSignIn.copy(port = "1081")) } }
        assertEquals(ConnectionTestResult.Passed(1), tester.testSaved(saved.id))
        assertNull(repository.lastTests.first()[saved.id])
        assertNull("a profile that's gone tests nothing", tester.testSaved("p404"))
    }

    @Test
    fun aCancelledTestEndsAtOnceAndKeepsNothing() = runBlocking {
        val saved = repository.save(null, withSignIn)
        val gate = CountDownLatch(1)
        engine.gate = gate
        guard.onAbort = { gate.countDown() } // shutting the sockets down ends the engine's blocking call
        val running = async(start = CoroutineStart.UNDISPATCHED) { tester.testSaved(saved.id) }
        withTimeout(5_000) { while (log.all.isEmpty()) kotlinx.coroutines.delay(5) }
        running.cancel()
        withTimeout(5_000) { running.join() }

        assertEquals("the test's sockets were shut down", 1, guard.aborts.get())
        assertEquals(1, guard.closes.get())
        assertTrue(gate.await(0, TimeUnit.SECONDS))
        assertTrue("nothing kept for a test that didn't finish", repository.lastTests.first().isEmpty())
    }

    private class RecordingGuard : SocketGuard {
        val opened = AtomicInteger()
        val aborts = AtomicInteger()
        val closes = AtomicInteger()

        @Volatile var onAbort: () -> Unit = {}

        override fun track(fd: Int) = Unit

        override fun abort() {
            aborts.incrementAndGet()
            onAbort()
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private companion object {
        const val NOW = 1_793_000_000_000L
    }
}
