package app.proxyflow.core.vpn

import app.proxyflow.core.engine.AndroidEnginePlatform
import app.proxyflow.core.engine.EngineSecret
import app.proxyflow.core.engine.EngineState
import app.proxyflow.core.engine.EngineStats
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.engine.toEngineProfile
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.DnsProblems
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.TlsProblems
import app.proxyflow.core.model.UnsupportedTraffic
import javax.net.ssl.X509TrustManager

/**
 * The packet engine as the session drives it: [ProxyEngine] in the app, a fake
 * in the JVM tests. Every call blocks; the session makes them off the main thread.
 * Each call that takes an [EngineSecret] consumes it (the bytes are zeroed).
 */
interface TunnelEngine {
    /** The VPN interface the engine expects: its addresses come from the engine itself. */
    fun tunConfig(): TunConfig

    /**
     * The pre-flight check: one tunnel to [target] through the proxy, opened and
     * closed again, on a socket kept out of any VPN by [protect].
     */
    fun probe(profile: ProxyProfile, secret: EngineSecret?, target: String, timeoutMillis: Long, protect: (Int) -> Boolean): PreflightAnswer

    /**
     * Starts the engine on [tun], which stays owned by the caller. [signals] hears, from any
     * thread, what the session acts on (ADR-0012): the packet loop ending without [stop]
     * (catalogue code `ENGINE_STOPPED`, once), and each connection that failed — at the proxy
     * itself, or for one site only.
     *
     * @throws app.proxyflow.core.engine.EngineException when the engine cannot start; other
     *   exceptions and a library that fails to load are possible too.
     */
    fun start(
        tun: TunInterface,
        config: TunConfig,
        profile: ProxyProfile,
        secret: EngineSecret?,
        protect: (Int) -> Boolean,
        signals: (EngineSignal) -> Unit,
    )

    /** Stops the engine and waits for it; it never closes the TUN descriptor. Safe at any time. */
    fun stop()

    /**
     * The running engine's counters as Home's diagnostics (ADR-0011), or null when no engine
     * runs or they can't be read. Unlike the other calls, safe from any thread at any time.
     */
    fun diagnostics(): RoutingDiagnostics? = null

    /**
     * The engine's traffic counters (M7) while it runs, and after it failed until it is stopped; null
     * when none was started or it stopped. Safe from any thread at any time.
     */
    fun traffic(): EngineTraffic? = null

    /** Proxy-level failures in a row as the running engine counts them (a success resets it); 0 when none runs. */
    fun consecutiveProxyFailures(): Long = 0

    /**
     * A SOCKS5 proxy's connection requests left unanswered in a row, and to how many different sites, as the running
     * engine counts them (M8): anything that works, and any answer from the proxy, starts the count over. Zero when
     * none runs.
     */
    fun unansweredRequests(): UnansweredRequests = UnansweredRequests()

    /** Ends every connection of the running engine — apps reconnect at once, on the phone's new network; how many it ended. */
    fun resetFlows(): Int = 0
}

/** What a running engine tells the session (ADR-0012). */
sealed interface EngineSignal {
    /** The packet loop ended without stop(). */
    data class Failed(val error: ConnectionError) : EngineSignal

    /** A connection failed at the proxy itself (a proxy-level code: the escalation rule counts these). */
    data class ProxyFailure(val error: ConnectionError) : EngineSignal

    /** A connection to one site failed while the proxy worked (for the log, counted per minute). */
    data class ConnectionFailure(val code: String) : EngineSignal

    /**
     * A SOCKS5 proxy answered the sign-in but not the connection request in time (M8): the site's failure, unless
     * several in a row to different sites go unanswered ([TunnelEngine.unansweredRequests]).
     */
    data class Unanswered(val error: ConnectionError) : EngineSignal
}

/** See [TunnelEngine.unansweredRequests]. */
data class UnansweredRequests(val inARow: Long = 0, val sites: Int = 0)

/**
 * The engine's traffic counters since it started (M7): bytes relayed for the apps' TCP connections,
 * connections relaying now, opened and refused, UDP datagrams refused and pings dropped.
 */
