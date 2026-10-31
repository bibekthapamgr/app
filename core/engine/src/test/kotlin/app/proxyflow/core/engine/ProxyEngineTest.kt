package app.proxyflow.core.engine

import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.TlsMode
import app.proxyflow.engine.go.pfengine.Platform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyEngineTest {
    private val platform = object : Platform {
        override fun protect(fd: Long) = true
        override fun resolveProxyHost(host: String) = "192.0.2.1"
        override fun verifyChain(derChain: ByteArray, host: String) = "OK"
        override fun onEvent(json: String) = Unit
    }

    private val profile = EngineProfile(protocol = ProxyProtocol.SOCKS5, host = "proxy.example", port = 1080)

    @Test
    fun `config JSON has exactly the engine's keys and no credentials`() {
        val full = EngineProfile(
            protocol = ProxyProtocol.HTTP, host = "2001:db8::1", port = 3128, tls = true, tlsServerName = "proxy.example",
            mtu = 9000, logDestinations = true, dialTimeoutMillis = 1, handshakeTimeoutMillis = 2, idleTimeoutMillis = 3,
            maxConnections = 4,
        )
        assertEquals(
            """{"protocol":"HTTP","host":"2001:db8::1","port":3128,"tls":true,"tls_server_name":"proxy.example","mtu":9000,""" +
                """"log_destinations":true,"dial_timeout_ms":1,"handshake_timeout_ms":2,"idle_timeout_ms":3,"max_conns":4}""",
            EngineJson.config(full),
        )
        assertEquals("""{"protocol":"SOCKS5","host":"proxy.example","port":1080}""", EngineJson.config(profile))
        assertEquals(
            """{"protocol":"SOCKS5","host":"h","port":1,"tls":true}""",
            EngineJson.config(EngineProfile(ProxyProtocol.SOCKS5, "h", 1, tls = true, tlsServerName = " ")),
        )
    }

    @Test
    fun `profiles map to the engine's view`() {
        val p = ProxyProfile(
            id = "1",
            name = "Office",
            protocol = ProxyProtocol.HTTP,
            host = "proxy.example",
            port = 8443,
            tlsMode = TlsMode.Tls,
            tlsServerName = "tls.example",
            requiresSignIn = true,
        )
        assertEquals(
            EngineProfile(ProxyProtocol.HTTP, "proxy.example", 8443, tls = true, tlsServerName = "tls.example"),
            p.toEngineProfile(),
        )
    }

    @Test
    fun `start hands the secret over once and zeroes it whatever happens`() {
        val native = FakeNative()
        val engine = ProxyEngine(native)
        engine.start(42, profile, EngineSecret.of("user".toCharArray(), "pw".toCharArray()), platform)
        assertEquals(42L, native.startedFd)
        assertEquals("""{"protocol":"SOCKS5","host":"proxy.example","port":1080}""", native.lastConfig)
        assertArrayEquals(byteArrayOf(1, 0, 4) + "user".toByteArray() + byteArrayOf(0, 2) + "pw".toByteArray(), native.secretSeen)
        assertTrue("the bytes given to the engine are zeroed afterwards", native.secretPassed!!.all { it == 0.toByte() })

        native.failStart = Exception("INTERNAL: the engine is already running")
        val e = assertThrows(EngineException::class.java) {
            engine.start(42, profile, EngineSecret.of("user".toCharArray(), "pw".toCharArray()), platform)
        }
        assertEquals("INTERNAL", e.code)
        assertEquals("the engine is already running", e.detail)
        assertTrue(native.secretPassed!!.all { it == 0.toByte() })

        native.failStart = null
        engine.start(42, profile, null, platform)
        assertEquals(0, native.secretSeen!!.size)
    }

    @Test
    fun `status, stats and flows are decoded, unknown fields ignored`() {
        val native = FakeNative()
        native.statusJson = """{"state":"failed","started_at":"2026-10-31T10:00:00Z","last_error":{"code":"ENGINE_STOPPED",""" +
            """"detail":"the TUN interface was closed","time":"2026-10-31T10:05:00Z"},"consecutive_proxy_failures":2,"flows":3,""" +
            """"tcp_active":1,"something_new":true}"""
        native.statsJson = """{"state":"running","tcp_opened":5,"bytes_up":10,"bytes_down":20,"failures":{"AUTH_FAILED":2},""" +
            """"last_proxy_error":{"code":"AUTH_FAILED","detail":"x","time":"t"},"goroutines":12}"""
        native.flowsJson =
            """[{"id":1,"kind":"tcp","state":"open","dst_kind":"hostname","port":443,"age_ms":5,"bytes_up":1,"bytes_down":2}]"""
        val engine = ProxyEngine(native)

        val status = engine.status()
        assertEquals(EngineState.Failed, status.state)
        assertEquals("ENGINE_STOPPED", status.lastError?.code)
        assertEquals(2, status.consecutiveProxyFailures)
        val stats = engine.stats()
        assertEquals(EngineState.Running, stats.state)
        assertEquals(mapOf("AUTH_FAILED" to 2L), stats.failures)
        assertEquals("AUTH_FAILED", stats.lastProxyError?.code)
        val flows = engine.flows()
        assertEquals(1, flows.size)
        assertEquals(443, flows[0].port)
        assertNull("destinations are absent unless recorded", flows[0].destination)
        assertEquals(EngineState.Unknown, EngineState.of("weird"))
    }

    @Test
    fun `probe and self-test results are decoded and their secrets zeroed`() {
        val native = FakeNative()
        val engine = ProxyEngine(native)
        native.probeJson = """{"ok":false,"code":"AUTH_FAILED","detail":"proxy rejected the username or password","proxy_level":true}"""
        val probe = engine.probe(profile, EngineSecret.of("u".toCharArray(), "p".toCharArray()), "example.com:443", 1000, platform)
        assertFalse(probe.ok)
        assertEquals("AUTH_FAILED", probe.code)
        assertTrue(probe.proxyLevel)
        assertEquals("example.com:443", native.lastTarget)
        assertTrue(native.secretPassed!!.all { it == 0.toByte() })

        native.selfTestJson = """{"ok":true,"latency_ms":12,"dns_answer":"198.18.1.0","connected":true,"bytes_sent":5,""" +
            """"bytes_received":5,"reply_b64":"aGVsbG8=","udp_rejected":true,"tcp_opened":1,"goroutines_before":7,"goroutines_after":8,""" +
            """"goroutines_left":1,"goroutines_left_in":["gvisor.dev/gvisor/pkg/tcpip/link/fdbased.(*endpoint).dispatchLoop"]}"""
        val self = engine.selfTest(profile, null, "echo.test:7", "hello".toByteArray(), 5, 1000, platform)
        assertTrue(self.ok && self.connected && self.udpRejected)
        assertEquals(1, self.goroutinesLeft)
        assertEquals(listOf("gvisor.dev/gvisor/pkg/tcpip/link/fdbased.(*endpoint).dispatchLoop"), self.goroutinesLeftIn)
        assertEquals("198.18.1.0", self.dnsAnswer)
        assertEquals(5L, native.lastExpect)
    }

    @Test
    fun `events are decoded and malformed ones dropped`() {
        val event = EngineJson.event("""{"time":"t","type":"conn_fail","conn_id":3,"proto":"tcp","port":443,"code":"TARGET_REFUSED"}""")
        assertEquals("conn_fail", event?.type)
        assertEquals("TARGET_REFUSED", event?.code)
        assertNull(EngineJson.event("not json"))
    }

    @Test
    fun `engine error messages become catalogue codes`() {
        assertEquals("AUTH_FAILED", EngineException.from(Exception("AUTH_FAILED: rejected")).code)
        val odd = EngineException.from(IllegalStateException("go: something odd"))
        assertEquals("INTERNAL", odd.code)
        assertEquals("go: something odd", odd.detail)
        assertEquals("INTERNAL", EngineException.from(RuntimeException()).code)
    }

    private class FakeNative : EngineNative {
        var startedFd = -1L
        var lastConfig: String? = null
        var secretSeen: ByteArray? = null
        var secretPassed: ByteArray? = null
        var failStart: Exception? = null
        var statusJson = "{}"
        var statsJson = "{}"
        var flowsJson = "[]"
        var probeJson = """{"ok":true}"""
        var selfTestJson = """{"ok":true}"""
        var lastTarget: String? = null
        var lastExpect = 0L

        override fun start(tunFd: Long, configJson: String, secret: ByteArray, platform: Platform) {
            startedFd = tunFd
            lastConfig = configJson
            secretSeen = secret.copyOf()
            secretPassed = secret
            failStart?.let { throw it }
        }

        override fun stop() = Unit
        override fun status() = statusJson
        override fun stats() = statsJson
        override fun flows() = flowsJson
        override fun resetFlows() = 0L
        override fun probe(configJson: String, secret: ByteArray, target: String, timeoutMs: Long, platform: Platform): String {
            secretPassed = secret
            lastTarget = target
            return probeJson
        }

        override fun selfTest(
            configJson: String,
            secret: ByteArray,
            target: String,
            payload: ByteArray,
            expect: Long,
            timeoutMs: Long,
            platform: Platform,
        ): String {
            lastExpect = expect
            return selfTestJson
        }

        override fun networkPlan() = """{"tun_address4":"198.18.0.1","tun_address6":"fd00:66::1","dns_server4":"198.18.0.2",""" +
            """"fake_ip_prefix":"198.18.0.0/15","default_mtu":1500}"""
        override fun version() = "test"
    }
}
