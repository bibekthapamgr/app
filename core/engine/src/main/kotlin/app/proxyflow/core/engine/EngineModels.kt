package app.proxyflow.core.engine

import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the engine needs to know about a proxy. It holds no username or
 * password: those travel separately as an [EngineSecret].
 */
data class EngineProfile(
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val tls: Boolean = false,
    /** The name TLS sends and checks, when it differs from [host]. */
    val tlsServerName: String? = null,
    val mtu: Int? = null,
    /** Privacy switch (Settings, "Record destinations"): destinations in events and flows. */
    val logDestinations: Boolean = false,
    val dialTimeoutMillis: Long? = null,
    val handshakeTimeoutMillis: Long? = null,
    val idleTimeoutMillis: Long? = null,
    val maxConnections: Int? = null,
)

fun ProxyProfile.toEngineProfile(logDestinations: Boolean = false): EngineProfile = EngineProfile(
    protocol = protocol,
    host = host,
    port = port,
    tls = useTls,
    tlsServerName = tlsServerName,
    logDestinations = logDestinations,
)

/** The engine's lifecycle (tunnel.State). */
enum class EngineState {
    New,
    Running,

    /** The packet loop ended without stop (the TUN closed or failed): ENGINE_STOPPED. Call stop. */
    Failed,
    Stopping,
    Stopped,
    Unknown,
    ;

    companion object {
        fun of(value: String?): EngineState = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Unknown
    }
}

/** A log-safe error: a code from the catalogue (docs/m1/04 §4) and a detail without secrets. */
@Serializable
data class EngineError(val code: String, val detail: String? = null, val time: String? = null)

@Serializable
data class EngineStatus(
    @SerialName("state") val stateName: String = "unknown",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("last_error") val lastError: EngineError? = null,
    @SerialName("last_proxy_error") val lastProxyError: EngineError? = null,
    @SerialName("consecutive_proxy_failures") val consecutiveProxyFailures: Long = 0,
    val flows: Int = 0,
    @SerialName("tcp_active") val tcpActive: Long = 0,
) {
    val state: EngineState get() = EngineState.of(stateName)
}

/** The counters the UI and diagnostics use (tunnel.Snapshot has a few more). */
@Serializable
data class EngineStats(
    @SerialName("state") val stateName: String = "unknown",
    @SerialName("uptime_ms") val uptimeMillis: Long = 0,
    @SerialName("last_error") val lastError: EngineError? = null,
    @SerialName("last_failure") val lastFailure: EngineError? = null,
    @SerialName("last_proxy_error") val lastProxyError: EngineError? = null,
    @SerialName("consecutive_proxy_failures") val consecutiveProxyFailures: Long = 0,
    val flows: Int = 0,
    @SerialName("tcp_connecting") val tcpConnecting: Int = 0,
    @SerialName("tcp_active") val tcpActive: Long = 0,
    @SerialName("tcp_opened") val tcpOpened: Long = 0,
    @SerialName("tcp_failed") val tcpFailed: Long = 0,
    val canceled: Long = 0,
    @SerialName("bytes_up") val bytesUp: Long = 0,
    @SerialName("bytes_down") val bytesDown: Long = 0,
    @SerialName("udp_rejected") val udpRejected: Long = 0,
    /** UDP datagrams refused, by destination port (at most 256 ports are told apart). */
    @SerialName("udp_rejected_ports") val udpRejectedPorts: Map<String, Long> = emptyMap(),
    @SerialName("idle_timeouts") val idleTimeouts: Long = 0,
    /** DNS sockets refused because too many were open at once (the app retries). */
    @SerialName("dns_dropped") val dnsDropped: Long = 0,
    /** DNS queries answered over TCP port 53 (ADR-0011). */
    @SerialName("dns_tcp_queries") val dnsOverTcp: Long = 0,
    /** Connecting to the proxy, or its handshake (TLS, SOCKS5, HTTP CONNECT), took too long. */
    @SerialName("proxy_timeouts") val proxyTimeouts: Long = 0,
    /** TCP to ProxyFlow's own addresses (not DNS), refused on the phone: Android's DNS-over-TLS probe, for one. */
    @SerialName("local_refused") val localRefused: Long = 0,
    @SerialName("events_dropped") val eventsDropped: Long = 0,
    /** Ping requests (ICMP and ICMPv6 echo) dropped: ProxyFlow never answers one (ADR-0004). */
    @SerialName("icmp_echo_requests_dropped") val icmpEchoRequestsDropped: Long = 0,
    @SerialName("icmp_fragments_dropped") val icmpFragmentsDropped: Long = 0,
    /** Echo replies that reached the TUN: must stay 0. */
    @SerialName("icmp_echo_replies_sent") val icmpEchoRepliesSent: Long = 0,
    @SerialName("recovered_panics") val recoveredPanics: Long = 0,
    /** Failed flows by error code. */
    val failures: Map<String, Long> = emptyMap(),
    /** Failed flows to a name (the proxy was asked to resolve it), by error code. */
    @SerialName("hostname_failures") val hostnameFailures: Map<String, Long> = emptyMap(),
    /** DNS queries by outcome: mapped, nodata, refused, formerr, ignored, exhausted. */
    val dns: Map<String, Long> = emptyMap(),
    @SerialName("fake_dns_mappings") val fakeDnsMappings: Int = 0,
    val goroutines: Int = 0,
    @SerialName("rss_kib") val rssKib: Long = 0,
) {
    val state: EngineState get() = EngineState.of(stateName)
}

