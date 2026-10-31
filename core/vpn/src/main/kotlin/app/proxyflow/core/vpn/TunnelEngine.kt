package app.proxyflow.core.vpn

import app.proxyflow.core.engine.AndroidEnginePlatform
import app.proxyflow.core.engine.EngineSecret
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.engine.toEngineProfile
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ProxyProfile

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
     * @throws app.proxyflow.core.engine.EngineException when the engine cannot start.
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
}

/**
 * The pre-flight check's answer. It passes when the tunnel opened, and also when
 * the proxy answered for the destination itself ([TARGET_LEVEL]): the proxy is
 * reachable and speaks its protocol, and for SOCKS5 (and HTTP answers other than
 * 403) it accepted the sign-in, which comes first (ADR-0010 §4).
 */
data class PreflightAnswer(val ok: Boolean, val code: String? = null, val detail: String? = null) {
    val passes: Boolean get() = ok || code in TARGET_LEVEL

    /** Why the check failed, for the screens: the engine's code, or INTERNAL when it gave none. */
    val error: ConnectionError get() = ConnectionError(code ?: ConnectionError.INTERNAL, detail)

    companion object {
        val TARGET_LEVEL = setOf(
            "TARGET_NOT_ALLOWED",
            "TARGET_UNREACHABLE",
            "TARGET_REFUSED",
            "TARGET_UNSUPPORTED",
            "PROXY_GENERAL_FAILURE",
        )
    }
}

/** [TunnelEngine] on the real engine (libgojni.so, loaded on first use). */
class ProxyTunnelEngine(private val engine: ProxyEngine = ProxyEngine()) : TunnelEngine {
    override fun tunConfig(): TunConfig = TunConfig.from(engine.networkPlan())

    override fun probe(
        profile: ProxyProfile,
        secret: EngineSecret?,
        target: String,
        timeoutMillis: Long,
        protect: (Int) -> Boolean,
    ): PreflightAnswer {
        val result = engine.probe(profile.toEngineProfile(), secret, target, timeoutMillis, AndroidEnginePlatform(protect))
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

    private companion object {
        /** tunnel.EvEngineFailed: the packet loop ended without Stop. */
        const val ENGINE_FAILED = "engine_failed"
    }
}
