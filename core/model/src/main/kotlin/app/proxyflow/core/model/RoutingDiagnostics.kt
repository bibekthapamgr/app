package app.proxyflow.core.model

/**
 * What the running connection did since it connected, as Home shows it (M6, ADR-0011):
 * how much went through the proxy, what ProxyFlow refused to carry, and the four kinds
 * of problems the diagnostics tell apart — TLS to the proxy, DNS, unsupported traffic
 * and proxy timeouts. Counts only: no destinations, names, addresses or payloads.
 */
data class RoutingDiagnostics(
    /** TCP connections the proxy opened for apps. */
    val connectionsOpened: Long = 0,
    /**
     * Different names apps looked up during this connection, each answered on the phone with a placeholder so that the
     * proxy resolves it when an app connects (counted once each by the engine, `dns_names`).
     */
    val namesForProxy: Long = 0,
    /** Name lookups answered "no IPv6 address" (AAAA, HTTPS, SVCB): names reach the proxy over IPv4 placeholders. */
    val ipv6LookupsAnsweredEmpty: Long = 0,
    val tls: TlsProblems = TlsProblems(),
    val dns: DnsProblems = DnsProblems(),
    val unsupported: UnsupportedTraffic = UnsupportedTraffic(),
    /** Connecting to the proxy, or its handshake, took too long. */
    val proxyTimeouts: Long = 0,
    /** Every failed connection, of any kind. */
    val connectionsFailed: Long = 0,
    /** The last connection that failed, with its catalogue code; null when none did. */
    val lastProblem: ConnectionError? = null,
) {
    val hasProblems: Boolean get() = tls.failures > 0 || dns.total > 0 || proxyTimeouts > 0
}

/** TLS to the proxy that failed while connected: the proxy's certificate or handshake was refused. */
data class TlsProblems(
    val failures: Long = 0,
    /** The code of the last one: TLS_UNTRUSTED, TLS_HOSTNAME, TLS_EXPIRED or TLS_HANDSHAKE. */
    val lastCode: String? = null,
)

/**
 * Names that could not be used. None of these ever falls back to a lookup outside the proxy. A heuristic for "DNS":
 * the proxy reports a name it cannot resolve the way it reports a host that is down (SOCKS5 "host unreachable",
 * HTTP 502/503/504), and some proxies only as a general failure, which counts under the last problem alone.
 */
data class DnsProblems(
    /** The proxy's own hostname could not be looked up (PROXY_DNS_FAILED). */
    val proxyNameLookups: Long = 0,
    /** Connections to names the proxy could not resolve or reach (TARGET_UNREACHABLE for a name), each attempt once. */
    val namesNotReached: Long = 0,
    /**
     * Queries ProxyFlow does not answer by design: other record types (MX, TXT, SRV, PTR…) and malformed queries.
     * Shown, but no failure: they never count in [total].
     */
    val queriesRefused: Long = 0,
    /** Lookups refused because every placeholder address was in use, or too many came at once. */
    val lookupsDropped: Long = 0,
    /** Connections to a placeholder address from an earlier session that no name maps to any more. */
    val staleAddresses: Long = 0,
) {
    /** The problems: everything except the queries refused by design. */
    val total: Long get() = proxyNameLookups + namesNotReached + lookupsDropped + staleAddresses
}

/** Traffic ProxyFlow does not carry (ADR-0004): refused on the phone, never sent anywhere. */
data class UnsupportedTraffic(
    /** UDP other than DNS: QUIC, calls, games. Each app gets "port unreachable" at once. */
    val udpBlocked: Long = 0,
    /** The destination ports blocked most, most first (at most three). */
    val udpTopPorts: List<Int> = emptyList(),
    /** Ping requests (ICMP echo, IPv4 and IPv6), dropped unanswered. */
    val pingsDropped: Long = 0,
)
