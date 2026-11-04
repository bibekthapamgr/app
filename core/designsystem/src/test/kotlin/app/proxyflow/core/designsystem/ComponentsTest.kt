package app.proxyflow.core.designsystem

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.component.PfChoiceDialog
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfListRow
import app.proxyflow.core.designsystem.component.PfPasswordField
import app.proxyflow.core.designsystem.component.PfPrimaryButton
import app.proxyflow.core.designsystem.component.PfProfileTestLine
import app.proxyflow.core.designsystem.component.PfSectionHeader
import app.proxyflow.core.designsystem.component.PfTextField
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * TalkBack announces a test's end, not each minute after it: the line is a live region while the test runs and while
     * its result reads "just now", then not.
     */
    @Test
    fun aTestLineAnnouncesTheTestsEndOnly() {
        val profile = ProxyProfile("p1", "Tokyo", ProxyProtocol.SOCKS5, "tokyo.example.com", 1080)
        var testing by mutableStateOf(true)
        var lastTest by mutableStateOf<ProfileTest?>(null)
        var now by mutableStateOf(1_000_000L)
        compose.setContent { ProxyFlowTheme { PfProfileTestLine(profile, lastTest, testing, now) } }
        val live = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

        compose.onNode(live and hasAnyDescendant(hasText("Testing…")), useUnmergedTree = true).assertExists()
        testing = false
        lastTest = ProfileTest(atMillis = now, code = null, latencyMillis = 182)
        compose.onNode(live and hasAnyDescendant(hasText("tested just now", substring = true)), useUnmergedTree = true).assertExists()
        now += 60_000
        compose.onNode(hasText("tested 1 min ago", substring = true), useUnmergedTree = true).assertExists()
        compose.onNode(live, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun aSectionHeaderIsAHeading() {
        compose.setContent { ProxyFlowTheme { PfSectionHeader("Connection") } }
        compose.onNode(hasText("CONNECTION") and isHeading()).assertExists()
    }

    @Test
    fun passwordFieldTogglesBetweenHiddenAndShown() {
        compose.setContent {
            ProxyFlowTheme {
                var value by remember { mutableStateOf("secret") }
                PfPasswordField(value = value, onValueChange = { value = it }, label = "Password")
            }
        }
        compose.onNodeWithContentDescription("Show password").performClick()
        compose.onNodeWithContentDescription("Hide password").performClick()
        compose.onNodeWithContentDescription("Show password").assertExists()
    }

    @Test
    fun credentialFieldsNeverPutTheirTextOnTheClipboard() {
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
        clipboard.clearPrimaryClip()
        compose.setContent {
            ProxyFlowTheme {
                Column {
                    PfPasswordField(value = "s3cret-pw", onValueChange = {}, label = "Password")
                    PfTextField(value = "office-user", onValueChange = {}, label = "Username", secret = true)
                    PfTextField(value = "Office", onValueChange = {}, label = "Name")
                }
            }
        }
        // The password field offers no Copy or Cut at all, not even while the password is shown and selected.
        compose.onNodeWithContentDescription("Show password").performClick()
        val password = compose.onNode(hasText("Password") and hasSetTextAction())
        password.performTextInputSelection(TextRange(0, "s3cret-pw".length))
        password.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CopyText))
        password.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CutText))

        // The username field offers Copy and Cut, as the text toolbar does, but neither reaches the clipboard
        // (Cut still removes the selected text).
        copyAll("Username", "office-user")
        val username = compose.onNode(hasText("Username") and hasSetTextAction())
        username.performTextInputSelection(TextRange(0, "office-user".length))
        username.performSemanticsAction(SemanticsActions.CutText)
        compose.waitForIdle()
        assertFalse("nothing copied", clipboard.hasPrimaryClip() && clipboard.primaryClip?.getItemAt(0)?.text?.isNotEmpty() == true)

        // Control: an ordinary field does copy, so a leak would have shown up above.
        copyAll("Name", "Office")
        compose.waitForIdle()
        assertEquals("Office", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    private fun copyAll(label: String, text: String) {
        val field = compose.onNode(hasText(label) and hasSetTextAction())
        field.performTextInputSelection(TextRange(0, text.length))
        field.performSemanticsAction(SemanticsActions.CopyText)
    }

    @Test
    fun errorTextReplacesTheHint() {
        compose.setContent {
            ProxyFlowTheme {
                PfTextField(
                    value = "0",
                    onValueChange = {},
                    label = "Port",
                    supportingText = "1–65535",
                    errorText = "Port must be between 1 and 65535",
                )
            }
        }
        compose.onNodeWithText("Port must be between 1 and 65535").assertExists()
        compose.onNodeWithText("1–65535").assertDoesNotExist()
    }

    @Test
    fun loadingButtonCannotBePressedTwice() {
        var clicks = 0
        compose.setContent { ProxyFlowTheme { PfPrimaryButton(text = "Save", onClick = { clicks++ }, loading = true) } }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        assertEquals(0, clicks)
    }

    @Test
    fun confirmDialogReportsTheChoice() {
        var confirmed = false
        var dismissed = false
        compose.setContent {
            ProxyFlowTheme {
                PfConfirmDialog(
                    title = "Delete \"Office HTTP\"?",
                    text = "This can't be undone.",
                    confirmLabel = "Delete",
                    dismissLabel = "Cancel",
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                    destructive = true,
                )
            }
        }
        compose.onNodeWithText("Cancel").performClick()
        assertFalse(confirmed)
        assertEquals(true, dismissed)
        compose.onNodeWithText("Delete").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun listRowIsOneTapTarget() {
        var clicks = 0
        compose.setContent { ProxyFlowTheme { PfListRow(title = "Theme", supportingText = "System default", onClick = { clicks++ }) } }
        compose.onNodeWithText("System default").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun choiceDialogMarksTheCurrentOptionAndReportsANewOne() {
        var chosen: String? = null
        compose.setContent {
            ProxyFlowTheme {
                PfChoiceDialog(
                    title = "Theme",
                    options = listOf("System default", "Light", "Dark"),
                    selected = "Light",
                    label = { it },
                    onSelect = { chosen = it },
                    onDismiss = {},
                    dismissLabel = "Cancel",
                )
            }
        }
        compose.onNodeWithText("Light").assertIsSelected()
        compose.onNodeWithText("Dark").performClick()
        assertEquals("Dark", chosen)
    }
}
