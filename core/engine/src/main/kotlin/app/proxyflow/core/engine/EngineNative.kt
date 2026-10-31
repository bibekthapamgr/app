package app.proxyflow.core.engine

import app.proxyflow.engine.go.pfengine.Pfengine
import app.proxyflow.engine.go.pfengine.Platform

/**
 * The Go engine's exported functions (package pfengine, bound by gomobile).
 * [ProxyEngine] talks only to this interface, so the JVM tests can replace
 * the native library, which only loads on Android.
 */
interface EngineNative {
    fun start(tunFd: Long, configJson: String, secret: ByteArray, platform: Platform)

    fun stop()

    fun status(): String

    fun stats(): String

    fun flows(): String

    fun resetFlows(): Long

    fun probe(configJson: String, secret: ByteArray, target: String, timeoutMs: Long, platform: Platform): String

    fun selfTest(
        configJson: String,
        secret: ByteArray,
        target: String,
        payload: ByteArray,
        expect: Long,
        timeoutMs: Long,
        platform: Platform,
    ): String

    fun networkPlan(): String

    fun version(): String
}

/** The real engine: libgojni.so, loaded on first use. */
object GoEngineNative : EngineNative {
    override fun start(tunFd: Long, configJson: String, secret: ByteArray, platform: Platform) =
        Pfengine.start(tunFd, configJson, secret, platform)

    override fun stop() = Pfengine.stop()

    override fun status(): String = Pfengine.status()

    override fun stats(): String = Pfengine.stats()

    override fun flows(): String = Pfengine.flows()

    override fun resetFlows(): Long = Pfengine.resetFlows()

    override fun probe(configJson: String, secret: ByteArray, target: String, timeoutMs: Long, platform: Platform): String =
        Pfengine.probe(configJson, secret, target, timeoutMs, platform)

    override fun selfTest(
        configJson: String,
        secret: ByteArray,
        target: String,
        payload: ByteArray,
        expect: Long,
        timeoutMs: Long,
        platform: Platform,
    ): String = Pfengine.selfTest(configJson, secret, target, payload, expect, timeoutMs, platform)

    override fun networkPlan(): String = Pfengine.networkPlan()

    override fun version(): String = Pfengine.version()
}
