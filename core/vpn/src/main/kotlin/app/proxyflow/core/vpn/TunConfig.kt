package app.proxyflow.core.vpn

import app.proxyflow.core.engine.NetworkPlan

/**
 * The VPN interface (docs/m1/06-architecture.md §4, ADR-0010 §3): the engine's
 * own addresses, both families routed in whole, the engine's fake-IP DNS
 * server, and MTU 9000 (lab E13; devices are measured in M10, with 1500 as
 * the fallback). The engine is started with the same MTU.
 */
data class TunConfig(val address4: String, val address6: String, val dnsServer: String, val mtu: Int = VPN_MTU) {
    companion object {
        const val VPN_MTU = 9000
        const val PREFIX4 = 32
        const val PREFIX6 = 128

        /** The addresses the engine answers on (pfengine.NetworkPlan), so the two can never disagree. */
        fun from(plan: NetworkPlan) = TunConfig(address4 = plan.tunAddress4, address6 = plan.tunAddress6, dnsServer = plan.dnsServer4)
    }
}

/** An established VPN interface: the descriptor the engine reads, owned (and closed) by the session. */
interface TunInterface {
    val fd: Int

    /** The interface's name (`tun0`…), read from the descriptor; null when it can't be read. */
    val name: String?

    /**
     * Drops the packets apps sent while no engine read the interface (traffic held during a
     * switch or while blocked), so the next engine never delivers them later; apps send
     * again. Only while no engine runs. Returns how many were dropped.
     */
    fun drain(): Int

    /** Closes the descriptor; Android then removes the interface. Safe to call more than once. */
    fun close()
}
