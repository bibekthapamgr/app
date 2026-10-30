package app.proxyflow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.testing.DumpOnFailureRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2 acceptance: every screen opens and closes without crashing, through the
 * real app (MainActivity, the navigation graph and the real ViewModels).
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.onAllNodes(isRoot()).printToString() }

    @Before
    fun waitForFirstFrame() = compose.waitForApp()

    @Test
    fun everyScreenOpensAndClosesWithoutCrashing() {
        // Home (B1), then each bottom-navigation tab.
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
        compose.tab("Home").assertIsSelected()

        compose.tab("Profiles").performClick()
        compose.onNodeWithText("No proxies yet").assertIsDisplayed()
        compose.tab("Profiles").assertIsSelected()

        compose.tab("Logs").performClick()
        compose.onNodeWithText("No events yet").assertIsDisplayed()

        // Logs → Settings through the empty state's "Log settings" button.
        compose.onNodeWithText("Log settings").performClick()
        compose.tab("Settings").assertIsSelected()
        compose.onNodeWithText("Theme").assertIsDisplayed()

        // Settings → About (full screen, no bottom bar) → Back.
        compose.onNodeWithText("About & privacy").performClick()
        compose.onNodeWithText("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · free, no account").assertIsDisplayed()
        compose.tab("Home").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Theme").assertIsDisplayed()

        // Home → Add proxy → save a profile → Home shows it.
        compose.tab("Home").performClick()
        compose.onNodeWithText("Add proxy").performClick()
        compose.tab("Home").assertDoesNotExist()
        compose.onNodeWithText("Server").performTextInput("proxy.example.com")
        compose.onNodeWithText("Port").performTextInput("1080")
        compose.onAllNodesWithText("Save").onFirst().performClick()

        compose.onNodeWithText("proxy.example.com").assertIsDisplayed()
        compose.onNodeWithText("proxy.example.com:1080").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()

        // Home → Profiles (Change) → Edit proxy → Back.
        compose.onNodeWithText("Change").performClick()
        compose.tab("Profiles").assertIsSelected()
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
        compose.onNodeWithText("proxy.example.com:1080").performClick()
        compose.onNodeWithText("Edit proxy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
    }

    @Test
    fun leavingAnEditedFormAsksFirst() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").performTextInput("proxy.example.com")

        compose.pressBack()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("proxy.example.com").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
    }

    @Test
    fun anUntouchedFormClosesWithoutAsking() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.pressBack()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
    }

    @Test
    fun invalidFormStaysOpenAndSaysWhatToFix() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").performTextInput("not a host")
        compose.onAllNodesWithText("Save").onFirst().performClick()
        compose.onNodeWithText("Fix 2 fields to save this proxy").assertIsDisplayed()
        compose.onNodeWithText("Enter a hostname or IP address (no spaces)").assertIsDisplayed()
        compose.onNodeWithText("Port must be between 1 and 65535").assertIsDisplayed()
    }
}
