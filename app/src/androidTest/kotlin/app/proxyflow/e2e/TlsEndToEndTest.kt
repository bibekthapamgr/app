package app.proxyflow.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M6 end to end: TLS to the proxy, checked by the platform's trust manager, and HTTPS to a site,
 * which stays end to end (ADR-0005, ADR-0011). The lab's proxies sit behind stunnel with
 * certificates from the run's own CA, which a test trusts through [TestTrust] (the platform's
 * trust manager with that CA as its anchor); one test leaves it out and shows that the app as
 * shipped trusts no private CA. A certificate that is not trusted, names another host, has
 * expired, or a proxy that does not speak TLS never connects, and nothing then goes to the proxy
 * unencrypted (the packet captures show every connection to a TLS port opening with a TLS record);
 * a certificate that changes while connected is refused and blocks traffic at once (M7, ADR-0012).
 */
@RunWith(AndroidJUnit4::class)
class TlsEndToEndTest {
    private val app = App()
    private lateinit var env: E2eConfig
    private lateinit var lab: LabConfig
    private lateinit var gate: Gate
    private lateinit var networks: VpnNetworks
    private lateinit var history: StateHistory
    private var establishedBefore = 0

    @Before
    fun setUp() {
        env = E2eConfig.load()
        lab = LabConfig.load()
        gate = Gate(lab)
        gate.forward(env.socksPort)
        TestTrust.labCa = lab.ca()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        networks = VpnNetworks(app.context)
        history = StateHistory(app.controller)
        establishedBefore = ProxyFlowVpnService.establishedInterfaces.get()
    }

