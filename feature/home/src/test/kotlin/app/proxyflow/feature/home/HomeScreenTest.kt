package app.proxyflow.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
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

    @Test
    fun emptyStateLeadsToAddProxy() {
        var adds = 0
        compose.setContent {
            ProxyFlowTheme { HomeScreen(HomeUiState.NoProfile, onAddProxy = { adds++ }, onChangeProfile = {}, onConnect = {}) }
        }
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
        compose.onNodeWithText("Add proxy").performClick()
        assertEquals(1, adds)
    }

    @Test
    fun connectIsDisabledAndExplainedWhileUnavailable() {
        val profile = ProxyProfile(id = "p1", name = "Office", protocol = ProxyProtocol.HTTP, host = "203.0.113.10", port = 3128)
        compose.setContent {
            ProxyFlowTheme {
                HomeScreen(
                    HomeUiState.Ready(profile, ConnectionStatus.Disconnected, canConnect = false),
                    onAddProxy = {},
                    onChangeProfile = {},
                    onConnect = {},
                )
            }
        }
        compose.onNodeWithText("Not connected").assertIsDisplayed()
        compose.onNodeWithText("Office").assertIsDisplayed()
        compose.onNodeWithText("HTTP · Not encrypted").assertIsDisplayed()
        compose.onNodeWithText("203.0.113.10:3128").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithText("Connecting arrives with the VPN service in a later milestone. This build sets up the app only.")
            .assertIsDisplayed()
    }
}
