package app.proxyflow.core.vpn

/**
 * What [NetworkMonitor] tells the session, from Android's default-network callbacks given as plain values: the default
 * network when it is another one, or when the same one starts or stops asking for a sign-in; nothing for a VPN network or
 * for capabilities that changed nothing else (bandwidth, signal); and no network once the current one is lost — a default
 * network that was replaced is not lost: the next one's capabilities named it already. Not thread-safe: Android calls one
 * callback at a time.
 */
internal class NetworkReports {
    private var current: String? = null
    private var currentSignIn = false

    /** The report for a default network's capabilities, or null when the session has nothing to hear. */
    fun capabilities(network: String, vpn: Boolean, transport: String, signIn: Boolean): VpnSession.NetworkInfo? {
        if (vpn || network == current && signIn == currentSignIn) return null
        current = network
        currentSignIn = signIn
        return VpnSession.NetworkInfo(id = network, transport = transport, signIn = signIn)
    }

    /** True when [network] was the current one: the session then hears there is none. */
    fun lost(network: String): Boolean {
        if (network != current) return false
        current = null
        currentSignIn = false
        return true
    }
}
