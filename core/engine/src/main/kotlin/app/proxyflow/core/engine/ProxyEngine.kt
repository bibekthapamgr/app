package app.proxyflow.core.engine

import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.engine.go.pfengine.Platform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The Kotlin face of the packet engine. One engine runs at a time per
 * process (the VPN); [probe] and [selfTest] run beside it without a TUN.
 *
 * Every call that takes an [EngineSecret] consumes it: the bytes are zeroed
 * before the call returns, whatever happens. Errors are [EngineException]s
 * with a catalogue code; no message contains a secret.
 */
class ProxyEngine(private val native: EngineNative = GoEngineNative) {

    /**
     * Starts the engine on the TUN descriptor, which stays owned by the
     * caller ([stop] never closes it).
     *
     * @throws EngineException when the engine cannot start (code INTERNAL).
     */
    fun start(tunFd: Int, profile: EngineProfile, secret: EngineSecret?, platform: Platform) {
        val bytes = secret?.take() ?: ByteArray(0)
        try {
            native.start(tunFd.toLong(), EngineJson.config(profile), bytes, platform)
        } catch (e: Exception) {
            throw EngineException.from(e)
        } finally {
            bytes.fill(0)
        }
    }

    /** Stops the engine and waits for it; safe to call at any time. */
    fun stop() = native.stop()

    fun status(): EngineStatus = EngineJson.decode(EngineStatus.serializer(), native.status())

    fun stats(): EngineStats = EngineJson.decode(EngineStats.serializer(), native.stats())

    fun flows(): List<EngineFlow> = EngineJson.decode(ListSerializer(EngineFlow.serializer()), native.flows())

    /** Aborts every connection (apps reconnect) after an underlying-network change. */
    fun resetFlows(): Int = native.resetFlows().toInt()

    /**
     * Opens one tunnel through the proxy to [target] ("host:port") and closes
     * it: the connection test and the pre-flight check before the VPN starts.
     */
    fun probe(profile: EngineProfile, secret: EngineSecret?, target: String, timeoutMillis: Long, platform: Platform): ProbeResult {
        val bytes = secret?.take() ?: ByteArray(0)
        try {
            return EngineJson.decode(
                ProbeResult.serializer(),
                native.probe(EngineJson.config(profile), bytes, target, timeoutMillis, platform),
            )
        } finally {
            bytes.fill(0)
        }
    }

    /**
     * Runs the whole packet engine on a socketpair with an in-process client
     * stack: fake DNS, a TCP connection to [target] through the proxy,
     * [payload] out and [expect] bytes back, UDP refused. A diagnostic that
     * needs no VPN permission.
     */
    fun selfTest(
        profile: EngineProfile,
        secret: EngineSecret?,
        target: String,
        payload: ByteArray,
        expect: Int,
        timeoutMillis: Long,
        platform: Platform,
    ): SelfTestResult {
        val bytes = secret?.take() ?: ByteArray(0)
        try {
            val json = native.selfTest(EngineJson.config(profile), bytes, target, payload, expect.toLong(), timeoutMillis, platform)
            return EngineJson.decode(SelfTestResult.serializer(), json)
        } finally {
            bytes.fill(0)
        }
    }

    fun networkPlan(): NetworkPlan = EngineJson.decode(NetworkPlan.serializer(), native.networkPlan())

    fun version(): String = native.version()
}

/** JSON in and out of the engine (snake_case keys, as the Go side defines them). */
internal object EngineJson {
    private val json = Json {
        ignoreUnknownKeys = true // the engine may report more than this app version reads
        explicitNulls = false
        encodeDefaults = false
    }

    /** The profile as pfengine's config JSON; the engine rejects unknown keys, so only known ones go in. */
    fun config(profile: EngineProfile): String = json.encodeToString(
        ConfigJson.serializer(),
        ConfigJson(
            protocol = when (profile.protocol) {
                ProxyProtocol.SOCKS5 -> "SOCKS5"
                ProxyProtocol.HTTP -> "HTTP"
            },
            host = profile.host,
            port = profile.port,
            tls = profile.tls,
            tlsServerName = profile.tlsServerName?.takeIf { it.isNotBlank() },
            mtu = profile.mtu,
            logDestinations = profile.logDestinations,
            dialTimeoutMillis = profile.dialTimeoutMillis,
            handshakeTimeoutMillis = profile.handshakeTimeoutMillis,
            idleTimeoutMillis = profile.idleTimeoutMillis,
            maxConnections = profile.maxConnections,
        ),
    )

    fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T = json.decodeFromString(serializer, text)

    fun event(text: String): EngineEvent? = runCatching { json.decodeFromString(EngineEvent.serializer(), text) }.getOrNull()

    @Serializable
    private data class ConfigJson(
        val protocol: String,
        val host: String,
        val port: Int,
        val tls: Boolean = false,
        @SerialName("tls_server_name") val tlsServerName: String? = null,
        val mtu: Int? = null,
        @SerialName("log_destinations") val logDestinations: Boolean = false,
        @SerialName("dial_timeout_ms") val dialTimeoutMillis: Long? = null,
        @SerialName("handshake_timeout_ms") val handshakeTimeoutMillis: Long? = null,
        @SerialName("idle_timeout_ms") val idleTimeoutMillis: Long? = null,
        @SerialName("max_conns") val maxConnections: Int? = null,
    )
}
