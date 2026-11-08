package app.proxyflow.core.designsystem.component

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import app.proxyflow.core.designsystem.R

/**
 * Android's VPN settings, where Always-on VPN and "Block connections without VPN" are (board E2, H1; M8); the network
 * settings, then Settings itself, on a phone without that screen. Each is Android's own screen: nothing is passed to it.
 */
fun openVpnSettings(context: Context) {
    for (action in listOf(Settings.ACTION_VPN_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // the next one
        }
    }
    Toast.makeText(context, R.string.pf_no_vpn_settings, Toast.LENGTH_LONG).show()
}
