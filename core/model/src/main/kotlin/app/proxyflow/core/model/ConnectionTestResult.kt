package app.proxyflow.core.model

/**
 * What testing a profile's connection found (boards D5–D7). Only a real test
 * through the connection engine may produce [Passed]; nothing else ever does.
 */
sealed interface ConnectionTestResult {
    /** No test was made: this build has no connection engine yet (it arrives in M4), and nothing was sent. */
    data object Unavailable : ConnectionTestResult

    /** The proxy accepted the sign-in and opened a test connection through it. */
    data class Passed(val latencyMillis: Long) : ConnectionTestResult

    /** The test failed; [code] is from the error catalogue (docs/m1/04 §4), for example `AUTH_FAILED`. */
    data class Failed(val code: String) : ConnectionTestResult
}