data class EngineTraffic(
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val activeConnections: Long = 0,
    val opened: Long = 0,
    val failed: Long = 0,
    val udpBlocked: Long = 0,
    val pingsDropped: Long = 0,
) {
    operator fun plus(other: EngineTraffic) = EngineTraffic(
        bytesUp + other.bytesUp,
        bytesDown + other.bytesDown,
        other.activeConnections,
        opened + other.opened,
        failed + other.failed,
        udpBlocked + other.udpBlocked,
        pingsDropped + other.pingsDropped,
    )
}

/**
 * The pre-flight check's answer (ADR-0010 §4). It passes only when the proxy opened the
 * tunnel. Any other answer fails the check with its code: a proxy that refuses the
 * check's destination (HTTP 403, SOCKS5 0x02 — what providers also send for an expired
 * plan or an address that isn't allowed), that answers with a general failure (SOCKS5
 * 0x01, or another HTTP status such as 401, 402, 404, 429 or 500), that can't reach it
 * (HTTP 502–504, SOCKS5 0x03–0x06: usually no working way out to the Internet) or that
 * doesn't support the request would show Connected while apps' connections fail.
 */
data class PreflightAnswer(val ok: Boolean, val code: String? = null, val detail: String? = null) {
    val passes: Boolean get() = ok

    /** Why the check failed, for the screens: the engine's code, or INTERNAL when it gave none. */
    val error: ConnectionError get() = ConnectionError(code ?: ConnectionError.INTERNAL, detail)
}

/**
 * [TunnelEngine] on the real engine (libgojni.so, loaded on first use).
 *
 * @param trust decides whether a TLS proxy's certificate is trusted, asked once per check and
 *   per engine start: the platform's trust manager (system CAs, Android's distrust rules, the
 *   app's network security config; ADR-0005). Only the device tests replace it, with a
 *   platform trust manager whose anchors include their run's test CA (ADR-0011); nothing in
 *   the app does.
 */
class ProxyTunnelEngine(
    private val engine: ProxyEngine = ProxyEngine(),
    private val trust: () -> X509TrustManager = { AndroidEnginePlatform.systemTrustManager() },
) : TunnelEngine {
    override fun tunConfig(): TunConfig = TunConfig.from(engine.networkPlan())

    override fun probe(
        profile: ProxyProfile,
        secret: EngineSecret?,
        target: String,
        timeoutMillis: Long,
        protect: (Int) -> Boolean,
    ): PreflightAnswer {
        val result = engine.probe(
            profile.toEngineProfile(),
            secret,
            target,
            timeoutMillis,
            AndroidEnginePlatform(protect, trustManager = trust()),
        )
        return PreflightAnswer(ok = result.ok, code = result.code, detail = result.detail)
    }

    override fun start(
        tun: TunInterface,
        config: TunConfig,
        profile: ProxyProfile,
        secret: EngineSecret?,
        protect: (Int) -> Boolean,
        signals: (EngineSignal) -> Unit,
    ) {
        val platform = AndroidEnginePlatform(
            protector = protect,
            trustManager = trust(),
            onEvent = { event -> signalOf(event.type, event.code, event.detail, event.unanswered)?.let(signals) },
        )
        // The engine's MTU must be the interface's: gVisor reads whole packets of up to that size.
        engine.start(tun.fd, profile.toEngineProfile().copy(mtu = config.mtu), secret, platform)
    }

    override fun stop() = engine.stop()

    override fun diagnostics(): RoutingDiagnostics? = statsWhile(EngineState.Running)?.toDiagnostics()

    // A failed engine (its packet loop ended) keeps its counters until it is stopped: they are the session's traffic too.
    override fun traffic(): EngineTraffic? = statsWhile(EngineState.Running, EngineState.Failed)?.let {
        EngineTraffic(
            bytesUp = it.bytesUp,
            bytesDown = it.bytesDown,
            activeConnections = it.tcpActive,
            opened = it.tcpOpened,
            failed = it.tcpFailed,
            udpBlocked = it.udpRejected,
            pingsDropped = it.icmpEchoRequestsDropped,
        )
    }

    override fun consecutiveProxyFailures(): Long =
        runCatching { engine.status() }.getOrNull()?.takeIf { it.state == EngineState.Running }?.consecutiveProxyFailures ?: 0

    override fun unansweredRequests(): UnansweredRequests =
        runCatching { engine.status() }.getOrNull()?.takeIf { it.state == EngineState.Running }
            ?.let { UnansweredRequests(it.unansweredRequests, it.unansweredSites) }
            ?: UnansweredRequests()

    override fun resetFlows(): Int = runCatching { engine.resetFlows() }.getOrDefault(0)

    private fun statsWhile(vararg states: EngineState) = runCatching { engine.stats() }.getOrNull()?.takeIf { it.state in states }

    internal companion object {
        /** tunnel.EvEngineFailed: the packet loop ended without Stop. */
        const val ENGINE_FAILED = "engine_failed"

        /** tunnel.EvConnFail: one connection failed. */
        const val CONN_FAIL = "conn_fail"

        /** A connection still being set up when the engine stopped or reset its flows: never a failure (docs/m1/04 §4). */
        const val CANCELED = "CANCELED"

        /** What an engine event means for the session, or null when it means nothing to it. */
        fun signalOf(type: String, code: String?, detail: String?, unanswered: Boolean = false): EngineSignal? = when {
            type == ENGINE_FAILED -> EngineSignal.Failed(ConnectionError(code ?: ConnectionError.ENGINE_STOPPED, detail))
            type != CONN_FAIL || code == null || code == CANCELED -> null
            code in RetryPolicy.PROXY_LEVEL -> EngineSignal.ProxyFailure(ConnectionError(code, detail))
            unanswered -> EngineSignal.Unanswered(ConnectionError(code, detail))
            else -> EngineSignal.ConnectionFailure(code)
        }
    }
}

