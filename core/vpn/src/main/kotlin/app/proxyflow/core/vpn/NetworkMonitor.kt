package app.proxyflow.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * The phone's default network as Android reports it to ProxyFlow (ADR-0012), told to the session
 * whenever it changes. ProxyFlow's own UID is outside its VPN (`addDisallowedApplication`), so its
 * default network is the one the VPN's traffic leaves on — Wi-Fi, mobile data, Ethernet — and
 * never the VPN itself; a VPN network is ignored all the same.
 *
 * Registered once for the life of the process, so the session always knows the current network,
 * also when a connection starts: it acts only on changes while connected or reconnecting. What it
 * is told is the network's id, for comparison, its kind for the log ("wifi"), and whether Android
 * found a sign-in page on it (a captive portal, M10), nothing more. What is reported when is
 * [NetworkReports]'s.
 */
internal class NetworkMonitor(context: Context) {
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** Starts reporting to [report] (null: no network); false when Android would not let it watch. */
    fun start(report: (VpnSession.NetworkInfo?) -> Unit): Boolean {
        val manager = connectivity ?: return false
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Android calls one callback at a time, on one thread.
            private val reports = NetworkReports()

            // Follows onAvailable at once (API 26+), and again whenever the network's capabilities change.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                reports.capabilities(
                    network = network.toString(),
                    vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    transport = kindOf(capabilities),
                    signIn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                )?.let(report)
            }

            override fun onLost(network: Network) {
                if (reports.lost(network.toString())) report(null)
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            true
        } catch (e: RuntimeException) {
            false // SecurityException, or too many callbacks registered: the session then never acts on a network change
        }
    }

    private fun kindOf(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
}
