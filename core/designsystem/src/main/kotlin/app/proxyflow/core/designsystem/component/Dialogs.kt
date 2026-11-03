package app.proxyflow.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Confirmation dialog. With [destructive] the confirm label is shown in the
 * error colour (delete, discard), matching boards C3 and D8, and taps on it that
 * pass through another app's window are dropped ([refusingObscuredTouches], M8).
 * [neutralLabel] adds a third button (H1's "Open VPN settings").
 */
@Composable
fun PfConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    neutralLabel: String? = null,
    onNeutral: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = if (destructive) Modifier.refusingObscuredTouches() else Modifier) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            if (neutralLabel == null) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            } else {
                Row {
                    TextButton(onClick = onNeutral) { Text(neutralLabel) }
                    TextButton(onClick = onDismiss) { Text(dismissLabel) }
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * Pick one of a few options (e.g. Theme: System default / Light / Dark).
 * Choosing an option applies it at once, so there is only a dismiss button.
 */
@Composable
fun <T> PfChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    PfRadioRow(text = label(option), selected = option == selected, onClick = { onSelect(option) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
        modifier = modifier,
    )
}