@Serializable
data class EngineFlow(
    val id: Long,
    val kind: String,
    val state: String,
    @SerialName("dst_kind") val destinationKind: String? = null,
    /** Only when the profile records destinations. */
    @SerialName("dst") val destination: String? = null,
    val port: Int = 0,
    @SerialName("age_ms") val ageMillis: Long = 0,
    @SerialName("bytes_up") val bytesUp: Long = 0,
    @SerialName("bytes_down") val bytesDown: Long = 0,
)

@Serializable
data class EngineEvent(
    val time: String? = null,
    val type: String,
    @SerialName("conn_id") val connectionId: Long = 0,
    val proto: String? = null,
    @SerialName("dst_kind") val destinationKind: String? = null,
    @SerialName("dst") val destination: String? = null,
    val port: Int = 0,
    val code: String? = null,
    val detail: String? = null,
    @SerialName("setup_ms") val setupMillis: Long = 0,
    @SerialName("bytes_up") val bytesUp: Long = 0,
    @SerialName("bytes_down") val bytesDown: Long = 0,
    @SerialName("duration_ms") val durationMillis: Long = 0,
    val count: Int = 0,
)

/** One tunnel through the proxy, opened and closed (pfengine.Probe). */
@Serializable
data class ProbeResult(
    val ok: Boolean,
    val code: String? = null,
    val detail: String? = null,
    /** The code concerns the proxy itself (counts towards the escalation rule), not one destination. */
    @SerialName("proxy_level") val proxyLevel: Boolean = false,
    @SerialName("latency_ms") val latencyMillis: Long = 0,
)

/** The whole packet engine run on a socketpair (pfengine.SelfTest). */
@Serializable
data class SelfTestResult(
    val ok: Boolean,
    val code: String? = null,
    val detail: String? = null,
    @SerialName("proxy_level") val proxyLevel: Boolean = false,
    @SerialName("latency_ms") val latencyMillis: Long = 0,
    @SerialName("dns_answer") val dnsAnswer: String? = null,
    val connected: Boolean = false,
    @SerialName("bytes_sent") val bytesSent: Int = 0,
    @SerialName("bytes_received") val bytesReceived: Int = 0,
    @SerialName("reply_b64") val replyBase64: String? = null,
    @SerialName("udp_rejected") val udpRejected: Boolean = false,
    @SerialName("tcp_opened") val tcpOpened: Long = 0,
    @SerialName("engine_bytes_up") val engineBytesUp: Long = 0,
    @SerialName("engine_bytes_down") val engineBytesDown: Long = 0,
    @SerialName("stop_ms") val stopMillis: Long = 0,
    /** Goroutines in the whole process before and after; informational (the Go runtime, gomobile and a running VPN engine start their own). */
    @SerialName("goroutines_before") val goroutinesBefore: Int = 0,
    @SerialName("goroutines_after") val goroutinesAfter: Int = 0,
    /** Goroutines this self-test started (directly or through the engine) still alive after it released everything: must be 0. */
    @SerialName("goroutines_left") val goroutinesLeft: Int = 0,
    /** For each of those, the function it is in (function names only). */
    @SerialName("goroutines_left_in") val goroutinesLeftIn: List<String> = emptyList(),
)

/** The addresses the VpnService builder uses (pfengine.NetworkPlan). */
@Serializable
data class NetworkPlan(
    @SerialName("tun_address4") val tunAddress4: String,
    @SerialName("tun_address6") val tunAddress6: String,
    @SerialName("dns_server4") val dnsServer4: String,
    @SerialName("fake_ip_prefix") val fakeIpPrefix: String,
    @SerialName("default_mtu") val defaultMtu: Int,
)

/** An engine call failed; [code] is from the catalogue, [detail] is log-safe. */
class EngineException(val code: String, val detail: String) : Exception("$code: $detail") {
    companion object {
        /** Parses the engine's "CODE: detail" error messages. */
        fun from(e: Throwable): EngineException {
            val message = e.message.orEmpty()
            val code = message.substringBefore(':', "").trim()
            return if (code.isNotEmpty() && code.all { it.isUpperCase() || it == '_' }) {
                EngineException(code, message.substringAfter(':').trim())
            } else {
                EngineException("INTERNAL", message.ifBlank { e.javaClass.simpleName })
            }
        }
    }
}
