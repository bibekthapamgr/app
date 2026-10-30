package app.proxyflow

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.testing.DumpOnFailureRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY (removed before merge): the proxy form only misbehaves when Back
 * comes from the system (OnBackPressedDispatcher), not from its back arrow.
 * These tests try the other ways a Back can arrive.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.describeScreen() }

    @Before
    fun waitForFirstFrame() = compose.waitForApp()

    @Test
    fun g_predictiveBackGestureClosesAnUntouchedForm() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.runOnUiThread {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 200f, 0.5f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
    }

    @Test
    fun i_systemBackThenMoreTimeClosesAnUntouchedForm() {
        compose.onNodeWithText("Add proxy").performClick()
        compose.pressBack()
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
        compose.onNodeWithText("Add a proxy to get started").assertIsDisplayed()
    }
}
