package app.proxyflow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.testing.DumpOnFailureRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2 acceptance: "UI state is not stored only inside Activity". The Activity is
 * destroyed and recreated (as on rotation or a theme change) and the state is
 * still there, because it lives in ViewModels, SavedStateHandle and the
 * application-scoped repositories.
 */
@RunWith(AndroidJUnit4::class)
class StateRestorationTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.describeScreen() }

    @Before
    fun waitForFirstFrame() = compose.waitForApp()

    @Test
    fun halfFilledFormSurvivesActivityRecreation() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").performTextInput("proxy.example.com")
        compose.onNodeWithText("Port").performTextInput("3128")
        compose.onNodeWithText("HTTP").performClick()

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText("proxy.example.com").assertIsDisplayed()
        compose.onNodeWithText("3128").assertIsDisplayed()
        compose.onNodeWithText("Discard changes?").assertDoesNotExist()
        compose.pressBack()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
    }

    @Test
    fun savedProfilesSurviveActivityRecreation() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").performTextInput("203.0.113.10")
        compose.onNodeWithText("Port").performTextInput("1080")
        compose.onAllNodesWithText("Save").onFirst().performClick()
        compose.onNodeWithText("203.0.113.10:1080").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.waitForApp()

        compose.onNodeWithText("203.0.113.10:1080").assertIsDisplayed()
    }

    @Test
    fun chosenThemeIsSavedAndShownAfterRecreation() {
        val themeRowSaysDark = hasText("Theme") and hasText("Dark")
        compose.tab("Settings").performClick()
        compose.onNodeWithText("Theme").performClick()
        compose.onNodeWithText("Dark").performClick()
        // DataStore writes on its own thread; wait until the row shows the stored value.
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodes(themeRowSaysDark).fetchSemanticsNodes().isNotEmpty() }

        compose.activityRule.scenario.recreate()
        compose.waitForApp()

        compose.tab("Settings").assertIsSelected()
        compose.onNode(themeRowSaysDark).assertIsDisplayed()
    }
}
