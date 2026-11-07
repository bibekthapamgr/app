package app.proxyflow.core.vpn

import android.app.Application
import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The connection notification on a locked phone (M10, ADR-0015 §9; threat model I-11): it names no profile, whatever
 * Android's setting for notification content on the lock screen, and Disconnect — which sends apps to the normal
 * network — Cancel and Restart run only on an unlocked phone: Android 12's flag on the action, or before it an
 * activity, which Android starts only once the phone is unlocked.
 */
@RunWith(AndroidJUnit4::class)
class VpnNotificationsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val notifications = VpnNotifications(context)

    @Test
    fun noLockScreenShowsTheProfilesName() {
        for (state in STATES) {
            val notification = notifications.status(state)
            for (version in listOfNotNull(notification, notification.publicVersion)) {
                val text = listOf(
                    Notification.EXTRA_TITLE,
                    Notification.EXTRA_TEXT,
                    Notification.EXTRA_SUB_TEXT,
                    Notification.EXTRA_INFO_TEXT,
                )
                    .mapNotNull { version.extras.getCharSequence(it)?.toString() }
                assertTrue("${state.status}: $text", text.none { NAME in it })
            }
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        }
    }

    @Test
    fun fromAndroid12EveryActionAsksForTheUnlockFirst() {
        val actions = STATES.flatMap { notifications.status(it).actions.orEmpty().toList() }
        assertEquals(setOf("Disconnect", "Cancel", "Restart"), actions.map { it.title.toString() }.toSet())
        for (action in actions) {
            assertTrue("${action.title}: unlocked phones only", action.isAuthenticationRequired)
            assertTrue("${action.title}: the service's own intent", shadowOf(action.actionIntent).isServiceIntent)
        }
    }

    @Test
    @Config(sdk = [30])
    fun onAndroid10And11EveryActionGoesThroughAnActivity() {
        val actions = STATES.flatMap { notifications.status(it).actions.orEmpty().toList() }
        assertEquals(setOf("Disconnect", "Cancel", "Restart"), actions.map { it.title.toString() }.toSet())
        for (action in actions) {
            val intent = shadowOf(action.actionIntent)
            assertTrue("${action.title}: an activity, which Android starts only unlocked", intent.isActivityIntent)
            assertEquals(NotificationActionActivity::class.java.name, intent.savedIntent.component?.className)
            val expected = if (action.title.toString() ==
                "Restart"
            ) {
                ProxyFlowVpnService.ACTION_RESTART
            } else {
                ProxyFlowVpnService.ACTION_DISCONNECT
            }
            assertEquals(expected, intent.savedIntent.action)
        }
    }

    @Test
    fun theActivityHandsTheActionToTheServiceAndFinishes() {
        for (action in listOf(ProxyFlowVpnService.ACTION_DISCONNECT, ProxyFlowVpnService.ACTION_RESTART)) {
            val activity = Robolectric.buildActivity(NotificationActionActivity::class.java, launch(action)).create().get()
            val started = checkNotNull(shadowOf(context).nextStartedService) { "$action: no service started" }
            assertEquals(ProxyFlowVpnService::class.java.name, started.component?.className)
            assertEquals(action, started.action)
            assertTrue(activity.isFinishing)
        }
        // Anything else starts nothing.
        val other = Robolectric.buildActivity(NotificationActionActivity::class.java, launch("app.proxyflow.vpn.OTHER")).create().get()
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(other.isFinishing)
    }

    private fun launch(action: String) = Intent(context, NotificationActionActivity::class.java).setAction(action)

    private companion object {
        const val NAME = "Tokyo residential"

        /** A state of each kind the notification has: connected, connecting, held for Always-on, reconnecting, blocked. */
        val STATES = listOf(
            ConnectionState(ConnectionStatus.Connected, "p1", NAME, connectedSinceMillis = 1_000),
            ConnectionState(ConnectionStatus.Connecting, "p1", NAME),
            ConnectionState(ConnectionStatus.Connecting, "p1", NAME, held = true),
            ConnectionState(ConnectionStatus.Reconnecting, "p1", NAME, connectedSinceMillis = 1_000),
            ConnectionState(ConnectionStatus.Blocked, "p1", NAME, error = ConnectionError("PROXY_UNREACHABLE")),
            ConnectionState(
                ConnectionStatus.Offline,
                "p1",
                NAME,
                connectedSinceMillis = 1_000,
                error = ConnectionError(ConnectionError.OFFLINE),
            ),
        )
    }
}
