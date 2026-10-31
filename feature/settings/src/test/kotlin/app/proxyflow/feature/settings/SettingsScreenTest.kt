package app.proxyflow.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
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
        compose.onNodeWithText("System default").assertIsDisplayed()
        compose.onNodeWithText("Theme").performClick()
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
        compose.onNodeWithText("Theme").performClick()
        // "Dark" is also the row's value; only the dialog option is selectable.
        compose.onNode(hasText("Dark") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Light") and isSelectable()).assertIsNotSelected()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNode(hasText("Light") and isSelectable()).assertDoesNotExist()
    }

    @Test
    fun aboutOpensFromSettings() {
        var opened = 0
        compose.setContent {
            ProxyFlowTheme { SettingsScreen(SettingsUiState(loading = false), onThemeChange = {}, onOpenAbout = { opened++ }) }
        }
        compose.onNodeWithText("About & privacy").performClick()
        assertEquals(1, opened)
    }
}
