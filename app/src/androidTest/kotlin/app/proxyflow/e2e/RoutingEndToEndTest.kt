package app.proxyflow.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M6 end to end on an emulator (ADR-0011, docs/m6/routing-and-limitations.md): what the VPN
 * carries and what it refuses, from the shell — a user the VPN covers — with the app's own
 * diagnostics read alongside. TCP goes through the proxy by name, by IPv4 or IPv6 address, to an
 * IPv6-only name and to the LAN address; DNS is answered on the phone whatever server an app asks
 * and however (UDP or TCP); UDP and ping are refused on the phone; a name the proxy can't resolve,
 * a proxy that stops answering and a proxy that hangs all fail closed, with nothing sent around
 * the proxy and DNS still answered on the phone; the proxy's own name is the only one the app looks
 * up itself. scripts/m6_evidence.py then checks the lab's logs and the packet captures: every line
 * arrived where it was sent, through a proxy, once; nothing reached a destination, a UDP sink or a
 * DNS server around the proxy.
 */
@RunWith(AndroidJUnit4::class)
class RoutingEndToEndTest {
    private val app = App()
    private lateinit var env: E2eConfig
    private lateinit var lab: LabConfig
    private lateinit var gate: Gate
    private lateinit var history: StateHistory

    @Before
    fun setUp() {
        env = E2eConfig.load()
        lab = LabConfig.load()
        gate = Gate(lab)
        gate.forward(env.socksPort)
        TestTrust.labCa = null
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        history = StateHistory(app.controller)
    }

    @After
    fun tearDown() {
        if (::history.isInitialized) history.close()
        if (::gate.isInitialized) runCatching { gate.forward(env.socksPort) }
        app.disconnectAndSettle()
        app.deleteAllProfiles()
    }

