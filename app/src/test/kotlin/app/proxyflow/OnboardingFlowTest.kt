package app.proxyflow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first launch through the real app (docs/m1/03 J1): the pages come before anything else, the app starts at Home once
 * they were gone through, that is saved, and they don't come back.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theFirstLaunchShowsThePagesOnceThenHome() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText("Welcome to ProxyFlow").fetchSemanticsNodes().isNotEmpty() }
        compose.tab("Home").assertDoesNotExist()
        compose.onNodeWithText("Next").performClick()
        // A2 comes before anything can connect.
        compose.onNodeWithText("How ProxyFlow works").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        // Robolectric runs Android 15, where the notification permission is asked for (A3); the answer here is no.
        compose.onNodeWithText("Show connection status?").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()

        compose.waitForApp()
        compose.waitForText("Add a proxy to get started").assertIsDisplayed()
        val settings = (compose.activity.application as ProxyFlowApplication).container.settingsRepository
        assertTrue("saved", runBlocking { settings.settings.first().onboardingCompleted })

        compose.activityRule.scenario.recreate()
        compose.waitForApp()
        compose.onNodeWithText("Welcome to ProxyFlow").assertDoesNotExist()
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun firstLaunch() {
            TestProxyFlowApplication.firstLaunch = true
        }

        @JvmStatic
        @AfterClass
        fun usedBefore() {
            TestProxyFlowApplication.firstLaunch = false
        }
    }
}
