package app.proxyflow

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.UserSettings
import app.proxyflow.core.testing.DumpOnFailureRule
import app.proxyflow.ui.ProxyFlowApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY (removed before merge): the app shell handles system Back on the
 * proxy form correctly in a plain activity, but not in MainActivity. Each test
 * adds one thing MainActivity does.
 */
@RunWith(AndroidJUnit4::class)
class BackDiagnosticTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.describeScreen() }

    private val container get() = (compose.activity.application as ProxyFlowApplication).container

    private fun openFormThenSystemBack() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add proxy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Add a proxy to get started").assertExists()
        compose.onNodeWithText("Server").assertDoesNotExist()
    }

    @Test
    fun w1_themeReadFromDataStoreBeforeTheFirstFrame() {
        compose.setContent {
            val settings: UserSettings? by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            settings ?: return@setContent
            ProxyFlowTheme { ProxyFlowApp(viewModelFactory = container.viewModelFactory, version = "test") }
        }
        openFormThenSystemBack()
    }

    @Test
    fun w2_enableEdgeToEdgeBeforeSetContent() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { ProxyFlowTheme { ProxyFlowApp(viewModelFactory = container.viewModelFactory, version = "test") } }
        openFormThenSystemBack()
    }

    @Test
    fun w3_enableEdgeToEdgeFromComposition() {
        compose.setContent {
            DisposableEffect(Unit) {
                compose.activity.enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { false },
                    navigationBarStyle = SystemBarStyle.auto(Color.WHITE, Color.BLACK) { false },
                )
                onDispose {}
            }
            ProxyFlowTheme { ProxyFlowApp(viewModelFactory = container.viewModelFactory, version = "test") }
        }
        openFormThenSystemBack()
    }
}