    /**
     * Names are answered on the phone with a placeholder, the same for every DNS server an app may ask — the VPN's,
     * the phone's own uplink resolver, a public one — over UDP and TCP; the proxy gets the name and resolves it.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun namesAreAnsweredOnThePhoneAndResolvedByTheProxy() {
        app.connect(socks().id)

        val placeholders = FromShell.resolve(env.destinationName)
        assertEquals("the system resolver's answer: $placeholders", 1, placeholders.size)
        val placeholder = placeholders.single()
        assertTrue("$placeholder is a placeholder (198.18.0.0/15), never the real address", isPlaceholder(placeholder))
        for (server in listOf(VPN_DNS, UPLINK_DNS, PUBLIC_DNS)) {
            assertEquals(
                "A from $server",
                DnsAnswer(DnsAnswer.NOERROR, listOf(placeholder)),
                FromShell.dns(server, env.destinationName, DnsAnswer.A),
            )
            assertEquals(
                "AAAA from $server",
                DnsAnswer(DnsAnswer.NOERROR, emptyList()),
                FromShell.dns(server, env.destinationName, DnsAnswer.AAAA),
            )
            assertEquals(
                "HTTPS from $server",
                DnsAnswer(DnsAnswer.NOERROR, emptyList()),
                FromShell.dns(server, env.destinationName, DnsAnswer.HTTPS),
            )
            assertEquals(
                "TXT from $server",
                DnsAnswer(DnsAnswer.REFUSED, emptyList()),
                FromShell.dns(server, env.destinationName, DnsAnswer.TXT),
            )
        }
        for (server in listOf(VPN_DNS, PUBLIC_DNS)) {
            val overTcp = FromShell.dns(server, env.destinationName, DnsAnswer.A, overTcp = true)
            assertEquals("A over TCP from $server", DnsAnswer(DnsAnswer.NOERROR, listOf(placeholder)), overTcp)
        }
        assertSent("names", env.destinationName, env.destinationPort)

        val stats = ProxyEngine().stats()
        assertTrue("queries over TCP were answered on the phone: ${stats.dnsOverTcp}", stats.dnsOverTcp >= 2)
        app.awaitDiagnostics("names counted for the proxy") { it.namesForProxy >= 1 && it.ipv6LookupsAnsweredEmpty >= 6 }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun ipv4AddressesGoThroughTheProxyByAddress() {
        for (profile in listOf(socks(), http())) {
            app.connect(profile.id)
            assertSent("address-${profile.protocol}", lab.addressDestination, lab.addressPort)
            app.disconnectAndSettle()
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun ipv6AddressesGoThroughTheProxy() {
        for (profile in listOf(socks(), http())) {
            app.connect(profile.id)
            assertSent("ipv6-${profile.protocol}", lab.v6Address, lab.v6Port)
            app.disconnectAndSettle()
        }
    }

    /** A name with only an IPv6 address: the phone gets an IPv4 placeholder and no IPv6 answer; the proxy reaches it over IPv6. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun ipv6OnlyNamesAreReachedThroughTheProxy() {
        for (profile in listOf(socks(), http())) {
            app.connect(profile.id)
            assertTrue(isPlaceholder(FromShell.resolve(lab.v6Name).single()))
            assertEquals(DnsAnswer(DnsAnswer.NOERROR, emptyList()), FromShell.dns(VPN_DNS, lab.v6Name, DnsAnswer.AAAA))
            assertSent("ipv6-name-${profile.protocol}", lab.v6Name, lab.v6Port)
            app.disconnectAndSettle()
        }
    }

    /**
     * The LAN goes through the proxy too: 10.0.2.2 is the emulator's own view of the runner, where reaching
     * 10.0.2.2:8082 directly would land on the runner's loopback, where nothing listens. Only the proxy,
     * which reaches the runner's 10.0.2.2 address, gets an answer from it.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun lanAddressesGoThroughTheProxyToo() {
        for (profile in listOf(socks(), http())) {
            app.connect(profile.id)
            assertSent("lan-${profile.protocol}", lab.lanAddress, lab.lanPort)
            app.disconnectAndSettle()
        }
    }

    /** UDP is refused on the phone ("port unreachable" at once), over IPv4 and IPv6, and never sent; Home counts it. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun udpIsRefusedOnThePhoneAndNeverSent() {
        app.connect(socks().id)
        val before = ProxyEngine().stats().udpRejectedPorts[lab.udpPort.toString()] ?: 0

        assertEquals("UDP to ${env.destinationAddress}", "PORT_UNREACHABLE", FromShell.udp(env.destinationAddress, lab.udpPort))
        assertEquals("UDP to ${lab.v6Address}", "PORT_UNREACHABLE", FromShell.udp(lab.v6Address, lab.udpPort))

        eventually("the engine counted the datagrams") {
            (ProxyEngine().stats().udpRejectedPorts[lab.udpPort.toString()] ?: 0) >= before + 2
        }
        app.awaitDiagnostics("UDP counted as unsupported traffic") { it.unsupported.udpBlocked >= 2 }
    }

    /** Pings (IPv4 and IPv6) are dropped unanswered: ProxyFlow never answers one, and none leaves the phone. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun pingsAreNeverAnswered() {
        app.connect(socks().id)
        val before = ProxyEngine().stats().icmpEchoRequestsDropped

        val v4 = Shell.run("ping -c $PINGS -W 1 ${env.destinationAddress}")
        // Older images have ping6; newer ones may have only ping -6.
        val ping6 = if (Shell.run("ls /system/bin/ping6").trim() == "/system/bin/ping6") "ping6" else "ping -6"
        val v6 = Shell.run("$ping6 -c $PINGS -W 1 ${lab.v6Address}")

        for ((family, output) in listOf("IPv4" to v4, "IPv6" to v6)) {
            assertTrue("$family: $output", Regex("$PINGS packets transmitted, 0 received").containsMatchIn(output))
        }
        eventually("the engine dropped the pings") { ProxyEngine().stats().icmpEchoRequestsDropped >= before + 2 * PINGS }
        assertEquals("ProxyFlow never answers a ping", 0L, ProxyEngine().stats().icmpEchoRepliesSent)
        app.awaitDiagnostics("pings counted as unsupported traffic") { it.unsupported.pingsDropped >= 2L * PINGS }
    }

    /** The proxy can't resolve the name: the app's connection fails at once, and nothing is looked up or sent around it. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aNameTheProxyCannotResolveFailsWithoutAFallback() {
        for (profile in listOf(socks(), http())) {
            app.connect(profile.id)
            assertTrue(isPlaceholder(FromShell.resolve(lab.missingName).single()))
            assertRefused("missing-name-${profile.protocol}", lab.missingName, env.destinationPort)
            eventually("the engine counted a failure for a name") {
                (ProxyEngine().stats().hostnameFailures["TARGET_UNREACHABLE"] ?: 0) >= 1
            }
            app.awaitDiagnostics("a DNS failure at the proxy") { it.dns.namesNotReached >= 1 }
            app.disconnectAndSettle()
        }
    }

    /**
     * The proxy goes away while connected: apps' connections fail at once (nothing goes around it), names are still
     * answered on the phone, Home says what failed, and traffic flows again as soon as the proxy is back.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun whenTheProxyStopsAnsweringNothingGoesAroundIt() {
        app.connect(gated().id)
        assertSent("gate-up", env.destinationName, env.destinationPort)

        gate.refuse()
        assertRefused("gate-down", env.destinationName, env.destinationPort)
        assertEquals(DnsAnswer.NOERROR, FromShell.dns(VPN_DNS, env.destinationName, DnsAnswer.A).rcode)
        val down = app.awaitDiagnostics("the failure counted") { it.lastProblem?.code == "PROXY_UNREACHABLE" }
        assertEquals("still connected: traffic is held, not let out", ConnectionStatus.Connected, app.controller.state.value.status)
        assertTrue("$down", down.connectionsFailed >= 1)

        gate.forward(env.socksPort)
        assertSent("gate-back", env.destinationName, env.destinationPort)
    }

    /** A proxy that accepts connections and never answers: each connection fails after the handshake timeout, counted as a proxy timeout. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aProxyThatHangsIsReportedAsATimeout() {
        app.connect(gated().id)
        assertSent("gate-up", env.destinationName, env.destinationPort)

        gate.silent()
        assertRefused("gate-silent", env.destinationName, env.destinationPort, timeoutMillis = HANGING_PROXY_WAIT_MS)
        eventually("the engine counted a proxy timeout") { ProxyEngine().stats().proxyTimeouts >= 1 }
        app.awaitDiagnostics("a proxy timeout") { it.proxyTimeouts >= 1 }

        gate.forward(env.socksPort)
        assertSent("gate-back", env.destinationName, env.destinationPort)
    }

    /** The proxy's own name is looked up by the app, on the phone's network (ADR-0003); the lab's uplink DNS server logs it. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun theProxysOwnNameIsLookedUpByTheApp() {
        val profile = app.save("By name", ProxyProtocol.SOCKS5, lab.proxyName, env.socksPort, env.socksUser, env.socksPassword)
        app.connect(profile.id)
        assertSent("proxy-by-name", env.destinationName, env.destinationPort)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aProxyNameThatDoesNotExistNeverConnects() {
        val profile = app.save("Unknown", ProxyProtocol.SOCKS5, "proxy.invalid", env.socksPort, env.socksUser, env.socksPassword)
        val established = ProxyFlowVpnService.establishedInterfaces.get()

        val failed = app.connectExpectingFailure(profile.id)

        assertEquals("PROXY_DNS_FAILED", failed.error?.code)
        assertTrue("never Connected: ${history.states}", history.neverConnected())
        assertEquals("no VPN interface was established", established, ProxyFlowVpnService.establishedInterfaces.get())
    }

    private fun socks(): ProxyProfile =
        app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)

    private fun http(): ProxyProfile = app.save("HTTP", ProxyProtocol.HTTP, env.proxyHost, env.httpPort, env.httpUser, env.httpPassword)

    /** SOCKS5 through the gate, which relays to Dante until a test breaks it. */
    private fun gated(): ProxyProfile =
        app.save("Gate", ProxyProtocol.SOCKS5, env.proxyHost, lab.gatePort, env.socksUser, env.socksPassword)

