package app.proxyflow.feature.profiles

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.DumpOnFailureRule
import app.proxyflow.core.testing.FakeConnectionTester
import app.proxyflow.core.testing.TestProfiles
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
    val dumpOnFailure = DumpOnFailureRule { compose.onAllNodes(isRoot()).printToString(maxDepth = Int.MAX_VALUE) }

    private val editing =
        AddEditUiState(isEdit = true, input = ProxyProfileInput(requiresSignIn = true, username = "user"), hasSavedPassword = true)

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
        showForm(editing.copy(keepsSavedPassword = true))
        compose.onNodeWithText("Edit proxy").assertIsDisplayed()
        compose.onNodeWithText("Leave empty to keep the saved password").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aSavedPasswordThatCannotFollowTheChangesIsAskedForAgain() {
        showForm(editing.copy(keepsSavedPassword = false))
        compose.onNodeWithText(REENTER).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Leave empty to keep the saved password").assertDoesNotExist()
    }

    @Test
    fun afterSaveTheErrorStillSaysWhyThePasswordIsNeeded() {
        showForm(editing.copy(errors = mapOf(ProfileField.Password to ProfileError.PasswordRequired)))
        compose.onNodeWithText(REENTER).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enter the password").assertDoesNotExist()
    }

    @Test
    fun aNewSignInWithoutPasswordSaysToEnterOne() {
        showForm(
            AddEditUiState(
                input = ProxyProfileInput(requiresSignIn = true, username = "user"),
                errors = mapOf(ProfileField.Password to ProfileError.PasswordRequired),
            ),
        )
        compose.onNodeWithText("Enter the password").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun formWindowIsHiddenFromScreenshotsAndRecents() {
        var shown by mutableStateOf(true)
        compose.setContent {
            val viewModel = remember { AddEditProxyViewModel(SavedStateHandle(), TestProfiles().repository, FakeConnectionTester()) }
            ProxyFlowTheme {
                if (shown) AddEditProxyRoute(viewModel = viewModel, onDone = {})
            }
        }
        assertNotEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)

        shown = false
        compose.waitForIdle()
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }

    @Test
    fun unreadableCredentialsAreExplainedWhereTheyAreEnteredAgain() {
        showForm(AddEditUiState(isEdit = true, input = ProxyProfileInput(requiresSignIn = true), credentialsUnreadable = true))
        compose.onNodeWithText("Re-enter your username and password").assertIsDisplayed()
        compose.onNodeWithText("can't be read on this phone any more", substring = true).assertIsDisplayed()
    }

    @Test
    fun aFailedSaveIsReported() {
        showForm(AddEditUiState(saveFailed = true))
        compose.onNodeWithText("Couldn't save this proxy. Try again.").assertIsDisplayed()
    }

    @Test
    fun testConnectionNeverShowsAPassItWasNotGiven() {
        var tests = 0
        var state by mutableStateOf(AddEditUiState())
        compose.setContent { ProxyFlowTheme { AddEditProxyScreen(state, AddEditActions(onTest = { tests++ })) } }

        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        assertEquals(1, tests)

        state = state.copy(test = ConnectionTestState.Testing)
        compose.onNodeWithText("Testing the connection…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Test connection").assertIsNotEnabled()

        state = state.copy(test = ConnectionTestState.Done(ConnectionTestResult.Unavailable))
        compose.onNodeWithText("Testing isn't available yet").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Nothing was sent to your proxy", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Test passed").assertDoesNotExist()

        state = state.copy(test = ConnectionTestState.Done(ConnectionTestResult.Failed("AUTH_FAILED")))
        compose.onNodeWithText("Test failed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("AUTH_FAILED", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Test passed").assertDoesNotExist()

        state = state.copy(test = ConnectionTestState.Done(ConnectionTestResult.Passed(latencyMillis = 87)))
        compose.onNodeWithText("Test passed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("87 ms", substring = true).assertIsDisplayed()
    }

    private companion object {
        const val REENTER = "Enter the password again: the server, port, protocol, username or TLS setting changed"
    }
}
