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
     * Starts the engine on [tun], which stays owned by the caller. [onFailure] is
     * called once, from any thread, if the packet loop ends without [stop]
     * (catalogue code `ENGINE_STOPPED`).
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
        onFailure: (ConnectionError) -> Unit,
    )

    /** Stops the engine and waits for it; it never closes the TUN descriptor. Safe at any time. */
    fun stop()

    /**
     * The running engine's counters as Home's diagnostics (ADR-0011), or null when no engine
     * runs or they can't be read. Unlike the other calls, safe from any thread at any time.
     */
    fun diagnostics(): RoutingDiagnostics? = null
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
        onFailure: (ConnectionError) -> Unit,
    ) {
        val platform = AndroidEnginePlatform(
            protector = protect,
            trustManager = trust(),
            onEvent = { event ->
                if (event.type == ENGINE_FAILED) {
                    onFailure(ConnectionError(event.code ?: ConnectionError.ENGINE_STOPPED, event.detail))
                }
            },
        )
        // The engine's MTU must be the interface's: gVisor reads whole packets of up to that size.
        engine.start(tun.fd, profile.toEngineProfile().copy(mtu = config.mtu), secret, platform)
    }

    override fun stop() = engine.stop()

    override fun diagnostics(): RoutingDiagnostics? =
        runCatching { engine.stats() }.getOrNull()?.takeIf { it.state == EngineState.Running }?.toDiagnostics()

    private companion object {
        /** tunnel.EvEngineFailed: the packet loop ended without Stop. */
        const val ENGINE_FAILED = "engine_failed"
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
        namesForProxy = fakeDnsMappings.toLong(), // different names; dns["mapped"] counts every lookup, repeats too
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
