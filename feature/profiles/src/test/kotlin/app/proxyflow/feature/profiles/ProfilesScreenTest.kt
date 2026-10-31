package app.proxyflow.feature.profiles

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfilesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val office = ProxyProfile(id = "p1", name = "Office HTTP", protocol = ProxyProtocol.HTTP, host = "203.0.113.10", port = 3128)
    private val backup =
        ProxyProfile(id = "p2", name = "Backup SOCKS", protocol = ProxyProtocol.SOCKS5, host = "socks.example.net", port = 1080)

    @Test
    fun emptyListOffersToAddAProxy() {
        var adds = 0
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    ProfilesUiState(loading = false),
                    onAddProxy = { adds++ },
                    onEditProxy = {},
                    onUseProxy = {},
                    onDeleteProxy = {},
                )
            }
        }
        compose.onNodeWithText("No proxies yet").assertIsDisplayed()
        compose.onNodeWithText("Add proxy").performClick()
        assertEquals(1, adds)
    }

    @Test
    fun deleteOnlyHappensAfterConfirmation() {
        val deleted = mutableListOf<String>()
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    ProfilesUiState(loading = false, profiles = listOf(office, backup), activeProfileId = "p1"),
                    onAddProxy = {},
                    onEditProxy = {},
                    onUseProxy = {},
                    onDeleteProxy = { deleted += it },
                )
            }
        }
        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"Backup SOCKS\"?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), deleted)

        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(listOf("p2"), deleted)
    }

    @Test
    fun tappingAProfileOpensItAndTheMenuCanActivateIt() {
        val edited = mutableListOf<String>()
        val used = mutableListOf<String>()
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    ProfilesUiState(loading = false, profiles = listOf(office, backup), activeProfileId = "p1"),
                    onAddProxy = {},
                    onEditProxy = { edited += it },
                    onUseProxy = { used += it },
                    onDeleteProxy = {},
                )
            }
        }
        compose.onNodeWithText("socks.example.net:1080").performClick()
        assertEquals(listOf("p2"), edited)

        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Use this proxy").performClick()
        assertEquals(listOf("p2"), used)
    }

    @Test
    fun theMenuDuplicatesAProfile() {
        val duplicated = mutableListOf<String>()
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    ProfilesUiState(loading = false, profiles = listOf(office, backup), activeProfileId = "p1"),
                    onAddProxy = {},
                    onEditProxy = {},
                    onUseProxy = {},
                    onDeleteProxy = {},
                    onDuplicateProxy = { duplicated += it },
                )
            }
        }
        compose.onNodeWithContentDescription("More actions for Office HTTP").performClick()
        compose.onNodeWithText("Use this proxy").assertDoesNotExist() // already the active one
        compose.onNodeWithText("Duplicate").performClick()
        assertEquals(listOf("p1"), duplicated)
    }
}
