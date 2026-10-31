package app.proxyflow.core.engine

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.core.model.ProxyProtocol
import java.io.FileDescriptor
import java.io.FileInputStream
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real engine (libgojni.so from the AAR) on Android emulators: TCP
 * through mock SOCKS5 and HTTP CONNECT proxies to an echo server, sign-in
 * outcomes, timeouts, TLS to the proxy checked with the platform's trust
 * manager, the whole packet engine on a socketpair, the engine lifecycle,
 * and no credentials in any output or in logcat.
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val engine = ProxyEngine()
    private val closeables = mutableListOf<AutoCloseable>()
    private val events = CopyOnWriteArrayList<EngineEvent>()
    private lateinit var echo: EchoServer

    /** Trusts the device test CA, as the system store trusts a public CA. */
    private val testCaPlatform get() = AndroidEnginePlatform(trustManager = TestPki.trustManager(), onEvent = { events += it })

    /** The production platform: the system trust store only. */
    private val systemPlatform get() = AndroidEnginePlatform(onEvent = { events += it })

    @Before
    fun setUp() {
        echo = EchoServer().also { closeables += it }
    }

    @After
    fun tearDown() {
        engine.stop()
        closeables.reversed().forEach { runCatching { it.close() } }
    }

    private fun proxy(
        protocol: MockProxy.Protocol,
        username: String? = null,
        password: String? = null,
        tls: TestPki.Issued? = null,
        silent: Boolean = false,
    ) = MockProxy(
        protocol,
        username,
        password,
        hosts = mapOf("echo.test" to echo.address, "2001:db8::7" to echo.address),
        tls = tls?.let { TestPki.serverContext(it) },
        silent = silent,
    ).also { closeables += it }

    private fun profile(proxy: MockProxy, host: String = "127.0.0.1", tls: Boolean = false, tlsServerName: String? = null) = EngineProfile(
        protocol = if (proxy.protocol == MockProxy.Protocol.SOCKS5) ProxyProtocol.SOCKS5 else ProxyProtocol.HTTP,
        host = host,
        port = proxy.port,
        tls = tls,
        tlsServerName = tlsServerName,
        handshakeTimeoutMillis = 3000,
    )

    private fun secret(user: String, pass: String) = EngineSecret.of(user.toCharArray(), pass.toCharArray())

    /**
     * A datagram socketpair standing in for the TUN descriptor (each read is one
     * packet, as on a TUN). ParcelFileDescriptor only offers stream pairs publicly.
     */
    private fun datagramPair(): List<ParcelFileDescriptor> {
        val a = FileDescriptor()
        val b = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, a, b)
        try {
            return listOf(ParcelFileDescriptor.dup(a), ParcelFileDescriptor.dup(b)).onEach { closeables += it }
        } finally {
            Os.close(a)
            Os.close(b)
        }
    }

    @Test
    fun socks5AndHttpConnectCarryTcpToTheDestination() {
        for (protocol in MockProxy.Protocol.entries) {
            val proxy = proxy(protocol, "user", "pässword")
            val result = engine.probe(profile(proxy), secret("user", "pässword"), "echo.test:7", 10_000, systemPlatform)
            assertTrue("$protocol: $result", result.ok)
            val record = proxy.records.last()
            assertEquals("echo.test:7", record.target)
            assertEquals("user", record.username)
            assertEquals("pässword", record.password)
            if (protocol == MockProxy.Protocol.SOCKS5) assertEquals("the hostname goes to the proxy", "domain", record.addressType)
        }
    }

    @Test
    fun signInFailuresAreReportedAsProxyLevelCodes() {
        for (protocol in MockProxy.Protocol.entries) {
            val proxy = proxy(protocol, "user", "right")
            val wrong = engine.probe(profile(proxy), secret("user", "wrong"), "echo.test:7", 10_000, systemPlatform)
            assertEquals("$protocol wrong password", "AUTH_FAILED", wrong.code)
            assertTrue(wrong.proxyLevel)
            val none = engine.probe(profile(proxy), null, "echo.test:7", 10_000, systemPlatform)
            assertEquals("$protocol no sign-in", "AUTH_REQUIRED", none.code)
            val unknown = engine.probe(profile(proxy), secret("user", "right"), "nowhere.test:7", 10_000, systemPlatform)
            assertEquals("$protocol unknown destination", "TARGET_UNREACHABLE", unknown.code)
            assertFalse(unknown.proxyLevel)
        }
    }

    @Test
    fun unreachableAndSilentProxiesFailClosedInTime() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val unreachable = engine.probe(
            EngineProfile(ProxyProtocol.SOCKS5, "127.0.0.1", closedPort),
            null,
            "echo.test:7",
            10_000,
            systemPlatform,
        )
        assertEquals("PROXY_UNREACHABLE", unreachable.code)

        val silent = proxy(MockProxy.Protocol.SOCKS5, silent = true)
        val started = System.nanoTime()
        val timedOut = engine.probe(profile(silent).copy(handshakeTimeoutMillis = 500), null, "echo.test:7", 10_000, systemPlatform)
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals("HANDSHAKE_TIMEOUT", timedOut.code)
        assertTrue("took $tookMillis ms", tookMillis in 400..3000)
    }

    @Test
    fun tlsToTheProxyIsVerifiedByThePlatformTrustManager() {
        val proxy = proxy(MockProxy.Protocol.SOCKS5, tls = TestPki.valid)
        val trusted = engine.probe(profile(proxy, tls = true, tlsServerName = "proxy.test"), null, "echo.test:7", 10_000, testCaPlatform)
        assertTrue("trusted chain and matching name: $trusted", trusted.ok)
        val byIp = engine.probe(profile(proxy, tls = true), null, "echo.test:7", 10_000, testCaPlatform)
        assertTrue("certificate with the proxy's IP address: $byIp", byIp.ok)

        val untrusted = engine.probe(profile(proxy, tls = true, tlsServerName = "proxy.test"), null, "echo.test:7", 10_000, systemPlatform)
        assertEquals("a CA the phone does not trust", "TLS_UNTRUSTED", untrusted.code)
        val wrongName = engine.probe(profile(proxy, tls = true, tlsServerName = "wrong.test"), null, "echo.test:7", 10_000, testCaPlatform)
        assertEquals("server name not in the certificate", "TLS_HOSTNAME", wrongName.code)

        val expired = proxy(MockProxy.Protocol.HTTP, tls = TestPki.expired)
        val expiredResult = engine.probe(
            profile(expired, tls = true, tlsServerName = "proxy.test"),
            null,
            "echo.test:7",
            10_000,
            testCaPlatform,
        )
        assertEquals("expired certificate", "TLS_EXPIRED", expiredResult.code)

        val other = proxy(MockProxy.Protocol.HTTP, tls = TestPki.otherName)
        val otherResult = engine.probe(
            profile(other, tls = true, tlsServerName = "proxy.test"),
            null,
            "echo.test:7",
            10_000,
            testCaPlatform,
        )
        assertEquals("certificate for another name", "TLS_HOSTNAME", otherResult.code)

        val plain = proxy(MockProxy.Protocol.SOCKS5)
        val notTls = engine.probe(profile(plain, tls = true, tlsServerName = "proxy.test"), null, "echo.test:7", 10_000, testCaPlatform)
        assertEquals("plaintext proxy with TLS on", "TLS_HANDSHAKE", notTls.code)
        // The TLS proxy saw a SOCKS5 request only on the two verified connections.
        assertEquals(2, proxy.records.size)
    }

    @Test
    fun theWholePacketEngineCarriesTrafficThroughTheProxy() {
        val payload = "hello through gVisor, the adapter and the proxy".toByteArray()
        // A first run starts whatever the Go runtime starts once per process, so the
        // goroutine counts below compare like with like.
        engine.selfTest(profile(proxy(MockProxy.Protocol.SOCKS5)), null, "echo.test:7", payload, payload.size, 20_000, systemPlatform)
        for (protocol in MockProxy.Protocol.entries) {
            val proxy = proxy(protocol, "user", "pw")
            val result = engine.selfTest(profile(proxy), secret("user", "pw"), "echo.test:7", payload, payload.size, 20_000, systemPlatform)
            assertTrue("$protocol: $result", result.ok)
            assertTrue(result.connected)
            assertEquals(payload.size, result.bytesReceived)
            assertEquals(String(payload), String(Base64.getDecoder().decode(result.replyBase64)))
            assertTrue(
                "fake DNS answer ${result.dnsAnswer}",
                result.dnsAnswer!!.startsWith("198.18.") || result.dnsAnswer!!.startsWith("198.19."),
            )
            assertTrue("UDP must be refused, not forwarded", result.udpRejected)
            assertEquals(1, result.tcpOpened)
            assertTrue(
                "goroutines ${result.goroutinesBefore} -> ${result.goroutinesAfter}",
                result.goroutinesAfter <= result.goroutinesBefore,
            )
            assertEquals("echo.test:7", proxy.records.last().target)
        }
        val v6 = proxy(MockProxy.Protocol.SOCKS5)
        val result = engine.selfTest(profile(v6), null, "[2001:db8::7]:7", payload, payload.size, 20_000, systemPlatform)
        assertTrue("IPv6 destination: $result", result.ok)
        assertEquals("ipv6", v6.records.last().addressType)

        val refusing = proxy(MockProxy.Protocol.HTTP, "user", "pw")
        val failed = engine.selfTest(
            profile(refusing),
            secret("user", "wrong"),
            "echo.test:7",
            payload,
            payload.size,
            20_000,
            systemPlatform,
        )
        assertFalse(failed.ok)
        assertFalse("the app's connection is refused, never sent around the proxy", failed.connected)
        assertEquals("AUTH_FAILED", failed.code)
    }

    @Test
    fun theEngineStartsAndStopsOnADescriptorItDoesNotOwn() {
        val pair = datagramPair()
        val proxy = proxy(MockProxy.Protocol.SOCKS5)
        engine.start(pair[0].fd, profile(proxy), null, systemPlatform)
        assertEquals(EngineState.Running, engine.status().state)
        assertEquals(EngineState.Running, engine.stats().state)
        assertEquals(emptyList<EngineFlow>(), engine.flows())
        assertEquals(0, engine.resetFlows())
        val again = assertThrows(EngineException::class.java) { engine.start(pair[0].fd, profile(proxy), null, systemPlatform) }
        assertEquals("INTERNAL", again.code)
        engine.stop()
        engine.stop()
        assertEquals(EngineState.Stopped, engine.status().state)
        assertTrue("the descriptor stays open for its owner", pair[0].fileDescriptor.valid())
        Os.fstat(pair[0].fileDescriptor) // throws if the engine had closed it
        engine.start(pair[0].fd, profile(proxy), null, systemPlatform) // a new engine on the same descriptor
        engine.stop()
        assertTrue(events.any { it.type == "engine_started" } && events.any { it.type == "engine_stopped" })

        val plan = engine.networkPlan()
        assertEquals("198.18.0.2", plan.dnsServer4)
        assertTrue(engine.version().contains("gvisor"))
        val bad = assertThrows(EngineException::class.java) {
            engine.start(pair[0].fd, EngineProfile(ProxyProtocol.SOCKS5, "bad host", 1), null, systemPlatform)
        }
        assertEquals("INTERNAL", bad.code)
    }

    @Test
    fun credentialsNeverAppearInOutputsEventsOrLogcat() {
        val user = "Kx7devuser"
        val pass = "Pq4devsecret"
        val outputs = mutableListOf<String>()
        for (protocol in MockProxy.Protocol.entries) {
            val good = proxy(protocol, user, pass)
            val bad = proxy(protocol, "someone", "else")
            for (p in listOf(good, bad)) {
                val logging = profile(p).copy(logDestinations = true)
                outputs += engine.probe(logging, secret(user, pass), "echo.test:7", 10_000, systemPlatform).toString()
                outputs +=
                    engine.selfTest(logging, secret(user, pass), "echo.test:7", "x".toByteArray(), 1, 20_000, systemPlatform).toString()
            }
            val pair = datagramPair()
            engine.start(pair[0].fd, profile(good), secret(user, pass), systemPlatform)
            outputs += engine.status().toString() + engine.stats().toString() + engine.flows().toString()
            outputs +=
                runCatching { engine.start(pair[0].fd, profile(good), secret(user, pass), systemPlatform) }.exceptionOrNull().toString()
            engine.stop()
        }
        outputs += events.map { it.toString() }
        assertTrue("events were produced", events.size > 10)
        val logcat = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("logcat -d -v raw").let { fd ->
            FileInputStream(fd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }.also { fd.close() }
        }
        assertNotNull(logcat)
        val needles = listOf(
            user,
            pass,
            Base64.getEncoder().encodeToString("$user:$pass".toByteArray()),
            user.toByteArray().joinToString("") { "%02x".format(it) },
            pass.toByteArray().joinToString("") { "%02x".format(it) },
        )
        for (needle in needles) {
            outputs.forEach { assertFalse("a credential appears in engine output: $it", it.contains(needle)) }
            assertFalse("a credential appears in logcat", logcat.contains(needle))
        }
    }

    @Test
    fun theProxyHostIsResolvedByTheAppNotTheEngine() {
        val proxy = proxy(MockProxy.Protocol.SOCKS5)
        val asked = CopyOnWriteArrayList<String>()
        val platform = object : app.proxyflow.engine.go.pfengine.Platform by systemPlatform {
            override fun resolveProxyHost(host: String): String {
                asked += host
                return "127.0.0.1"
            }
        }
        val result = engine.probe(profile(proxy, host = "proxy.example"), null, "echo.test:7", 10_000, platform)
        assertTrue("$result", result.ok)
        assertEquals(listOf("proxy.example"), asked)
        val unresolved = engine.probe(profile(proxy, host = "unknown.invalid"), null, "echo.test:7", 10_000, systemPlatform)
        assertEquals("PROXY_DNS_FAILED", unresolved.code)
    }
}
