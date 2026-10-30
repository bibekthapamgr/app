package app.proxyflow

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.InMemoryProfileRepository
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.testing.DumpOnFailureRule
import app.proxyflow.feature.profiles.AddEditActions
import app.proxyflow.feature.profiles.AddEditProxyDestination
import app.proxyflow.feature.profiles.AddEditProxyScreen
import app.proxyflow.feature.profiles.AddEditProxyViewModel
import app.proxyflow.feature.profiles.AddEditUiState
import app.proxyflow.feature.profiles.addEditProxyScreen
import app.proxyflow.ui.ProxyFlowApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TEMPORARY (removed before merge): a bare two-screen NavHost handles system
 * Back correctly, the app does not. Each test adds one part of the app to the
 * bare setup, to find the part that breaks it.
 */
@RunWith(AndroidJUnit4::class)
class BackDiagnosticTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.describeScreen() }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private val formFactory = viewModelFactory {
        initializer { AddEditProxyViewModel(createSavedStateHandle(), InMemoryProfileRepository()) }
    }

    @Test
    fun v1_realFormDestinationPopsOnSystemBack() {
        compose.setContent {
            val nav = rememberNavController()
            ProxyFlowTheme {
                NavHost(nav, startDestination = "a") {
                    composable("a") { Text("Screen A") }
                    addEditProxyScreen(formFactory, onDone = { nav.popBackStack<AddEditProxyDestination>(inclusive = true) })
                }
            }
            LaunchedEffect(Unit) { nav.navigate(AddEditProxyDestination()) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Server").assertExists()
        systemBack()
        compose.onNodeWithText("Screen A").assertExists()
        compose.onNodeWithText("Server").assertDoesNotExist()
    }

    @Test
    fun v2_statelessFormPopsOnSystemBack() {
        compose.setContent {
            val nav = rememberNavController()
            ProxyFlowTheme {
                NavHost(nav, startDestination = "a") {
                    composable("a") { Text("Screen A") }
                    composable("b") { AddEditProxyScreen(AddEditUiState(), AddEditActions()) }
                }
            }
            LaunchedEffect(Unit) { nav.navigate("b") }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Server").assertExists()
        systemBack()
        compose.onNodeWithText("Screen A").assertExists()
        compose.onNodeWithText("Server").assertDoesNotExist()
    }

    @Test
    fun v2b_screenThatSetsFlagSecurePopsOnSystemBack() {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "a") {
                composable("a") { Text("Screen A") }
                composable("b") {
                    val window = LocalActivity.current?.window
                    DisposableEffect(window) {
                        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                    }
                    Text("Screen B")
                }
            }
            LaunchedEffect(Unit) { nav.navigate("b") }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Screen B").assertExists()
        systemBack()
        compose.onNodeWithText("Screen A").assertExists()
        compose.onNodeWithText("Screen B").assertDoesNotExist()
    }

    @Test
    fun v3_appShellInAPlainActivityPopsTheFormOnSystemBack() {
        val factory = (compose.activity.application as ProxyFlowApplication).container.viewModelFactory
        compose.setContent { ProxyFlowTheme { ProxyFlowApp(viewModelFactory = factory, version = "test") } }
        compose.waitForIdle()
        compose.onNodeWithText("Add proxy").performClick()
        compose.onNodeWithText("Server").assertExists()
        systemBack()
        compose.onNodeWithText("Add a proxy to get started").assertExists()
        compose.onNodeWithText("Server").assertDoesNotExist()
    }
}
