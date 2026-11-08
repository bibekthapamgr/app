package app.proxyflow.core.model

/**
 * What testing a profile's connection found (boards D5–D7): the same check connecting makes first, a tunnel through the
 * proxy opened and closed again (ADR-0010 §4, ADR-0014). Only a real test through the connection engine may produce
 * [Passed]; nothing else ever does.
 */
sealed interface ConnectionTestResult {
    /** The proxy accepted the sign-in and opened a test connection through it, in [latencyMillis]. */
    data class Passed(val latencyMillis: Long) : ConnectionTestResult

    /**
     * The test failed with [error]: the catalogue's code (docs/m1/04 §4), for example `AUTH_FAILED`, and its detail.
     * [fromEngine] is true when the engine was asked — the proxy's answer, or the engine's own failure (`INTERNAL`) — and
     * false for a failure found before asking it — no password to send, a sign-in that can't be encoded, a Keystore that
     * failed, a test that couldn't start — which describes the form or the phone, never the proxy. No default: every
     * failure says which it is.
     */
    data class Failed(val error: ConnectionError, val fromEngine: Boolean) : ConnectionTestResult {
        val code: String get() = error.code
    }

    /** The result as a profile keeps it (board C2's "Works · 182 ms · tested 2 min ago"), dated [atMillis]. */
    fun asProfileTest(atMillis: Long): ProfileTest = when (this) {
        is Passed -> ProfileTest(atMillis, code = null, latencyMillis = latencyMillis)
        is Failed -> ProfileTest(atMillis, code = error.code, latencyMillis = null)
    }
}

/**
 * A profile's last connection test, kept with it until its server or sign-in changes (ADR-0014).
 *
 * @param code the catalogue's code when the test failed; null when it passed.
 * @param latencyMillis how long the proxy took to open the test connection, when it passed.
 */
data class ProfileTest(val atMillis: Long, val code: String?, val latencyMillis: Long?) {
    val passed: Boolean get() = code == null
}
