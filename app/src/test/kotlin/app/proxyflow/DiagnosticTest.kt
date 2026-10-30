package app.proxyflow

import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.testing.DumpOnFailureRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY (removed before merge): finds out why the screen stops updating in
 * Robolectric once the proxy form is open. Each test isolates one suspect.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE).take(1500) }

    @Before
    fun waitForFirstFrame() = compose.waitForApp()

    @Test
    fun a_tabSwitchWithoutWindowFlagChanges() {
        compose.tab("Profiles").performClick()
        compose.onNodeWithText("No proxies yet").assertIsDisplayed()
    }

    @Test
    fun b_tabSwitchAfterFlagSecureIsAdded() {
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        compose.waitForIdle()
        compose.tab("Profiles").performClick()
        compose.onNodeWithText("No proxies yet").assertIsDisplayed()
    }

    @Test
    fun c_tabSwitchAfterKeepScreenOnIsAdded() {
        compose.runOnUiThread { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        compose.waitForIdle()
        compose.tab("Profiles").performClick()
        compose.onNodeWithText("No proxies yet").assertIsDisplayed()
    }

    @Test
    fun d_homeIsGoneOnceTheFormIsOpen() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Add a proxy to get started").assertDoesNotExist()
    }

    @Test
    fun e_formClosesWithItsBackArrow() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
    }

    @Test
    fun f_aboutClosesWithItsBackArrow() {
        compose.tab("Settings").performClick()
        compose.onNodeWithText("About & privacy").performClick()
        compose.onNodeWithText("Theme").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Theme").assertIsDisplayed()
    }
}
