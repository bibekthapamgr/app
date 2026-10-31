package app.proxyflow.core.vpn

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus

/**
 * The connection notifications (board B10, ADR-0010 §9). "Connection status"
 * (low importance, silent) carries the foreground notification: Connecting,
 * Reconnecting, Connected (with its timer) or Blocked. "Connection problems"
 * (high importance) alerts when Android revoked the VPN. Tapping opens the
 * app; lock screens get a version without the profile name (threat model I-11).
 */
internal class VpnNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(STATUS_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.vpn_channel_status))
                    .setDescription(context.getString(R.string.vpn_channel_status_description))
                    .setShowBadge(false)
                    .build(),
                NotificationChannelCompat.Builder(PROBLEMS_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.vpn_channel_problems))
                    .setDescription(context.getString(R.string.vpn_channel_problems_description))
                    .build(),
            ),
        )
    }

    /** The foreground notification for [state]. */
    fun status(state: ConnectionState): Notification {
        val title = context.getString(statusTitle(state.status))
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_proxyflow)
            .setContentTitle(title)
            .setContentText(statusText(state))
            .setSubText(state.profileName)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(STATUS_CHANNEL, title))
        openApp()?.let { builder.setContentIntent(it) }
        val since = state.connectedSinceMillis
        if (state.status == ConnectionStatus.Connected && since != null) {
            builder.setWhen(since).setShowWhen(true).setUsesChronometer(true)
        } else {
            builder.setShowWhen(false)
        }
        val disconnect = ProxyFlowVpnService.pendingAction(context, ProxyFlowVpnService.ACTION_DISCONNECT, REQUEST_DISCONNECT)
        when (state.status) {
            ConnectionStatus.Connecting -> builder.addAction(0, context.getString(R.string.vpn_action_cancel), disconnect)
            ConnectionStatus.Blocked -> {
                val restart = ProxyFlowVpnService.pendingAction(context, ProxyFlowVpnService.ACTION_RESTART, REQUEST_RESTART)
                builder.addAction(0, context.getString(R.string.vpn_action_restart), restart)
                builder.addAction(0, context.getString(R.string.vpn_action_disconnect), disconnect)
            }
            else -> builder.addAction(0, context.getString(R.string.vpn_action_disconnect), disconnect)
        }
        return builder.build()
    }

    /** The alert after Android revoked the VPN (board B6d). Without the notification permission there is none; the app shows B6d. */
    fun problem(state: ConnectionState) {
        if (state.error?.code != ConnectionError.VPN_REVOKED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val title = context.getString(R.string.vpn_notification_revoked)
        val text = context.getString(R.string.vpn_notification_revoked_text)
        val builder = NotificationCompat.Builder(context, PROBLEMS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_proxyflow)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        openApp()?.let { builder.setContentIntent(it) }
        try {
            manager.notify(PROBLEM_ID, builder.build())
        } catch (e: SecurityException) {
            // The permission was withdrawn after the check; the app still shows B6d.
        }
    }

    fun clearProblem() = manager.cancel(PROBLEM_ID)

    /** Opens the app where the user left it, as its launcher icon does. */
    fun openApp(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, REQUEST_OPEN, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun statusTitle(status: ConnectionStatus): Int = when (status) {
        ConnectionStatus.Connecting -> R.string.vpn_notification_connecting
        ConnectionStatus.Reconnecting -> R.string.vpn_notification_reconnecting
        ConnectionStatus.Connected -> R.string.vpn_notification_connected
        ConnectionStatus.Blocked, ConnectionStatus.Offline -> R.string.vpn_notification_blocked
        ConnectionStatus.Disconnected -> R.string.vpn_notification_disconnected
    }

    private fun statusText(state: ConnectionState): String = context.getString(
        when (state.status) {
            ConnectionStatus.Connecting ->
                if (state.step == ConnectStep.StartingProtection) {
                    R.string.vpn_notification_starting_protection
                } else {
                    R.string.vpn_notification_checking_proxy
                }
            ConnectionStatus.Reconnecting -> R.string.vpn_notification_reconnecting_text
            ConnectionStatus.Connected -> R.string.vpn_notification_connected_text
            ConnectionStatus.Blocked, ConnectionStatus.Offline ->
                if (state.error?.code == ConnectionError.ENGINE_STOPPED) {
                    R.string.vpn_notification_blocked_engine
                } else {
                    R.string.vpn_notification_blocked_text
                }
            ConnectionStatus.Disconnected -> R.string.vpn_notification_disconnected_text
        },
    )

    private fun publicVersion(channel: String, title: String): Notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_proxyflow)
        .setContentTitle(title)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val STATUS_CHANNEL = ProxyFlowVpnService.STATUS_CHANNEL
        const val PROBLEMS_CHANNEL = ProxyFlowVpnService.PROBLEMS_CHANNEL
        const val STATUS_ID = ProxyFlowVpnService.STATUS_NOTIFICATION_ID
        const val PROBLEM_ID = ProxyFlowVpnService.PROBLEM_NOTIFICATION_ID
        private const val REQUEST_OPEN = 0
        private const val REQUEST_DISCONNECT = 1
        private const val REQUEST_RESTART = 2
    }
}
