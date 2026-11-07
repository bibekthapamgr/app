package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionError
import kotlin.math.roundToLong

/**
 * How ProxyFlow reconnects on its own (M7, ADR-0012; docs/m1/03 J6): attempt n waits
 * [delaysMillis]`[n - 1]`, give or take [jitter] of it, and there are as many attempts as
 * delays — then it stops, with traffic still held, until the user retries or stops. The
 * default backs off 1, 2, 4, 8, 16 and then 30 s three times: about two minutes in all.
 */
data class RetryPolicy(
    val delaysMillis: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000),
    val jitter: Double = 0.2,
) {
    init {
        require(delaysMillis.isNotEmpty() && delaysMillis.all { it >= 0 }) { "delays must be non-negative" }
        require(jitter in 0.0..1.0) { "jitter is a fraction of the delay" }
    }

    val maxAttempts: Int get() = delaysMillis.size

    /**
     * The wait before attempt [attempt] (1-based): its delay, moved by up to [jitter] of it either
     * way by [random] (0 ≤ random < 1), so phones that lost the same proxy don't all come back at once.
     */
    fun delayBefore(attempt: Int, random: Double): Long {
        require(attempt in 1..maxAttempts) { "attempt $attempt of $maxAttempts" }
        val base = delaysMillis[attempt - 1]
        val spread = base * jitter * (random.coerceIn(0.0, 1.0) * 2 - 1)
        return (base + spread).roundToLong().coerceAtLeast(0)
    }

    companion object {
        /** Codes an attempt may fail with and still be worth another (the catalogue's: [ConnectionError.MAY_PASS]). */
        val RECOVERABLE = ConnectionError.MAY_PASS

        /**
         * Answers no retry changes, so one of them stops a connected session at once (docs/m1/04 §4:
         * an auth or certificate failure once): the user has to fix the profile or the proxy.
         */
        val BLOCK_AT_ONCE = setOf("AUTH_REQUIRED", "AUTH_FAILED", "TLS_UNTRUSTED", "TLS_HOSTNAME", "TLS_EXPIRED")

        /** The proxy-level codes of the escalation rule (engine/adapters/errors.go `IsProxyLevel`). */
        val PROXY_LEVEL = setOf(
            "PROXY_UNREACHABLE", "PROXY_DNS_FAILED", "AUTH_REQUIRED", "AUTH_FAILED", "TLS_UNTRUSTED", "TLS_HOSTNAME", "TLS_EXPIRED",
            "TLS_HANDSHAKE", "PROTOCOL_ERROR", "HANDSHAKE_TIMEOUT",
        )

        /** Proxy-level failures in a row that make a connected session reconnect (docs/m1/04 §4). */
        const val ESCALATION_FAILURES = 3

        /**
         * A SOCKS5 proxy's connection requests left unanswered in a row that make a connected session reconnect (M8),
         * when they went to at least [UNANSWERED_SITES] different sites: one slow site never does.
         */
        const val UNANSWERED_ESCALATION = 3L
        const val UNANSWERED_SITES = 2

        fun recoverable(code: String?): Boolean = code in RECOVERABLE
    }
}
