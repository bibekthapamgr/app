package app.proxyflow.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.PfCard
import app.proxyflow.core.designsystem.component.PfEmptyState
import app.proxyflow.core.designsystem.component.PfLoadingIndicator
import app.proxyflow.core.designsystem.component.PfPrimaryButton
import app.proxyflow.core.designsystem.component.PfStatusPill
import app.proxyflow.core.designsystem.component.PfTextButton
import app.proxyflow.core.designsystem.component.StatusTone
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.MonoSmall
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile

@Composable
internal fun HomeRoute(
    viewModel: HomeViewModel,
    onAddProxy: () -> Unit,
    onChangeProfile: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state = state, onAddProxy = onAddProxy, onChangeProfile = onChangeProfile, onConnect = viewModel::connect)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onAddProxy: () -> Unit,
    onChangeProfile: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Default insets: status bar on top; the app's bottom bar has already consumed the bottom.
    Scaffold(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppHeader()
            when (state) {
                HomeUiState.Loading -> PfLoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
                HomeUiState.NoProfile -> PfEmptyState(
                    icon = PfIcons.Logo,
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    actionLabel = stringResource(R.string.home_add_proxy),
                    onAction = onAddProxy,
                )
                is HomeUiState.Ready -> ReadyContent(state, onChangeProfile, onConnect)
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(PfIcons.Logo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Text(
            stringResource(R.string.home_app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun ReadyContent(state: HomeUiState.Ready, onChangeProfile: () -> Unit, onConnect: () -> Unit) {
    val (statusText, tone) = statusLabel(state.status)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PfStatusPill(text = statusText, tone = tone)
        if (state.status == ConnectionStatus.Disconnected) {
            Text(
                stringResource(R.string.home_normal_network),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    ProfileCard(state.profile, onChangeProfile)
    PfPrimaryButton(
        text = stringResource(R.string.home_connect),
        onClick = onConnect,
        enabled = state.canConnect,
        icon = PfIcons.Power,
        modifier = Modifier.fillMaxWidth(),
    )
    if (!state.canConnect) {
        Text(
            stringResource(R.string.home_connect_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileCard(profile: ProxyProfile, onChangeProfile: () -> Unit) {
    PfCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.home_proxy_label).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(
                        profile.protocol.name,
                        stringResource(if (profile.useTls) R.string.home_tls else R.string.home_not_encrypted),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(profile.endpoint, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PfTextButton(text = stringResource(R.string.home_change_profile), onClick = onChangeProfile)
        }
    }
}

@Composable
private fun statusLabel(status: ConnectionStatus): Pair<String, StatusTone> = when (status) {
    ConnectionStatus.Disconnected -> stringResource(R.string.home_status_disconnected) to StatusTone.Off
    ConnectionStatus.Connecting -> stringResource(R.string.home_status_connecting) to StatusTone.Transition
    ConnectionStatus.Connected -> stringResource(R.string.home_status_connected) to StatusTone.Connected
    ConnectionStatus.Reconnecting -> stringResource(R.string.home_status_reconnecting) to StatusTone.Transition
    ConnectionStatus.Blocked -> stringResource(R.string.home_status_blocked) to StatusTone.Error
    ConnectionStatus.Offline -> stringResource(R.string.home_status_offline) to StatusTone.Transition
}
