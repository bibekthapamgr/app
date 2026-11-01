package app.proxyflow.core.vpn

import app.proxyflow.core.engine.EngineError
import app.proxyflow.core.engine.EngineStats
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.DnsProblems
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.TlsProblems
import app.proxyflow.core.model.UnsupportedTraffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine's counters as Home's diagnostics (ADR-0011). */
class DiagnosticsTest {
    @Test
    fun aQuietConnectionHasNoProblems() {
        val diagnostics = EngineStats(stateName = "running", tcpOpened = 3, dns = mapOf("mapped" to 2L, "nodata" to 2L)).toDiagnostics()
        assertEquals(RoutingDiagnostics(connectionsOpened = 3, namesForProxy = 2, ipv6LookupsAnsweredEmpty = 2), diagnostics)
        assertFalse(diagnostics.hasProblems)
    }

    @Test
    fun everyKindOfProblemIsToldApart() {
        val stats = EngineStats(
            stateName = "running",
            tcpOpened = 40,
            tcpFailed = 9,
            udpRejected = 12,
            udpRejectedPorts = mapOf("443" to 7L, "3478" to 3L, "123" to 1L, "53000" to 1L),
            icmpEchoRequestsDropped = 4,
            proxyTimeouts = 2,
            dnsDropped = 1,
            failures = mapOf(
                "TLS_UNTRUSTED" to 1L,
                "TLS_HOSTNAME" to 1L,
                "HANDSHAKE_TIMEOUT" to 2L,
                "PROXY_DNS_FAILED" to 1L,
                "TARGET_UNREACHABLE" to 3L,
                "STALE_DNS_MAPPING" to 1L,
            ),
            hostnameFailures = mapOf("TARGET_UNREACHABLE" to 2L),
            dns = mapOf("mapped" to 30L, "refused" to 2L, "formerr" to 1L, "exhausted" to 1L),
            lastProxyError = EngineError("TLS_HOSTNAME", "proxy certificate does not match the proxy hostname"),
            lastFailure = EngineError("TARGET_UNREACHABLE", "the proxy could not reach the host"),
        )
        val diagnostics = stats.toDiagnostics()
        assertEquals(TlsProblems(failures = 2, lastCode = "TLS_HOSTNAME"), diagnostics.tls)
        assertEquals(
            DnsProblems(proxyNameLookups = 1, namesNotReached = 2, queriesRefused = 3, lookupsDropped = 2, staleAddresses = 1),
            diagnostics.dns,
        )
        assertEquals(9L, diagnostics.dns.total)
        // The ports blocked most, most first; equal counts by port number.
        assertEquals(UnsupportedTraffic(udpBlocked = 12, udpTopPorts = listOf(443, 3478, 123), pingsDropped = 4), diagnostics.unsupported)
        assertEquals(2L, diagnostics.proxyTimeouts)
        assertEquals(40L, diagnostics.connectionsOpened)
        assertEquals(9L, diagnostics.connectionsFailed)
        assertEquals(ConnectionError("TARGET_UNREACHABLE", "the proxy could not reach the host"), diagnostics.lastProblem)
        assertTrue(diagnostics.hasProblems)
    }

    @Test
    fun theLastTlsCodeSurvivesALaterProblemOfAnotherKind() {
        val stats = EngineStats(
            failures = mapOf("TLS_EXPIRED" to 3L, "TLS_UNTRUSTED" to 1L, "HANDSHAKE_TIMEOUT" to 1L),
            lastProxyError = EngineError("HANDSHAKE_TIMEOUT"),
        )
        assertEquals(TlsProblems(failures = 4, lastCode = "TLS_EXPIRED"), stats.toDiagnostics().tls)
    }

    @Test
    fun targetFailuresForAddressesAreNoDnsProblem() {
        val stats =
            EngineStats(
                failures = mapOf("TARGET_UNREACHABLE" to 5L, "TARGET_REFUSED" to 2L),
                hostnameFailures = mapOf("TARGET_REFUSED" to 2L),
            )
        val diagnostics = stats.toDiagnostics()
        assertEquals(0L, diagnostics.dns.total)
        assertFalse(diagnostics.hasProblems)
    }
}
