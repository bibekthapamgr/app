package app.proxyflow.core.vpn

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The connection notification's actions — Disconnect, Cancel, Restart — on Android 10 and 11 (M10, ADR-0015 §9).
 * Disconnecting sends apps to the normal network, so it must never work from a locked phone's notification shade.
 * Android 12 and later have a flag for that on the action itself (`setAuthenticationRequired`); before it, Android
 * asks for the unlock only before it starts an activity. So there the actions start this one: it shows nothing,
 * hands the action to the service, as the action's own service intent does on later versions, and finishes.
 * Not exported: only the notification's immutable intents start it.
 */
class NotificationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (action in ACTIONS) startService(ProxyFlowVpnService.intent(this, checkNotNull(action)))
        finish()
    }

    internal companion object {
        private val ACTIONS = setOf(ProxyFlowVpnService.ACTION_DISCONNECT, ProxyFlowVpnService.ACTION_RESTART)

        fun pendingAction(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, NotificationActionActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
