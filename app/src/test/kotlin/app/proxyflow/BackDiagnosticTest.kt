package app.proxyflow

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.testing.DumpOnFailureRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * TEMPORARY (removed before merge): system Back on the app's proxy form neither
 * pops nor stays put correctly. These tests use a bare two-screen NavHost to see
 * whether Back behaves the same without any of the app's own code.
 */
@RunWith(AndroidJUnit4::class)
class BackDiagnosticTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.describeScreen() + "\nback handled by screen B: $handledByB" }

    private var handledByB by mutableStateOf(false)

    private fun twoScreens(bInterceptsBack: Boolean?) {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "a") {
                composable("a") { Text("Screen A") }
                composable("b") {
                    if (bInterceptsBack != null) BackHandler(enabled = bInterceptsBack) { handledByB = true }
                    Text("Screen B")
                }
            }
            LaunchedEffect(Unit) { nav.navigate("b") }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Screen B").assertExists()
        compose.onNodeWithText("Screen A").assertDoesNotExist()
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    @Test
    fun m1_plainNavHostPopsOnSystemBack() {
        twoScreens(bInterceptsBack = null)
        systemBack()
        compose.waitForIdle()
        compose.onNodeWithText("Screen A").assertExists()
        compose.onNodeWithText("Screen B").assertDoesNotExist()
    }

    @Test
    fun m1b_plainNavHostPopsOnSystemBackAfterLooperIdle() {
        twoScreens(bInterceptsBack = null)
        systemBack()
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("Screen A").assertExists()
        compose.onNodeWithText("Screen B").assertDoesNotExist()
    }

    @Test
    fun m2_enabledBackHandlerOnTheScreenWins() {
        twoScreens(bInterceptsBack = true)
        systemBack()
        compose.waitForIdle()
        assertTrue("the screen's BackHandler did not get the Back", handledByB)
        compose.onNodeWithText("Screen B").assertExists()
        compose.onNodeWithText("Screen A").assertDoesNotExist()
    }

    @Test
    fun m3_disabledBackHandlerLetsNavHostPop() {
        twoScreens(bInterceptsBack = false)
        systemBack()
        compose.waitForIdle()
        assertFalse("a disabled BackHandler got the Back", handledByB)
        compose.onNodeWithText("Screen A").assertExists()
        compose.onNodeWithText("Screen B").assertDoesNotExist()
    }
}
