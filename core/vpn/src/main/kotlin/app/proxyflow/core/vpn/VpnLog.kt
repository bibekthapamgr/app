package app.proxyflow.core.vpn

import android.os.SystemClock
import android.util.Log

/**
 * The VPN layer's lifecycle — connection steps, service starts and stops, Android's
 * callbacks — for diagnosing it on devices: to logcat under [TAG], and the last
 * [capacity] entries in memory for the device tests' failure reports. Entries name
 * profiles by id only: never a credential, a server name or anything apps send.
 */
class VpnLog(private val capacity: Int = 200) {
    private val entries = ArrayDeque<String>()

    fun add(message: String) {
        Log.i(TAG, message)
        synchronized(entries) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast("${SystemClock.elapsedRealtime()} $message")
        }
    }

    /** The latest entries, oldest first, each led by the time since boot in milliseconds. */
    fun recent(): List<String> = synchronized(entries) { entries.toList() }

    companion object {
        const val TAG = "ProxyFlowVpn"
    }
}
