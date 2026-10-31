package app.proxyflow.feature.home

import android.content.Intent
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val profile = ProxyProfile(id = "p1", name = "Office", protocol = ProxyProtocol.HTTP, host = "203.0.113.10", port = 3128)
    private val calls = mutableListOf<String>()
    private val actions = HomeActions(
        onAddProxy = { calls += "add" },
        onChangeProfile = { calls += "change" },
        onEditProfile = { calls += "edit $it" },
        onConnect = { calls += "connect" },
        onDisconnect = { calls += "disconnect" },
        onStop = { calls += "stop" },
        onConfirmStop = { calls += "confirm stop" },
        onExplainerContinue = { calls += "continue ${it.action}" },
        onDismissDialog = { calls += "dismiss" },
    )

    @Test
    fun emptyStateLeadsToAddProxy() {
        show(HomeUiState.NoProfile)
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
        compose.onNodeWithText("Add proxy").performClick()
        assertEquals(listOf("add"), calls)
    }

    @Test
    fun connectIsDisabledAndExplainedWhileUnavailable() {
        show(ready(ConnectionState.Idle, canConnect = false))
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Office").assertIsDisplayed()
        compose.onNodeWithText("HTTP · Not encrypted").assertIsDisplayed()
        compose.onNodeWithText("203.0.113.10:3128").assertIsDisplayed()
        scrolledTo("Connect").assertIsNotEnabled()
        scrolledTo("Connecting isn't available in this build. It saves your proxies but doesn't connect.").assertIsDisplayed()
    }

    @Test
    fun connectingShowsTheCheckBeforeAnythingChangesAndCanBeCancelled() {
        show(ready(ConnectionState(ConnectionStatus.Connecting, "p1", "Office", step = ConnectStep.CheckingProxy)))
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNodeWithText("Connecting to Office").assertIsDisplayed()
        scrolledTo("Checking proxy — TLS and sign-in").assertIsDisplayed()
        scrolledTo(
            "Your phone's network is unchanged until protection starts. If the proxy check fails, nothing is switched over.",
        ).assertIsDisplayed()
        scrolledTo("Cancel").performClick()
        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun connectedShowsItsTimeAndDisconnect() {
        val since = 1_800_000_000_000L
        show(ready(ConnectionState(ConnectionStatus.Connected, "p1", "Office", connectedSinceMillis = since)), now = since + 3_723_000)
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("01:02:03").assertIsDisplayed()
        scrolledTo("Traffic is going through your proxy").assertIsDisplayed()
        scrolledTo("Office · HTTP").assertIsDisplayed()
        scrolledTo("Disconnect").performClick()
        assertEquals(listOf("disconnect"), calls)
    }

    @Test
    fun aFailedCheckSaysWhyAndThatNothingChanged() {
        show(ready(ConnectionState(profileId = "p1", error = ConnectionError("PROXY_UNREACHABLE"))))
        compose.onNodeWithText("Can't reach your proxy").assertIsDisplayed()
        scrolledTo(
            "ProxyFlow couldn't connect to 203.0.113.10:3128. Check the address and port, and that the proxy is online.",
        ).assertIsDisplayed()
        scrolledTo("You're not connected — apps are still using your normal network.").assertIsDisplayed()
        scrolledTo("Code: PROXY_UNREACHABLE").assertIsDisplayed()
        scrolledTo("Edit profile").performClick()
        scrolledTo("Use another proxy").performClick()
        scrolledTo("Try again").performClick()
        assertEquals(listOf("edit p1", "change", "connect"), calls)
    }

    @Test
    fun blockedTrafficOffersRestartAndStop() {
        val stopped = ConnectionError(ConnectionError.ENGINE_STOPPED, "reading the TUN interface failed")
        show(ready(ConnectionState(ConnectionStatus.Blocked, "p1", "Office", error = stopped)))
        compose.onNodeWithText("Traffic blocked").assertIsDisplayed()
        scrolledTo("Protection stopped unexpectedly").assertIsDisplayed()
        scrolledTo("Apps can't go online until this is fixed — nothing is sent around your proxy.").assertIsDisplayed()
        scrolledTo("Code: ENGINE_STOPPED · reading the TUN interface failed").assertIsDisplayed()
        scrolledTo("Restart").performClick()
        scrolledTo("Stop and use normal network").performClick()
        assertEquals(listOf("connect", "stop"), calls)
    }

    @Test
    fun aDeletedProfileLeavesOnlyStop() {
        show(ready(ConnectionState(ConnectionStatus.Blocked, "p1", "Office", error = ConnectionError(ConnectionError.PROFILE_DELETED))))
        compose.onNodeWithText("Traffic blocked").assertIsDisplayed()
        scrolledTo("Profile deleted").assertIsDisplayed()
        scrolledTo("Code: PROFILE_DELETED").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Edit profile").assertDoesNotExist()
        scrolledTo("Stop and use normal network").performClick()
        assertEquals(listOf("stop"), calls)
    }

    /** What providers send for an expired plan or an address they don't allow fails the check, and says what to check. */
    @Test
    fun aProxyRefusingTheCheckSaysWhatToCheck() {
        show(
            ready(
                ConnectionState(
                    profileId = "p1",
                    error = ConnectionError("TARGET_NOT_ALLOWED", "proxy refused this destination (HTTP 403)"),
                ),
            ),
        )
        compose.onNodeWithText("Your proxy refused the connection").assertIsDisplayed()
        scrolledTo(
            "203.0.113.10:3128 refused to open a test connection. Providers answer this way when a plan has ended or " +
                "your network's address isn't allowed. Check your account with your proxy provider.",
        ).assertIsDisplayed()
        scrolledTo("You're not connected — apps are still using your normal network.").assertIsDisplayed()
    }

    @Test
    fun stoppingWhileBlockedAsksFirst() {
        val blocked = ConnectionState(ConnectionStatus.Blocked, "p1", "Office", error = ConnectionError(ConnectionError.ENGINE_STOPPED))
        show(ready(blocked, dialog = HomeDialog.ConfirmStop))
        compose.onNodeWithText("Stop protection?").assertIsDisplayed()
        compose.onNodeWithText("Apps will use your normal network, not your proxy, until you connect again.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun aRevokedVpnSaysAndroidRemovedIt() {
        show(ready(ConnectionState(profileId = "p1", error = ConnectionError(ConnectionError.VPN_REVOKED))))
        compose.onNodeWithText("Disconnected by Android").assertIsDisplayed()
        scrolledTo("Your apps are now using your normal network.").assertIsDisplayed()
        scrolledTo("Reconnect").performClick()
        assertEquals(listOf("connect"), calls)
    }

    @Test
    fun theExplainerComesBeforeAndroidsConsentScreen() {
        show(ready(ConnectionState.Idle, dialog = HomeDialog.VpnExplainer(Intent("android.net.vpn.SETUP_VPN"))))
        compose.onNodeWithText("Allow a VPN connection").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("continue android.net.vpn.SETUP_VPN"), calls)
    }

    @Test
    fun aRefusedPermissionShowsH1() {
        show(ready(ConnectionState.Idle, dialog = HomeDialog.VpnPermissionDenied))
        compose.onNodeWithText("VPN permission needed").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(listOf("dismiss", "connect"), calls)
    }

    private fun ready(connection: ConnectionState, canConnect: Boolean = true, dialog: HomeDialog? = null) =
        HomeUiState.Ready(profile, connection, canConnect, dialog)

    private fun show(state: HomeUiState, now: Long = 0L) {
        compose.setContent { ProxyFlowTheme { HomeScreen(state, actions, nowMillis = now) } }
    }

    private fun scrolledTo(text: String): SemanticsNodeInteraction = compose.onNodeWithText(text).performScrollTo()
}
