package app.proxyflow.core.vpn

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus

/**
 * The connection notifications (board B10, ADR-0010 §9). "Connection status"
 * (low importance, silent) carries the foreground notification: Connecting,
 * Reconnecting, Connected (with its timer) or Blocked. "Connection problems"
 * (high importance) alerts when Android revoked the VPN. Tapping opens the
 * app. It names no profile, so no lock screen shows one whatever Android's
 * setting for notification content (threat model I-11), and its actions run
 * only on an unlocked phone: Disconnect sends apps to the normal network
 * (M10, ADR-0015 §9).
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
        val content = StatusContent.of(state)
        val title = context.getString(titleOf(content.title))
        val builder = NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_proxyflow)
            .setContentTitle(title)
            .setContentText(
                if (content.text == StatusContent.Text.RECONNECTING_ATTEMPT) {
                    context.getString(textOf(content.text), content.attempt ?: 1, content.maxAttempts ?: 1)
                } else {
                    context.getString(textOf(content.text))
                },
            )
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
        for (action in content.actions) {
            when (action) {
                StatusContent.Action.CANCEL -> builder.addAction(unlockedOnly(R.string.vpn_action_cancel, disconnect()))
                StatusContent.Action.RESTART -> builder.addAction(unlockedOnly(R.string.vpn_action_restart, restart()))
                StatusContent.Action.DISCONNECT -> builder.addAction(unlockedOnly(R.string.vpn_action_disconnect, disconnect()))
            }
        }
        return builder.build()
    }

    /** An action Android runs only once the phone is unlocked: its own flag from Android 12, an activity before (see [action]). */
    private fun unlockedOnly(@StringRes label: Int, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, context.getString(label), intent).setAuthenticationRequired(true).build()

    private fun disconnect(): PendingIntent = action(ProxyFlowVpnService.ACTION_DISCONNECT, REQUEST_DISCONNECT)

    private fun restart(): PendingIntent = action(ProxyFlowVpnService.ACTION_RESTART, REQUEST_RESTART)

    /**
     * The intent of an action: the service's own from Android 12, where the action's flag has Android ask for the unlock
     * first; before it, [NotificationActionActivity], which Android starts only on an unlocked phone.
     */
    private fun action(action: String, requestCode: Int): PendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ProxyFlowVpnService.pendingAction(context, action, requestCode)
    } else {
        NotificationActionActivity.pendingAction(context, action, requestCode)
    }

    /**
     * The alert after Android revoked the VPN (board B6d), or after Android started ProxyFlow for Always-on VPN with no
     * profile chosen (M8). Without the notification permission there is none; the app shows either on Home.
     */
    fun problem(state: ConnectionState) {
        val (titleId, textId) = when (state.error?.code) {
            ConnectionError.VPN_REVOKED -> R.string.vpn_notification_revoked to R.string.vpn_notification_revoked_text
            ConnectionError.ALWAYS_ON_NO_PROFILE -> R.string.vpn_notification_no_profile to R.string.vpn_notification_no_profile_text
            else -> return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val title = context.getString(titleId)
        val text = context.getString(textId)
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
            // The permission was withdrawn after the check; the app still shows it on Home.
        }
    }

    fun clearProblem() = manager.cancel(PROBLEM_ID)

    /** Opens the app where the user left it, as its launcher icon does. */
    fun openApp(): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, REQUEST_OPEN, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun titleOf(title: StatusContent.Title): Int = when (title) {
        StatusContent.Title.CONNECTING -> R.string.vpn_notification_connecting
        StatusContent.Title.RECONNECTING -> R.string.vpn_notification_reconnecting
        StatusContent.Title.CONNECTED -> R.string.vpn_notification_connected
        StatusContent.Title.BLOCKED -> R.string.vpn_notification_blocked
        StatusContent.Title.OFFLINE -> R.string.vpn_notification_offline
        StatusContent.Title.DISCONNECTED -> R.string.vpn_notification_disconnected
    }

    private fun textOf(text: StatusContent.Text): Int = when (text) {
        StatusContent.Text.CHECKING_PROXY -> R.string.vpn_notification_checking_proxy
        StatusContent.Text.STARTING_PROTECTION -> R.string.vpn_notification_starting_protection
        StatusContent.Text.HOLDING -> R.string.vpn_notification_holding
        StatusContent.Text.RECONNECTING -> R.string.vpn_notification_reconnecting_text
        StatusContent.Text.RECONNECTING_ATTEMPT -> R.string.vpn_notification_reconnecting_attempt
        StatusContent.Text.CONNECTED -> R.string.vpn_notification_connected_text
        StatusContent.Text.OFFLINE -> R.string.vpn_notification_offline_text
        StatusContent.Text.ENGINE_STOPPED -> R.string.vpn_notification_blocked_engine
        StatusContent.Text.PROFILE_DELETED -> R.string.vpn_notification_blocked_profile_deleted
        StatusContent.Text.PROXY_FAILED -> R.string.vpn_notification_blocked_text
        StatusContent.Text.DISCONNECTED -> R.string.vpn_notification_disconnected_text
    }

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
