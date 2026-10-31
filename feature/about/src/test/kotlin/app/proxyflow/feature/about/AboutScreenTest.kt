package app.proxyflow.feature.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsVersionScopeAndLimits() {
        compose.setContent { ProxyFlowTheme { AboutScreen(version = "0.2.0 (2)", onBack = {}) } }
        compose.onNodeWithText("Version 0.2.0 (2) · free, no account").assertIsDisplayed()
        compose.onNodeWithText("This build sets up the app only.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("UDP (calls, some games) and ping are blocked", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun backLeavesThePage() {
        var backs = 0
        compose.setContent { ProxyFlowTheme { AboutScreen(version = "0.2.0 (2)", onBack = { backs++ }) } }
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
