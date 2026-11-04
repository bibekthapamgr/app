package app.proxyflow.feature.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ProfileTest
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

    /** Board C2: each card says how its last test went and when, in the catalogue's words; Test runs one from the menu. */
    @Test
    fun eachCardShowsItsLastTestAndTestRunsOne() {
        val tested = mutableListOf<String>()
        val now = 1_793_000_000_000L
        val state = ProfilesUiState(
            loading = false,
            profiles = listOf(office, backup),
            activeProfileId = "p1",
            lastTests = mapOf("p1" to ProfileTest(now - 20 * 3_600_000L, code = "AUTH_FAILED", latencyMillis = null)),
        )
        var shown by mutableStateOf(state)
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    shown,
                    onAddProxy = {},
                    onEditProxy = {},
                    onUseProxy = {},
                    onDeleteProxy = {},
                    onTestProxy = { tested += it },
                    nowMillis = now,
                )
            }
        }
        compose.onNodeWithText("Username or password rejected · tested 20 hours ago").assertIsDisplayed()
        compose.onNodeWithText("Not tested yet").assertIsDisplayed()

        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Test").performClick()
        assertEquals(listOf("p2"), tested)

        shown = state.copy(testing = setOf("p2"))
        compose.onNodeWithText("Testing…").assertIsDisplayed()
        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Test").assertIsNotEnabled()

        shown = state.copy(lastTests = state.lastTests + ("p2" to ProfileTest(now - 2 * 60_000L, code = null, latencyMillis = 182)))
        compose.onNodeWithText("Works · 182 ms · tested 2 min ago").assertIsDisplayed()
    }

    /** J11: the profile a connection uses can only be deleted together with ending the connection, and the dialog says so. */
    @Test
    fun deletingTheProfileInUseSaysItDisconnects() {
        val deleted = mutableListOf<String>()
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    ProfilesUiState(loading = false, profiles = listOf(office, backup), activeProfileId = "p1", connectedProfileId = "p1"),
                    onAddProxy = {},
                    onEditProxy = {},
                    onUseProxy = {},
                    onDeleteProxy = { deleted += it },
                )
            }
        }
        compose.onNodeWithContentDescription("More actions for Office HTTP").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Disconnect and delete \"Office HTTP\"?").assertIsDisplayed()
        compose.onNodeWithText(
            "ProxyFlow is using this proxy. Deleting it ends the connection first: apps then use your normal network unless " +
                "Android's system kill switch is on.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Disconnect and delete").performClick()
        assertEquals(listOf("p1"), deleted)
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

    /** J11: "Use this proxy" while a connection runs on another profile switches it, so the screen asks first. */
    @Test
    fun usingAnotherProxyWhileConnectedAsksFirst() {
        val used = mutableListOf<String>()
        var state by mutableStateOf(
            ProfilesUiState(loading = false, profiles = listOf(office, backup), activeProfileId = "p1", connectedProfileId = "p1"),
        )
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(state, onAddProxy = {}, onEditProxy = {}, onUseProxy = { used += it }, onDeleteProxy = {})
            }
        }
        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Use this proxy").performClick()
        compose.onNodeWithText("Switch to \"Backup SOCKS\"?").assertIsDisplayed()
        compose.onNodeWithText(
            "ProxyFlow is using Office HTTP. Switching ends its open connections, and apps can't go online until ProxyFlow is " +
                "connected through Backup SOCKS.",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("cancelled: nothing switches", emptyList<String>(), used)

        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Use this proxy").performClick()
        compose.onNodeWithText("Switch").performClick()
        assertEquals(listOf("p2"), used)

        // The connection ends while the question is open: it closes, and nothing is switched.
        compose.onNodeWithContentDescription("More actions for Backup SOCKS").performClick()
        compose.onNodeWithText("Use this proxy").performClick()
        state = state.copy(connectedProfileId = null)
        compose.onNodeWithText("Switch to \"Backup SOCKS\"?").assertDoesNotExist()
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

    @Test
    fun aFailedActionIsReportedOnce() {
        var shown = 0
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    ProfilesUiState(loading = false, profiles = listOf(office), activeProfileId = "p1", failed = ProfileAction.Duplicate),
                    onAddProxy = {},
                    onEditProxy = {},
                    onUseProxy = {},
                    onDeleteProxy = {},
                    onFailureShown = { shown++ },
                )
            }
        }
        compose.onNodeWithText("Couldn't duplicate this proxy. Try again.").assertIsDisplayed()
        assertEquals(0, shown)

        compose.mainClock.advanceTimeBy(10_000) // past the message's display time
        compose.waitForIdle()
        assertEquals(1, shown)
    }
}
