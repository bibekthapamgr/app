package app.proxyflow.core.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import app.proxyflow.core.model.ConnectionState
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The VPN service (ADR-0010 §8): Android creates it, binds it while a VPN
 * interface exists, and tells it about revocation. It holds no connection
 * logic of its own: it attaches itself to the process's [VpnSession] as the
 * [VpnHost] (establishing interfaces, protecting sockets, Android's view of
 * its VPN network, the foreground notification) and forwards Android's
 * callbacks to it.
 *
 * Started by the session for a connection ([ACTION_CONNECT]), by the
 * notification's actions, or by Android for Always-on VPN
 * ([VpnService.SERVICE_INTERFACE]). Not sticky: after process death the VPN is
 * gone, and the app starts Disconnected, which is then true.
 */
class ProxyFlowVpnService :
    VpnService(),
    VpnHost {
    private lateinit var runtime: VpnRuntime
    private lateinit var notifications: VpnNotifications
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var lastStartId = 0

    // The status notification. The first status of a connection puts the service in the foreground
    // at once; later ones are spaced out, because Android drops a package's notification updates
    // beyond about five a second, which could leave a stale state showing. The last one always
    // gets through. Every startForeground and stopForeground happens under [statusLock], so they
    // reach Android in order, and a stop that arrives late never removes a newer connection's
    // notification.
    private val statusLock = Any()
    private var foreground = false
    private var statusGeneration = 0L
    private var pendingStatus: ConnectionState? = null
    private var statusShownAt = 0L
    private val postStatus = Runnable { postPendingStatus() }

    override fun onCreate() {
        super.onCreate()
        runtime = (application as VpnRuntime.Provider).vpnRuntime
        notifications = VpnNotifications(this)
        notifications.createChannels()
        liveInstances.incrementAndGet()
        runtime.log.add("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        runtime.log.add("service start $startId ${intent?.action ?: "(no action)"}")
        val session = runtime.session
        session.attach(this, alwaysOn = intent?.action == SERVICE_INTERFACE)
        when (intent?.action) {
            ACTION_DISCONNECT -> session.disconnect()
            ACTION_RESTART -> session.restart()
        }
        return START_NOT_STICKY
    }

    // Not super: its stopSelf() would race the session, which stops the service after closing the interface.
    override fun onRevoke() {
        runtime.log.add("onRevoke")
        runtime.session.revoked(this)
    }

    override fun onDestroy() {
        runtime.log.add("service destroyed")
        main.removeCallbacks(postStatus)
        runtime.session.detached(this)
        liveInstances.decrementAndGet()
        super.onDestroy()
    }

    override fun hasConsent(): Boolean = VpnService.prepare(this) == null

    override fun establish(config: TunConfig): TunInterface? {
        val builder = Builder()
            // The app's name, not the profile's: Android shows it in its VPN settings and dialogs.
            .setSession(getString(R.string.vpn_session_name))
            .addAddress(config.address4, TunConfig.PREFIX4)
            .addAddress(config.address6, TunConfig.PREFIX6)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer(config.dnsServer)
            .setMtu(config.mtu)
            // ProxyFlow's own sockets (the engine's, the pre-flight check's) bypass the VPN; they are protected too.
            .addDisallowedApplication(packageName)
            // The default network until M7's network monitor names it; metered when it is.
            .setUnderlyingNetworks(null)
            .setMetered(false)
            .setBlocking(false) // the engine polls the descriptor
        notifications.openApp()?.let { builder.setConfigureIntent(it) }
        // Never allowBypass(): apps must not be able to leave the VPN.
        val descriptor = builder.establish() ?: return null
        establishedInterfaces.incrementAndGet()
        return ParcelTun(descriptor)
    }

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun vpnNetworks(): List<VpnNetwork> {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return try {
            // allNetworks: callbacks are never told about a VPN that excludes the caller, as ProxyFlow's does.
            @Suppress("DEPRECATION")
            val networks = connectivity.allNetworks
            networks.mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@mapNotNull null
                VpnNetwork(
                    id = network.toString(),
                    interfaceName = connectivity.getLinkProperties(network)?.interfaceName,
                    ready = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }
        } catch (e: RuntimeException) {
            emptyList() // SecurityException without ACCESS_NETWORK_STATE, or the system server gone: nothing is known
        }
    }

    override fun socketGuard(): SocketGuard = ShutdownGuard()

    override fun showStatus(state: ConnectionState): Boolean = synchronized(statusLock) {
        if (foreground) {
            pendingStatus = state
            main.removeCallbacks(postStatus)
            main.postDelayed(postStatus, (statusShownAt + STATUS_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            return true
        }
        // A connection's first status: Android decides now whether the service may run in the foreground.
        val shown = startForegroundWith(state)
        if (shown) {
            foreground = true
            statusGeneration++
            statusShownAt = SystemClock.elapsedRealtime()
        }
        shown
    }

    private fun postPendingStatus() {
        synchronized(statusLock) {
            val next = pendingStatus ?: return
            if (!foreground) return
            pendingStatus = null
            statusShownAt = SystemClock.elapsedRealtime()
            startForegroundWith(next)
        }
    }

    private fun startForegroundWith(state: ConnectionState): Boolean = try {
        // systemExempted (Android 14+): for VPN apps the user has allowed (ADR-0007). Before 14
        // there are no type rules for it; the manifest's type is used.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        ServiceCompat.startForeground(this, STATUS_NOTIFICATION_ID, notifications.status(state), type)
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (12+), ForegroundServiceTypeNotAllowedException (14+)
        runtime.log.add("foreground refused: ${e.javaClass.simpleName}")
        false
    } catch (e: SecurityException) {
        runtime.log.add("foreground refused: ${e.javaClass.simpleName}")
        false
    }

    override fun notifyProblem(state: ConnectionState) = notifications.problem(state)

    override fun clearProblem() = notifications.clearProblem()

    override fun stop() {
        val startId = lastStartId
        val generation = synchronized(statusLock) {
            foreground = false
            pendingStatus = null
            statusGeneration
        }
        main.removeCallbacks(postStatus)
        main.post {
            synchronized(statusLock) {
                // A newer connection may have put the service in the foreground since: its notification stays.
                val newer = foreground || statusGeneration != generation
                if (!newer) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            val stopped = stopSelfResult(startId)
            runtime.log.add("service stop $startId: ${if (stopped) "stopping" else "a newer start keeps it"}")
        }
    }

    /** The interface's descriptor, closed by the session once the engine has stopped. */
    private class ParcelTun(private val descriptor: ParcelFileDescriptor) : TunInterface {
        override val fd: Int = descriptor.fd
        override val name: String? = interfaceName(fd)

        override fun close() {
            runCatching { descriptor.close() }
        }
    }

    companion object {
        const val ACTION_CONNECT = "app.proxyflow.vpn.CONNECT"
        const val ACTION_DISCONNECT = "app.proxyflow.vpn.DISCONNECT"
        const val ACTION_RESTART = "app.proxyflow.vpn.RESTART"

        /** The foreground notification, on [STATUS_CHANNEL]. */
        const val STATUS_NOTIFICATION_ID = 1

        /** The revocation alert, on [PROBLEMS_CHANNEL]. */
        const val PROBLEM_NOTIFICATION_ID = 2
        const val STATUS_CHANNEL = "connection_status"
        const val PROBLEMS_CHANNEL = "connection_problems"

        /** At most about three status updates a second, well inside Android's limit. */
        private const val STATUS_INTERVAL_MS = 300L

        /** Service instances alive in this process: never more than one (device lifecycle tests). */
        @VisibleForTesting
        val liveInstances = AtomicInteger()

        /** VPN interfaces established in this process so far (device tests: a failed check establishes none). */
        @VisibleForTesting
        val establishedInterfaces = AtomicInteger()

        internal fun intent(context: Context, action: String): Intent = Intent(context, ProxyFlowVpnService::class.java).setAction(action)

        /** A notification action that reaches the service (it runs in the foreground then, so Android delivers it). */
        internal fun pendingAction(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(context, requestCode, intent(context, action), PendingIntent.FLAG_IMMUTABLE)

        /** The TUN interface's name, from the kernel's description of the descriptor (`iff:` in /proc/self/fdinfo). */
        @VisibleForTesting
        fun interfaceName(fd: Int): String? = try {
            File("/proc/self/fdinfo/$fd").useLines { lines -> lines.firstOrNull { it.startsWith("iff:") } }
                ?.substringAfter(':')
                ?.trim()
                ?.ifEmpty { null }
        } catch (e: IOException) {
            null
        }
    }
}
