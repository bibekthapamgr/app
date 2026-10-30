package app.proxyflow.feature.profiles

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.InMemoryProfileRepository
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.DumpOnFailureRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// The form is longer than a phone screen, so fields further down are scrolled to before they are checked.
@RunWith(AndroidJUnit4::class)
class AddEditProxyScreenTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule(order = 1)
    val dumpOnFailure = DumpOnFailureRule { compose.onAllNodes(isRoot()).printToString() }

    /** Shows the screen with a plain state holder, so each test can change the state. */
    private fun showForm(initial: AddEditUiState = AddEditUiState()) {
        var state by mutableStateOf(initial)
        val actions = AddEditActions(
            onRequiresSignInChange = { state = state.copy(input = state.input.copy(requiresSignIn = it)) },
            onUseTlsChange = { state = state.copy(input = state.input.copy(useTls = it)) },
        )
        compose.setContent { ProxyFlowTheme { AddEditProxyScreen(state, actions) } }
    }

    @Test
    fun plaintextWarningShowsUntilTlsIsTurnedOn() {
        showForm()
        compose.onNodeWithText("Not encrypted").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TLS server name (optional)").assertDoesNotExist()

        compose.onNodeWithText("Encrypt connection (TLS)").performScrollTo().performClick()

        compose.onNodeWithText("Not encrypted").assertDoesNotExist()
        compose.onNodeWithText("TLS server name (optional)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun signInFieldsAppearOnlyWhenTheProxyNeedsThem() {
        showForm()
        compose.onNodeWithText("Username").assertDoesNotExist()
        compose.onNodeWithText("Proxy requires sign-in").performScrollTo().performClick()
        compose.onNodeWithText("Username").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Password").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun savedPasswordIsNeverShownButItsPresenceIs() {
        showForm(
            AddEditUiState(isEdit = true, input = ProxyProfileInput(requiresSignIn = true, username = "user"), hasSavedPassword = true),
        )
        compose.onNodeWithText("Edit proxy").assertIsDisplayed()
        compose.onNodeWithText("Leave empty to keep the saved password").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun formWindowIsHiddenFromScreenshotsAndRecents() {
        var shown by mutableStateOf(true)
        compose.setContent {
            val viewModel = remember { AddEditProxyViewModel(SavedStateHandle(), InMemoryProfileRepository()) }
            ProxyFlowTheme {
                if (shown) AddEditProxyRoute(viewModel = viewModel, onDone = {})
            }
        }
        assertNotEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)

        shown = false
        compose.waitForIdle()
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }
}
