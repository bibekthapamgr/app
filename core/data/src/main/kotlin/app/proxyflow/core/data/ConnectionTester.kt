package app.proxyflow.core.data

import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProxyProfileInput

/**
 * Tests a profile's connection (boards D5–D7): the engine signs in and opens a test connection through the proxy, the
 * check connecting makes first (ADR-0010 §4), without the VPN and without changing the phone's network (M9, ADR-0014).
 */
interface ConnectionTester {
    /**
     * Tests [input] as typed in the form. With [profileId], a password left empty stands for the saved one, under the same
     * rule as saving ([ProxyProfileInput.sameCredentialTargetAs]); a test that was of the saved profile as saved — its
     * server and sign-in, its saved password — is kept on it, as [testSaved] keeps one.
     */
    suspend fun test(profileId: String?, input: ProxyProfileInput): ConnectionTestResult

    /**
     * Tests the saved profile [profileId] with its saved sign-in, as connecting would, and keeps the result on it while
     * its server and sign-in are unchanged ([ProfileRepository.recordTest]); a failure of this phone's own (`INTERNAL`:
     * the Keystore, the engine) is shown, never kept. Null when the profile is gone, or changed before the test could begin.
     */
    suspend fun testSaved(profileId: String): ConnectionTestResult?
}
