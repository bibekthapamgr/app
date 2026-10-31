package app.proxyflow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.testing.DumpOnFailureRule
import app.proxyflow.feature.profiles.AddEditProxyDestination
import app.proxyflow.feature.profiles.navigateToAddEditProxy
import org.junit.Assert.assertEquals
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

    private val navigationEvents = mutableListOf<String>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.describeScreen() + compose.describeNavigation(navigationEvents) }

    @Before
    fun waitForFirstFrame() {
        compose.waitForApp()
        compose.recordNavigation(navigationEvents)
    }

    @Test
    fun everyScreenOpensAndClosesWithoutCrashing() {
        // Home (B1), then each bottom-navigation tab.
        compose.waitForText("Add a proxy to get started").assertIsDisplayed()
        compose.tab("Home").assertIsSelected()

        compose.tab("Profiles").performClick()
        compose.waitForText("No proxies yet").assertIsDisplayed()
        compose.tab("Profiles").assertIsSelected()

        compose.tab("Logs").performClick()
        compose.onNodeWithText("No events yet").assertIsDisplayed()

        compose.tab("Settings").performClick()
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
        compose.topBarSave().performClick()

        compose.waitForText("proxy.example.com:1080").assertIsDisplayed()
        compose.onNodeWithText("proxy.example.com").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()

        // Home → Profiles (Change) → Edit proxy → Back.
        compose.onNodeWithText("Change").performClick()
        compose.tab("Profiles").assertIsSelected()
        compose.waitForText("ACTIVE").assertIsDisplayed()
        compose.onNodeWithText("proxy.example.com:1080").performClick()
        compose.onNodeWithText("Edit proxy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForText("ACTIVE").assertIsDisplayed()
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
        compose.waitForText("Add a proxy to get started").assertIsDisplayed()
    }

    @Test
    fun anUntouchedFormClosesWithoutAsking() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.pressBack()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.waitForText("Add a proxy to get started").assertIsDisplayed()
    }

    @Test
    fun aDoubleTapOpensTheFormOnlyOnce() {
        // Both taps of a quick double tap arrive before the form is drawn.
        compose.runOnUiThread {
            val navController = checkNotNull(compose.activity.navController)
            navController.navigateToAddEditProxy()
            navController.navigateToAddEditProxy()
        }
        compose.waitForIdle()
        val forms = compose.activity.navController?.currentBackStack?.value.orEmpty()
            .count { it.destination.hasRoute<AddEditProxyDestination>() }
        assertEquals(1, forms)

        compose.pressBack()
        compose.waitForText("Add a proxy to get started").assertIsDisplayed()
    }

    @Test
    fun invalidFormStaysOpenAndSaysWhatToFix() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").performTextInput("not a host")
        // Save at the end of the form: the form scrolls back up to the error summary.
        compose.formEndSave().performScrollTo().performClick()
        compose.onNodeWithText("Fix 2 fields to save this proxy").assertIsDisplayed()
        compose.onNodeWithText("Enter a hostname or IP address (no spaces)").assertIsDisplayed()
        compose.onNodeWithText("Port must be between 1 and 65535").assertIsDisplayed()
    }
}
