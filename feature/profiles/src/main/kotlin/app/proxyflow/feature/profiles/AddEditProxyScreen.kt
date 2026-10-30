package app.proxyflow.feature.profiles

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfChoiceChips
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfPasswordField
import app.proxyflow.core.designsystem.component.PfPrimaryButton
import app.proxyflow.core.designsystem.component.PfSwitchRow
import app.proxyflow.core.designsystem.component.PfTextButton
import app.proxyflow.core.designsystem.component.PfTextField
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProxyProtocol

@Composable
internal fun AddEditProxyRoute(viewModel: AddEditProxyViewModel, onDone: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    SecureWindow()
    AddEditProxyScreen(
        state = state,
        actions = AddEditActions(
            onNameChange = viewModel::onNameChange,
            onProtocolChange = viewModel::onProtocolChange,
            onServerChange = viewModel::onServerChange,
            onPortChange = viewModel::onPortChange,
            onRequiresSignInChange = viewModel::onRequiresSignInChange,
            onUsernameChange = viewModel::onUsernameChange,
            onPasswordChange = viewModel::onPasswordChange,
            onUseTlsChange = viewModel::onUseTlsChange,
            onTlsServerNameChange = viewModel::onTlsServerNameChange,
            onSave = viewModel::save,
            onBack = onDone,
        ),
    )
}

/** Callbacks of the Add/Edit form, grouped so the screen stays easy to preview and test. */
data class AddEditActions(
    val onNameChange: (String) -> Unit = {},
    val onProtocolChange: (ProxyProtocol) -> Unit = {},
    val onServerChange: (String) -> Unit = {},
    val onPortChange: (String) -> Unit = {},
    val onRequiresSignInChange: (Boolean) -> Unit = {},
    val onUsernameChange: (String) -> Unit = {},
    val onPasswordChange: (String) -> Unit = {},
    val onUseTlsChange: (Boolean) -> Unit = {},
    val onTlsServerNameChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onBack: () -> Unit = {},
)

@Composable
fun AddEditProxyScreen(state: AddEditUiState, actions: AddEditActions, modifier: Modifier = Modifier) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val back: () -> Unit = {
        if (state.dirty) {
            confirmDiscard = true
        } else {
            actions.onBack()
        }
    }
    BackHandler(enabled = state.dirty, onBack = { confirmDiscard = true })

    val title = stringResource(if (state.isEdit) R.string.edit_title_edit else R.string.edit_title_add)
    Scaffold(
        modifier = modifier,
        topBar = {
            PfTopAppBar(
                title = title,
                onBack = back,
                actions = { PfTextButton(text = stringResource(R.string.edit_save), onClick = actions.onSave, enabled = !state.saving) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (state.errors.isNotEmpty()) {
                val count = state.errors.size
                PfBanner(text = pluralStringResource(R.plurals.edit_fix_fields, count, count), tone = BannerTone.Error)
            }
            FormFields(state, actions)
            Spacer(Modifier.height(Spacing.md))
            PfPrimaryButton(
                text = stringResource(R.string.edit_save),
                onClick = actions.onSave,
                loading = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmDiscard) {
        PfConfirmDialog(
            title = stringResource(R.string.edit_discard_title),
            text = state.originalName?.let { stringResource(R.string.edit_discard_body_named, it) }
                ?: stringResource(R.string.edit_discard_body),
            confirmLabel = stringResource(R.string.edit_discard),
            dismissLabel = stringResource(R.string.edit_keep_editing),
            onConfirm = {
                confirmDiscard = false
                actions.onBack()
            },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
}

@Composable
private fun FormFields(state: AddEditUiState, actions: AddEditActions) {
    val input = state.input
    val errors = state.errors
    PfTextField(
        value = input.name,
        onValueChange = actions.onNameChange,
        label = stringResource(R.string.edit_name),
        errorText = errors[ProfileField.Name]?.let { errorText(it) },
    )
    Text(stringResource(R.string.edit_protocol), style = MaterialTheme.typography.labelLarge)
    PfChoiceChips(
        options = ProxyProtocol.entries,
        selected = input.protocol,
        label = { it.name },
        onSelect = actions.onProtocolChange,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PfTextField(
            value = input.server,
            onValueChange = actions.onServerChange,
            label = stringResource(R.string.edit_server),
            errorText = errors[ProfileField.Server]?.let { errorText(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.weight(0.64f),
        )
        PfTextField(
            value = input.port,
            onValueChange = actions.onPortChange,
            label = stringResource(R.string.edit_port),
            supportingText = stringResource(R.string.edit_port_hint),
            errorText = errors[ProfileField.Port]?.let { errorText(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(0.36f),
        )
    }
    PfSwitchRow(
        title = stringResource(R.string.edit_sign_in),
        subtitle = stringResource(R.string.edit_sign_in_hint),
        checked = input.requiresSignIn,
        onCheckedChange = actions.onRequiresSignInChange,
    )
    if (input.requiresSignIn) {
        PfTextField(
            value = input.username,
            onValueChange = actions.onUsernameChange,
            label = stringResource(R.string.edit_username),
            errorText = errors[ProfileField.Username]?.let { errorText(it) },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        PfPasswordField(
            value = input.password,
            onValueChange = actions.onPasswordChange,
            label = stringResource(R.string.edit_password),
            supportingText = if (state.hasSavedPassword) stringResource(R.string.edit_password_keep) else null,
            errorText = errors[ProfileField.Password]?.let { errorText(it) },
        )
    }
    PfSwitchRow(
        title = stringResource(R.string.edit_tls),
        subtitle = stringResource(R.string.edit_tls_hint),
        checked = input.useTls,
        onCheckedChange = actions.onUseTlsChange,
    )
    if (input.useTls) {
        PfTextField(
            value = input.tlsServerName,
            onValueChange = actions.onTlsServerNameChange,
            label = stringResource(R.string.edit_tls_server_name),
            supportingText = stringResource(R.string.edit_tls_server_name_hint),
            errorText = errors[ProfileField.TlsServerName]?.let { errorText(it) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
    } else {
        // Board D4: always shown while TLS is off, stronger when a password would travel in the clear.
        PfBanner(
            title = stringResource(R.string.edit_plaintext_title),
            text = stringResource(R.string.edit_plaintext_body),
            tone = if (input.requiresSignIn) BannerTone.Error else BannerTone.Warning,
        )
    }
}

@Composable
private fun errorText(error: ProfileError): String = stringResource(
    when (error) {
        ProfileError.NameTooLong -> R.string.error_name_too_long
        ProfileError.ServerInvalid -> R.string.error_server_invalid
        ProfileError.PortOutOfRange -> R.string.error_port_range
        ProfileError.UsernameRequired -> R.string.error_username_required
        ProfileError.UsernameHasColon -> R.string.error_username_colon
        ProfileError.UsernameTooLong -> R.string.error_username_too_long
        ProfileError.PasswordWithoutUsername -> R.string.error_password_without_username
        ProfileError.PasswordTooLong -> R.string.error_password_too_long
        ProfileError.TlsServerNameInvalid -> R.string.error_tls_server_name
    },
)

/**
 * Keeps credentials out of screenshots, screen recordings and the recents
 * thumbnail while this screen is shown (docs/m1/04 §1, ADR-0006).
 */
@Composable
private fun SecureWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
