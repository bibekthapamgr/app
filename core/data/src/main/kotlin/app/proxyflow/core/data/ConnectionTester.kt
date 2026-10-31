package app.proxyflow.core.data

import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProxyProfileInput

/**
 * Tests a profile's connection (boards D5–D7). From M4 the engine implements
 * it with a real sign-in and test connection through the proxy.
 */
interface ConnectionTester {
    /**
     * Tests [input] as typed in the form. With [profileId], a password left empty
     * stands for the saved one, under the same rule as saving
     * ([ProxyProfileInput.sameCredentialTargetAs]).
     */
    suspend fun test(profileId: String?, input: ProxyProfileInput): ConnectionTestResult
}

/** M3: there is no engine to test with, so the answer is always that no test was made. */
class UnavailableConnectionTester : ConnectionTester {
    override suspend fun test(profileId: String?, input: ProxyProfileInput): ConnectionTestResult = ConnectionTestResult.Unavailable
}