    @After
    fun tearDown() {
        if (::history.isInitialized) history.close()
        if (::networks.isInitialized) networks.close()
        if (::gate.isInitialized) runCatching { gate.forward(env.socksPort) }
        app.disconnectAndSettle()
        app.deleteAllProfiles()
        TestTrust.labCa = null
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpConnectOverTlsCarriesTraffic() {
        app.connect(tlsProfile(ProxyProtocol.HTTP, lab.tlsHttpPort).id)
        assertSent("tls-http")
        val diagnostics = app.diagnostics()
        assertEquals("no TLS failure: $diagnostics", 0L, diagnostics.tls.failures)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun socks5OverTlsCarriesTraffic() {
        app.connect(tlsProfile(ProxyProtocol.SOCKS5, lab.tlsSocksPort).id)
        assertSent("tls-socks")
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aCertificateForAnotherNameNeverConnects() = assertRefusedAtConnect(lab.tlsOtherPort, "TLS_HOSTNAME")

    @Test(timeout = TEST_TIMEOUT_MS)
    fun anExpiredCertificateNeverConnects() = assertRefusedAtConnect(lab.tlsExpiredPort, "TLS_EXPIRED")

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aCertificateFromAnotherCaNeverConnects() = assertRefusedAtConnect(lab.tlsUntrustedPort, "TLS_UNTRUSTED")

    /** The app as shipped: the platform's trust store only, so a certificate from a private CA is refused (ADR-0005). */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun theAppAsShippedTrustsNoPrivateCa() {
        TestTrust.labCa = null
        assertRefusedAtConnect(lab.tlsHttpPort, "TLS_UNTRUSTED")
    }

    /** TLS on, but the proxy speaks plain HTTP on that port: refused, never retried without TLS. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aProxyWithoutTlsNeverConnectsWithTlsOn() = assertRefusedAtConnect(env.httpPort, "TLS_HANDSHAKE")

    /**
     * Connected through the gate to a proxy with a valid certificate, which then presents one from a CA nothing
     * trusts: the first connection after the change is refused and, since no retry changes a certificate, traffic is
     * blocked at once (docs/m1/04 §4, ADR-0012) — nothing unencrypted, nothing around the proxy; the log names the
     * stage and the code; with the valid certificate back, Try again connects on the same interface.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aCertificateThatChangesWhileConnectedBlocksAtOnce() {
        gate.forward(lab.tlsHttpPort)
        val profile = tlsProfile(ProxyProtocol.HTTP, lab.gatePort)
        app.connect(profile.id)
        val established = ProxyFlowVpnService.establishedInterfaces.get()
        assertSent("tls-gate-up")

        gate.forward(lab.tlsUntrustedPort)
        // By name and by address: nothing may go unencrypted, around the proxy, or straight to an address the phone reaches.
        for ((host, port) in listOf(env.destinationName to env.destinationPort, lab.addressDestination to lab.addressPort)) {
            val line = Lines.next("tls-gate-untrusted")
            Lines.refused(line, host, port)
            val result = FromShell.tcp(host, port, line)
            assertTrue("a connection through an untrusted certificate must fail: $result", result.startsWith("ERROR"))
        }
        val blocked = app.await(what = "blocked by the refused certificate") { it.status == ConnectionStatus.Blocked }
        assertEquals("TLS_UNTRUSTED", blocked.error?.code)
        assertEquals("the interface stays, so traffic is held", 1, Descriptors.tun().size)
        assertTrue("Android keeps the VPN network", networks.present())
        val logged = app.awaitLog("the block") { it.event == LogEvent.Blocked && it.profileId == profile.id }
        assertEquals(ConnectionStage.Connected, logged.stage)
        assertEquals("TLS_UNTRUSTED", logged.code)

        gate.forward(lab.tlsHttpPort)
        app.controller.restart()
        app.awaitStatus(ConnectionStatus.Connected)
        assertEquals("the same interface", established, ProxyFlowVpnService.establishedInterfaces.get())
        assertEquals(1, Descriptors.tun().size)
        assertSent("tls-gate-back")
    }

    /**
     * HTTPS to a site through the proxy stays end to end: the shell's TLS client, trusting only the lab's CA and
     * checking the name as a browser does, sees the destination's own certificate — the very one the runner made.
     * ProxyFlow relays the bytes; it has no certificate it could present instead.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun httpsToASiteStaysEndToEnd() {
        for (profile in listOf(plainProfile(ProxyProtocol.SOCKS5, env.socksPort), tlsProfile(ProxyProtocol.HTTP, lab.tlsHttpPort))) {
            app.connect(profile.id)
            val line = Lines.next("https-${profile.protocol}-${if (profile.useTls) "tls" else "plain"}")
            Lines.sent(line, lab.httpsName, lab.httpsPort)
            val result = FromShell.tls(lab.httpsName, lab.httpsPort, lab.caPath, line)
            val match =
                Regex("^TLS (\\S+) LEAF ([0-9a-f]{64}) REPLY (.*)$").find(result) ?: throw AssertionError("TLS to the site failed: $result")
            assertEquals("the site's own certificate, end to end", lab.httpsSha256, match.groupValues[2])
            assertEquals("PROXYFLOW-TLS $line", match.groupValues[3])
            app.disconnectAndSettle()
        }
    }

    private fun tlsProfile(protocol: ProxyProtocol, port: Int): ProxyProfile = save(protocol, port, tls = true)

    private fun plainProfile(protocol: ProxyProtocol, port: Int): ProxyProfile = save(protocol, port, tls = false)

    /** The lab's address, with the certificate's name as the TLS server name (no DNS involved). */
    private fun save(protocol: ProxyProtocol, port: Int, tls: Boolean): ProxyProfile = runBlocking {
        val socks = protocol == ProxyProtocol.SOCKS5
        app.profiles.save(
            null,
            ProxyProfileInput(
                name = "${if (tls) "TLS" else "Plain"} $protocol ${UUID.randomUUID().toString().take(4)}",
                protocol = protocol,
                server = env.proxyHost,
                port = port.toString(),
                requiresSignIn = true,
                username = if (socks) env.socksUser else env.httpUser,
                password = if (socks) env.socksPassword else env.httpPassword,
                useTls = tls,
                tlsServerName = if (tls) lab.proxyName else "",
            ),
        )
    }

    /** TLS to [port] fails the pre-flight check with [code]: never Connected, no interface, nothing changed on the phone. */
    private fun assertRefusedAtConnect(port: Int, code: String) {
        val profile = tlsProfile(ProxyProtocol.HTTP, port)
        val failed: ConnectionState = app.connectExpectingFailure(profile.id)
        assertEquals("$failed", code, failed.error?.code)
        assertTrue("never Connected: ${history.states}", history.neverConnected())
        assertEquals("no VPN interface was established", establishedBefore, ProxyFlowVpnService.establishedInterfaces.get())
        assertEquals("Android never had a VPN network", 0, networks.appearances)
        assertTrue("no VPN interface", Descriptors.tun().isEmpty())
    }

    private fun assertSent(label: String) {
        val line = Lines.next(label)
        Lines.sent(line, env.destinationName, env.destinationPort)
        assertEquals("REPLY PROXYFLOW-DEST $line", FromShell.tcp(env.destinationName, env.destinationPort, line))
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 90_000L
    }
}
