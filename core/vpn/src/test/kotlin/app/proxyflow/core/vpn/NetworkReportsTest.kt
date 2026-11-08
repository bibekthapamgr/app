package app.proxyflow.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the network monitor tells the session (ADR-0012, M10): another network, a sign-in page appearing or going, a loss. */
class NetworkReportsTest {
    private val reports = NetworkReports()

    @Test
    fun anotherNetworkIsReportedOnceAndCapabilitiesThatChangeNothingElseAreNot() {
        assertEquals(VpnSession.NetworkInfo("100", "wifi"), reports.capabilities("100", vpn = false, transport = "wifi", signIn = false))
        assertNull("the same network, its bandwidth changed", reports.capabilities("100", vpn = false, transport = "wifi", signIn = false))
        assertEquals(
            VpnSession.NetworkInfo("101", "cellular"),
            reports.capabilities("101", vpn = false, transport = "cellular", signIn = false),
        )
    }

    @Test
    fun theSameNetworkAskingForASignInAndSignedInIsReportedEachTime() {
        reports.capabilities("100", vpn = false, transport = "wifi", signIn = false)
        assertEquals(
            VpnSession.NetworkInfo("100", "wifi", signIn = true),
            reports.capabilities("100", vpn = false, transport = "wifi", signIn = true),
        )
        assertNull(reports.capabilities("100", vpn = false, transport = "wifi", signIn = true))
        assertEquals(
            VpnSession.NetworkInfo("100", "wifi", signIn = false),
            reports.capabilities("100", vpn = false, transport = "wifi", signIn = false),
        )
    }

    @Test
    fun aVpnNetworkIsNeverReportedNorTakenForTheCurrentOne() {
        reports.capabilities("100", vpn = false, transport = "wifi", signIn = false)
        assertNull(reports.capabilities("200", vpn = true, transport = "other", signIn = false))
        assertNull("still the same network", reports.capabilities("100", vpn = false, transport = "wifi", signIn = false))
        assertFalse("a VPN lost is not the phone's network lost", reports.lost("200"))
    }

    @Test
    fun onlyTheCurrentNetworkLostMeansNoNetworkAndTheNextReportComesAgain() {
        reports.capabilities("100", vpn = false, transport = "wifi", signIn = true)
        reports.capabilities("101", vpn = false, transport = "cellular", signIn = false)
        assertFalse("replaced, not lost: the next one was named already", reports.lost("100"))
        assertTrue(reports.lost("101"))
        assertFalse(reports.lost("101"))
        assertEquals(
            VpnSession.NetworkInfo("101", "cellular"),
            reports.capabilities("101", vpn = false, transport = "cellular", signIn = false),
        )
    }
}
