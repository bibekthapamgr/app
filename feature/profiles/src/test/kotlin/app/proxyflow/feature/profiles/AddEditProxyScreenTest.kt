package app.proxyflow.feature.profiles

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.InMemoryProfileRepository
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ProxyProfileInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddEditProxyScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

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
        compose.onNodeWithText("Not encrypted").assertIsDisplayed()
        compose.onNodeWithText("TLS server name (optional)").assertDoesNotExist()

        compose.onNodeWithText("Encrypt connection (TLS)").performClick()

        compose.onNodeWithText("Not encrypted").assertDoesNotExist()
        compose.onNodeWithText("TLS server name (optional)").assertIsDisplayed()
    }

    @Test
    fun signInFieldsAppearOnlyWhenTheProxyNeedsThem() {
        showForm()
        compose.onNodeWithText("Username").assertDoesNotExist()
        compose.onNodeWithText("Proxy requires sign-in").performClick()
        compose.onNodeWithText("Username").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
    }

    @Test
    fun savedPasswordIsNeverShownButItsPresenceIs() {
        showForm(
            AddEditUiState(isEdit = true, input = ProxyProfileInput(requiresSignIn = true, username = "user"), hasSavedPassword = true),
        )
        compose.onNodeWithText("Edit proxy").assertIsDisplayed()
        compose.onNodeWithText("Leave empty to keep the saved password").assertIsDisplayed()
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