private val TLS_CODES = setOf("TLS_UNTRUSTED", "TLS_HOSTNAME", "TLS_EXPIRED", "TLS_HANDSHAKE")

/** The engine's counters as Home's diagnostics (ADR-0011): counts only, no destinations. */
internal fun EngineStats.toDiagnostics(): RoutingDiagnostics {
    fun Map<String, Long>.count(key: String) = this[key] ?: 0L
    val tlsFailures = TLS_CODES.sumOf { failures.count(it) }
    // The engine keeps the last proxy-level failure; when a later one of another kind replaced a
    // TLS failure, the TLS code seen most often stands for them.
    val lastTls = lastProxyError?.code?.takeIf { it in TLS_CODES }
        ?: TLS_CODES.filter { failures.count(it) > 0 }.maxByOrNull { failures.count(it) }
    return RoutingDiagnostics(
        connectionsOpened = tcpOpened,
        // Different names during this connection: dns["mapped"] counts every lookup, repeats too, and the pool behind
        // fakeDnsMappings is shared by every engine in the process.
        namesForProxy = dnsNames.toLong(),
        ipv6LookupsAnsweredEmpty = dns.count("nodata"),
        tls = TlsProblems(failures = tlsFailures, lastCode = lastTls.takeIf { tlsFailures > 0 }),
        dns = DnsProblems(
            proxyNameLookups = failures.count("PROXY_DNS_FAILED"),
            namesNotReached = hostnameFailures.count("TARGET_UNREACHABLE"),
            queriesRefused = dns.count("refused") + dns.count("formerr"),
            lookupsDropped = dns.count("exhausted") + dnsDropped,
            staleAddresses = failures.count("STALE_DNS_MAPPING"),
        ),
        unsupported = UnsupportedTraffic(
            udpBlocked = udpRejected,
            udpTopPorts = udpRejectedPorts.entries
                .mapNotNull { (port, count) -> port.toIntOrNull()?.let { it to count } }
                .sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })
                .take(TOP_PORTS)
                .map { it.first },
            pingsDropped = icmpEchoRequestsDropped,
        ),
        proxyTimeouts = proxyTimeouts,
        connectionsFailed = tcpFailed,
        lastProblem = lastFailure?.let { ConnectionError(it.code, it.detail) },
    )
}

private const val TOP_PORTS = 3
