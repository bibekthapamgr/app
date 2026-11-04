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
 * is told is the network's id, for comparison, and its kind for the log ("wifi"), nothing more.
 */
internal class NetworkMonitor(context: Context) {
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** Starts reporting to [report] (null: no network); false when Android would not let it watch. */
    fun start(report: (VpnSession.NetworkInfo?) -> Unit): Boolean {
        val manager = connectivity ?: return false
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Android calls one callback at a time, on one thread.
            private var current: Network? = null

            // Follows onAvailable at once (API 26+), and again whenever the network's capabilities change.
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || network == current) return
                current = network
                report(VpnSession.NetworkInfo(id = network.toString(), transport = kindOf(capabilities)))
            }

            // A default network that was replaced is not lost: onCapabilitiesChanged already named the next one.
            override fun onLost(network: Network) {
                if (network != current) return
                current = null
                report(null)
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