    /** One line from the shell to [host]:[port], answered by the destination there. */
    private fun assertSent(label: String, host: String, port: Int) {
        val line = Lines.next(label)
        Lines.sent(line, host, port)
        assertEquals("$host:$port", "REPLY PROXYFLOW-DEST $line", FromShell.tcp(host, port, line))
    }

    /** One line that must get nowhere: the connection fails, and m6_evidence.py checks no log has the line. */
    private fun assertRefused(label: String, host: String, port: Int, timeoutMillis: Int = 10_000) {
        val line = Lines.next(label)
        Lines.refused(line, host, port)
        val result = FromShell.tcp(host, port, line, timeoutMillis)
        assertTrue("$host:$port must fail: $result", result.startsWith("ERROR"))
    }

    private fun isPlaceholder(address: String): Boolean {
        val bytes = InetAddress.getByName(address).address
        return bytes.size == 4 && bytes[0] == 198.toByte() && (bytes[1] == 18.toByte() || bytes[1] == 19.toByte())
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 90_000L
        const val VPN_DNS = "198.18.0.2"

        /** The emulator's own resolver on its uplink: an app asking it directly still gets ProxyFlow's answer. */
        const val UPLINK_DNS = "10.0.2.3"
        const val PUBLIC_DNS = "8.8.8.8"
        const val PINGS = 3

        /** The engine gives up on a silent proxy after its 10 s handshake timeout. */
        const val HANGING_PROXY_WAIT_MS = 30_000
    }
}
