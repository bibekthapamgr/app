package app.proxyflow.feature.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.SettingsRepository
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.LogRetention
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import app.proxyflow.core.testing.FakeSettingsRepository
import app.proxyflow.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first-launch pages (boards A1–A3, docs/m1/03 J1). */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule(order = 0)
    val main = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private fun show(notifications: Boolean) {
        compose.setContent {
            ProxyFlowTheme {
                OnboardingScreen(
                    showNotificationsPage = notifications,
                    onAllowNotifications = { calls += "allow" },
                    onFinish = { calls += "finish" },
                )
            }
        }
    }

    /** A1 says what the user needs, A2 the limits and the VPN permission before anything can connect, A3 asks. */
    @Test
    fun thePagesSayWhatIsNeededTheLimitsAndThePermissionThenAsk() {
        show(notifications = true)
        compose.onNodeWithText("Welcome to ProxyFlow").assertIsDisplayed()
        compose.onNodeWithText("Page 1 of 3").assertIsDisplayed()
        shown("An HTTP proxy that allows tunnels (CONNECT), or a SOCKS5 proxy")
        shown("ProxyFlow doesn't sell or provide proxies", substring = true)
        compose.onNodeWithText("Next").performClick()

        compose.onNodeWithText("How ProxyFlow works").assertIsDisplayed()
        compose.onNodeWithText("Page 2 of 3").assertIsDisplayed()
        shown("Calls, some games, ping (UDP, ICMP)")
        shown("Blocked traffic is never sent around your proxy", substring = true)
        shown("Your proxy provider can see which sites you connect to", substring = true)
        shown("Android asks you to allow it", substring = true)
        compose.onNodeWithText("Continue").performClick()

        compose.onNodeWithText("Show connection status?").assertIsDisplayed()
        compose.onNodeWithText("Page 3 of 3").assertIsDisplayed()
        compose.onNodeWithText("Allow notifications").performClick()
        assertEquals(listOf("allow"), calls)
    }

    @Test
    fun notNowFinishesWithoutAsking() {
        show(notifications = true)
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf("finish"), calls)
    }

    /** Android 12 and earlier, or notifications already allowed: there is nothing to ask, so A2 is the last page. */
    @Test
    fun withoutANotificationPageTheSecondPageFinishes() {
        show(notifications = false)
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Page 2 of 2").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(listOf("finish"), calls)
        compose.onNodeWithText("Show connection status?").assertDoesNotExist()
    }

    @Test
    fun backGoesToThePreviousPage() {
        show(notifications = true)
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Page 2 of 3").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Welcome to ProxyFlow").assertIsDisplayed()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun howItWorksOnItsOwnHasABackButtonAndNoPages() {
        var backs = 0
        compose.setContent { ProxyFlowTheme { HowItWorksScreen(onBack = { backs++ }) } }
        shown("Printers, casting, local devices")
        compose.onNodeWithText("Page", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    /** Finishing saves that the pages were gone through, then lets the app go on. */
    @Test
    fun finishingSavesItAndThenMovesOn() {
        val settings = FakeSettingsRepository()
        val viewModel = OnboardingViewModel(settings)
        assertFalse(viewModel.finished.value)

        viewModel.finish()

        assertTrue(settings.state.value.onboardingCompleted)
        assertTrue(viewModel.finished.value)
    }

    /** A settings file that can't be written must not lock the user out: the app goes on, and the pages come back next time. */
    @Test
    fun aSettingThatCantBeSavedStillLetsTheUserIn() {
        val broken = object : SettingsRepository {
            override val settings: Flow<UserSettings> = flowOf(UserSettings())

            override suspend fun setTheme(theme: ThemePreference) = Unit

            override suspend fun setLogRetention(retention: LogRetention) = Unit

            override suspend fun setOnboardingCompleted() = throw java.io.IOException("disk full")
        }
        val viewModel = OnboardingViewModel(broken)

        viewModel.finish()

        assertTrue(viewModel.finished.value)
    }

    private fun shown(text: String, substring: Boolean = false) =
        compose.onNodeWithText(text, substring = substring).performScrollTo().assertIsDisplayed()
}
