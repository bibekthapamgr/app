package app.proxyflow.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.component.PfChoiceDialog
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfListRow
import app.proxyflow.core.designsystem.component.PfPasswordField
import app.proxyflow.core.designsystem.component.PfPrimaryButton
import app.proxyflow.core.designsystem.component.PfTextField
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule
    val compose = createComposeRule()

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
