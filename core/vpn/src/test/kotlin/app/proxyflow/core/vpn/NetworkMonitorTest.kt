package app.proxyflow.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

/**
 * The network monitor with Android's own classes (Robolectric): what it reads from the capabilities Android reports —
 * the transport, a VPN, and the sign-in page flag (`NET_CAPABILITY_CAPTIVE_PORTAL`), which no CI emulator has — is what
 * reaches the session (M10). [NetworkReportsTest] covers which reports pass; this, that Android's flag is the one read.
 */
@RunWith(AndroidJUnit4::class)
class NetworkMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reports = mutableListOf<VpnSession.NetworkInfo?>()
    private lateinit var callback: ConnectivityManager.NetworkCallback

    @Before
    fun start() {
        assertTrue(NetworkMonitor(context).start { reports += it })
        callback = shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.single()
    }

    @Test
    fun androidsSignInPageFlagReachesTheSession() {
        val wifi = ShadowNetwork.newInstance(101)
        callback.onCapabilitiesChanged(
            wifi,
            capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
        )
        // Another change on the same network that leaves the flag as it was says nothing new.
        callback.onCapabilitiesChanged(
            wifi,
            capabilities(
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
            ),
        )
        // Signed in: Android validates the network and the flag is gone.
        callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.NET_CAPABILITY_VALIDATED))

        assertEquals(
            listOf(VpnSession.NetworkInfo("101", "wifi", signIn = true), VpnSession.NetworkInfo("101", "wifi", signIn = false)),
            reports,
        )
    }

    @Test
    fun aVpnIsNeverThePhonesNetworkAndEachKindIsNamed() {
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(200), capabilities(NetworkCapabilities.TRANSPORT_VPN))
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(201), capabilities(NetworkCapabilities.TRANSPORT_CELLULAR))
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(202), capabilities(NetworkCapabilities.TRANSPORT_ETHERNET))
        callback.onCapabilitiesChanged(ShadowNetwork.newInstance(203), capabilities(NetworkCapabilities.TRANSPORT_BLUETOOTH))

        assertEquals(listOf("201", "202", "203"), reports.map { it?.id })
        assertEquals(listOf("cellular", "ethernet", "other"), reports.map { it?.transport })
        assertEquals(listOf(false, false, false), reports.map { it?.signIn })
    }

    @Test
    fun losingThePhonesNetworkReportsNone() {
        val wifi = ShadowNetwork.newInstance(101)
        callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
        callback.onLost(ShadowNetwork.newInstance(102)) // a network that was replaced already
        callback.onLost(wifi)

        assertEquals(listOf(VpnSession.NetworkInfo("101", "wifi"), null), reports)
    }

    private fun capabilities(transport: Int, vararg capabilities: Int): NetworkCapabilities =
        NetworkCapabilities.Builder().addTransportType(transport).apply { capabilities.forEach { addCapability(it) } }.build()
}
