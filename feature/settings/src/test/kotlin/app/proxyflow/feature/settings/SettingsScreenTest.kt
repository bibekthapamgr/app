package app.proxyflow.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun themeIsChosenFromADialog() {
        val chosen = mutableListOf<ThemePreference>()
        compose.setContent {
            ProxyFlowTheme {
                SettingsScreen(
                    SettingsUiState(loading = false, theme = ThemePreference.System),
                    onThemeChange = { chosen += it },
                    onOpenAbout = {},
                )
            }
        }
        // Below the kill switch section (M8): on a small screen the rows are scrolled to first.
        shown("System default")
        compose.onNodeWithText("Theme").performScrollTo().performClick()
        compose.onNodeWithText("Light").assertIsDisplayed()
        compose.onNodeWithText("Dark").performClick()

        assertEquals(listOf(ThemePreference.Dark), chosen)
        compose.onNodeWithText("Light").assertDoesNotExist()
    }

    @Test
    fun currentThemeIsSelectedInTheDialog() {
        compose.setContent {
            ProxyFlowTheme {
                SettingsScreen(SettingsUiState(loading = false, theme = ThemePreference.Dark), onThemeChange = {}, onOpenAbout = {})
            }
        }
        compose.onNodeWithText("Theme").performScrollTo().performClick()
        // "Dark" is also the row's value; only the dialog option is selectable.
        compose.onNode(hasText("Dark") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Light") and isSelectable()).assertIsNotSelected()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasText("Light") and isSelectable()).assertDoesNotExist()
    }

    /** Boards E1, E1b (M8): neither kill switch is a switch; Android's shows what Android last said, and when. */
    @Test
    fun theKillSwitchesAreDescribedNotToggled() {
        var guides = 0
        compose.setContent {
            ProxyFlowTheme {
                SettingsScreen(
                    SettingsUiState(loading = false),
                    onThemeChange = {},
                    onOpenAbout = {},
                    onOpenKillSwitchGuide = { guides++ },
                )
            }
        }
        shown("KILL SWITCH") // a section header, in capitals
        shown("Always on: while connected, traffic is blocked whenever the proxy isn't working.")
        compose.onNode(isToggleable()).assertDoesNotExist()
        shown("Not checked yet · Set up", substring = true)
        shown("Android tells ProxyFlow only while it's connected.", substring = true)
        compose.onNodeWithText("System kill switch").performScrollTo().performClick()
        assertEquals(1, guides)
    }

    @Test
    fun androidsKillSwitchIsShownAsLastReported() {
        val on = SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = 0)
        var state by mutableStateOf(SettingsUiState(loading = false, killSwitch = on, connected = true))
        compose.setContent { ProxyFlowTheme { SettingsScreen(state, onThemeChange = {}, onOpenAbout = {}) } }
        shown("On · Android blocks traffic whenever ProxyFlow isn't running", substring = true)
        shown("As Android says now, while ProxyFlow is connected.", substring = true)
        shown("With the system kill switch on, apps have no internet while ProxyFlow is off.", substring = true)

        state = state.copy(killSwitch = on.copy(blocksWithoutVpn = false), connected = false)
        shown("Always-on VPN without blocking · Set up", substring = true)
        shown("As Android said at", substring = true)
        compose.onNodeWithText("apps have no internet while ProxyFlow is off", substring = true).assertDoesNotExist()

        state = state.copy(killSwitch = on.copy(alwaysOn = false, blocksWithoutVpn = false))
        shown("Off · Set up", substring = true)
    }

    /** Board E2: the steps in Android's settings, what ProxyFlow can't do, and the way there. */
    @Test
    fun theGuideSaysOnlyTheUserCanTurnItOnAndOpensAndroidsVpnSettings() {
        var opened = 0
        var back = 0
        compose.setContent {
            ProxyFlowTheme {
                KillSwitchGuideScreen(SettingsUiState(loading = false), onOpenVpnSettings = { opened++ }, onBack = { back++ })
            }
        }
        shown("Status: not checked yet", substring = true)
        for (step in listOf("Turn on Always-on VPN.", "Turn on Block connections without VPN.")) {
            shown(step)
        }
        shown("ProxyFlow can't turn this on for you", substring = true)
        shown("it may not start it again", substring = true)
        shown("Android lets some of its own services reach the network outside any VPN", substring = true)
        compose.onNodeWithText("Open VPN settings").performScrollTo().performClick()
        compose.onNodeWithText("Later").performScrollTo().performClick()
        assertEquals(1, opened)
        assertEquals(1, back)
    }

    @Test
    fun theGuideShowsWhenAndroidSaysItIsOn() {
        val state = SettingsUiState(loading = false, killSwitch = SystemKillSwitch(true, true, checkedAtMillis = 0), connected = true)
        compose.setContent { ProxyFlowTheme { KillSwitchGuideScreen(state, onOpenVpnSettings = {}, onBack = {}) } }
        shown("Status: on\nAs Android says now, while ProxyFlow is connected.")
    }

    @Test
    fun aboutOpensFromSettings() {
        var opened = 0
        compose.setContent {
            ProxyFlowTheme { SettingsScreen(SettingsUiState(loading = false), onThemeChange = {}, onOpenAbout = { opened++ }) }
        }
        compose.onNodeWithText("About & privacy").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onNodeWithText(text, substring = substring).performScrollTo().assertIsDisplayed()
}
