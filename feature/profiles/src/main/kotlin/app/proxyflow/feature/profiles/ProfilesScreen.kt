package app.proxyflow.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfCard
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfEmptyState
import app.proxyflow.core.designsystem.component.PfLoadingScreen
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.MonoSmall
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ProxyProfile

@Composable
internal fun ProfilesRoute(
    viewModel: ProfilesViewModel,
    onAddProxy: () -> Unit,
    onEditProxy: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProfilesScreen(
        state = state,
        onAddProxy = onAddProxy,
        onEditProxy = onEditProxy,
        onUseProxy = viewModel::setActive,
        onDeleteProxy = viewModel::delete,
    )
}

@Composable
fun ProfilesScreen(
    state: ProfilesUiState,
    onAddProxy: () -> Unit,
    onEditProxy: (String) -> Unit,
    onUseProxy: (String) -> Unit,
    onDeleteProxy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saved by id so the confirmation survives rotation; it closes by itself if the profile disappears.
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = stringResource(R.string.profiles_title)) },
        floatingActionButton = {
            if (state.profiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.profiles_add)) },
                    icon = { Icon(PfIcons.Add, contentDescription = null) },
                    onClick = onAddProxy,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> PfLoadingScreen(Modifier.padding(padding))
            state.profiles.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                PfEmptyState(
                    icon = PfIcons.Profiles,
                    title = stringResource(R.string.profiles_empty_title),
                    body = stringResource(R.string.profiles_empty_body),
                    actionLabel = stringResource(R.string.profiles_add),
                    onAction = onAddProxy,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(start = Spacing.md, end = Spacing.md, top = Spacing.sm, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // M2 only: ADR-0008. Removed when M3 stores profiles with Room and the Keystore.
                item(key = "memory-only") { PfBanner(text = stringResource(R.string.profiles_memory_only), tone = BannerTone.Info) }
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileItem(
                        profile = profile,
                        active = profile.id == state.activeProfileId,
                        onClick = { onEditProxy(profile.id) },
                        onUse = { onUseProxy(profile.id) },
                        onDelete = { pendingDeleteId = profile.id },
                    )
                }
            }
        }
    }
    state.profiles.firstOrNull { it.id == pendingDeleteId }?.let { profile ->
        PfConfirmDialog(
            title = stringResource(R.string.profiles_delete_title, profile.displayName),
            text = stringResource(R.string.profiles_delete_body),
            confirmLabel = stringResource(R.string.profiles_delete),
            dismissLabel = stringResource(R.string.profiles_cancel),
            onConfirm = {
                onDeleteProxy(profile.id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
            destructive = true,
        )
    }
}

@Composable
private fun ProfileItem(
    profile: ProxyProfile,
    active: Boolean,
    onClick: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    PfCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                    if (active) {
                        Text(
                            stringResource(R.string.profiles_active).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    buildList {
                        add(profile.protocol.name)
                        add(stringResource(if (profile.useTls) R.string.profiles_tls else R.string.profiles_not_encrypted))
                        if (profile.requiresSignIn) add(stringResource(R.string.profiles_sign_in))
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(profile.endpoint, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(PfIcons.MoreVert, contentDescription = stringResource(R.string.profiles_more_actions, profile.displayName))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!active) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_use)) },
                            leadingIcon = { Icon(PfIcons.Check, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onUse()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profiles_edit)) },
                        leadingIcon = { Icon(PfIcons.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onClick()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profiles_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(PfIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
