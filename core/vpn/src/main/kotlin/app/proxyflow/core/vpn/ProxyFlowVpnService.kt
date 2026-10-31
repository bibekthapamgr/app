package app.proxyflow.core.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import app.proxyflow.core.model.ConnectionState
import java.util.concurrent.atomic.AtomicInteger

/**
 * The VPN service (ADR-0010 §8): Android creates it, binds it while a VPN
 * interface exists, and tells it about revocation. It holds no connection
 * logic of its own: it attaches itself to the process's [VpnSession] as the
 * [VpnHost] (establishing interfaces, protecting sockets, the foreground
 * notification) and forwards Android's callbacks to it.
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

    override fun onCreate() {
        super.onCreate()
        runtime = (application as VpnRuntime.Provider).vpnRuntime
        notifications = VpnNotifications(this)
        notifications.createChannels()
        liveInstances.incrementAndGet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val session = runtime.session
        session.attach(this, alwaysOn = intent?.action == SERVICE_INTERFACE)
        when (intent?.action) {
            ACTION_DISCONNECT -> session.disconnect()
            ACTION_RESTART -> session.restart()
        }
        return START_NOT_STICKY
    }

    // Not super: its stopSelf() would race the session, which stops the service after closing the interface.
    override fun onRevoke() = runtime.session.revoked(this)

    override fun onDestroy() {
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
        return ParcelTun(descriptor)
    }

    override fun protectSocket(fd: Int): Boolean = protect(fd)

    override fun showStatus(state: ConnectionState): Boolean = try {
        // systemExempted (Android 14+): for VPN apps the user has allowed (ADR-0007). Before 14
        // there are no type rules for it; the manifest's type is used.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        }
        ServiceCompat.startForeground(this, VpnNotifications.STATUS_ID, notifications.status(state), type)
        true
    } catch (e: IllegalStateException) {
        false // ForegroundServiceStartNotAllowedException (12+), ForegroundServiceTypeNotAllowedException (14+)
    } catch (e: SecurityException) {
        false
    }

    override fun notifyProblem(state: ConnectionState) = notifications.problem(state)

    override fun clearProblem() = notifications.clearProblem()

    override fun stop() {
        val startId = lastStartId
        main.post {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    /** The interface's descriptor, closed by the session once the engine has stopped. */
    private class ParcelTun(private val descriptor: ParcelFileDescriptor) : TunInterface {
        override val fd: Int = descriptor.fd

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

        /** Service instances alive in this process: never more than one (device lifecycle tests). */
        @VisibleForTesting
        val liveInstances = AtomicInteger()

        internal fun intent(context: Context, action: String): Intent = Intent(context, ProxyFlowVpnService::class.java).setAction(action)

        /** A notification action that reaches the service (it runs in the foreground then, so Android delivers it). */
        internal fun pendingAction(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(context, requestCode, intent(context, action), PendingIntent.FLAG_IMMUTABLE)
    }
}
