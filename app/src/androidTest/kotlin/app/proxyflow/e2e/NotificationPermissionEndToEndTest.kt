package app.proxyflow.e2e

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M10: Android 13 and later without the notification permission — the user said Not now on onboarding's A3, or turned
 * ProxyFlow's notifications off. Connecting needs no notification: the VPN's foreground service still runs (Android lists
 * it in its Task Manager instead of the notification drawer), the connection works as with the permission, and the
 * notification drawer shows nothing of ProxyFlow.
 *
 * Revoking a runtime permission ends the app's process, which runs this test, so scripts/m10_scenarios.sh revokes it
 * while ProxyFlow is not running and then runs this test alone (`-e m10 notifications`); in the device tests' own run it
 * is skipped.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionEndToEndTest {
    @Test(timeout = 120_000)
    fun withoutTheNotificationPermissionItConnectsAndCarriesTraffic() {
        assumeTrue("run by scripts/m10_scenarios.sh", InstrumentationRegistry.getArguments().getString("m10") == "notifications")
        assumeTrue("Android 13 and later ask for it", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val app = App()
        val env = E2eConfig.load()
        val notifications = app.context.getSystemService(NotificationManager::class.java)
        assertEquals(
            "the permission is denied",
            PackageManager.PERMISSION_DENIED,
            app.context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
        )
        assertFalse("Android shows none of ProxyFlow's notifications", notifications.areNotificationsEnabled())
        app.setVpnConsent(allowed = true)
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        try {
            val profile = app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)
            app.connect(profile.id)
            val line = Lines.next("notifications-denied")
            Lines.sent(line, env.destinationName, env.destinationPort)
            assertEquals("REPLY PROXYFLOW-DEST $line", FromShell.tcp(env.destinationName, env.destinationPort, line))
            assertEquals(ConnectionStatus.Connected, app.controller.state.value.status)
            // Connected, the VPN's foreground service running: Android posted none of ProxyFlow's notifications.
            val shown = notifications.activeNotifications.map { it.id }
            assertEquals("ProxyFlow's notifications Android shows while connected", emptyList<Int>(), shown)
            Log.i(LAB_TAG, "notifications: permission denied, connected, a line through the proxy, none of ProxyFlow's notifications shown")
        } finally {
            app.disconnectAndSettle()
            app.deleteAllProfiles()
        }
    }
}
