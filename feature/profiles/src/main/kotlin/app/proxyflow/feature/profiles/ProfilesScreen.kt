package app.proxyflow.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.PfCard
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfEmptyState
import app.proxyflow.core.designsystem.component.PfLoadingScreen
import app.proxyflow.core.designsystem.component.PfProfileTestLine
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.MonoSmall
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfile
import kotlinx.coroutines.delay

@Composable
internal fun ProfilesRoute(viewModel: ProfilesViewModel, onAddProxy: () -> Unit, onEditProxy: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Copy names follow the phone's language ("Office (copy)", "Office (copy 2)").
    val copyPattern = stringResource(R.string.profiles_copy_name)
    val numberedCopyPattern = stringResource(R.string.profiles_copy_name_numbered)
    val locale = LocalConfiguration.current.locales[0]
    // "tested 2 min ago" moves on with the clock, on whole minutes, while a result is shown.
    val ticking = state.lastTests.isNotEmpty()
    val now by produceState(System.currentTimeMillis(), ticking) {
        while (ticking) {
            value = System.currentTimeMillis()
            delay(MINUTE_MILLIS - value % MINUTE_MILLIS)
        }
    }
    ProfilesScreen(
        state = state,
        nowMillis = now,
        onAddProxy = onAddProxy,
        onEditProxy = onEditProxy,
        onUseProxy = viewModel::setActive,
        onDeleteProxy = viewModel::delete,
        onDuplicateProxy = { id ->
            viewModel.duplicate(id) { base, number ->
                if (number == 1) copyPattern.format(locale, base) else numberedCopyPattern.format(locale, base, number)
            }
        },
        onFailureShown = viewModel::onFailureShown,
        onTestProxy = viewModel::test,
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
    onDuplicateProxy: (String) -> Unit = {},
    onFailureShown: () -> Unit = {},
    onTestProxy: (String) -> Unit = {},
    nowMillis: Long = 0L,
) {
    // Saved by id so the confirmation survives rotation; it closes by itself if the profile disappears.
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    // "Use this proxy" while a connection runs on another saved profile switches it (J11): asked first, as Home's picker
    // says it before the tap, in the words for the connection's state — once connected, traffic is held until the new
    // proxy is checked; during a first connect, the other proxy is checked instead. A connection whose profile was
    // deleted has none to name: the session stops it as soon as it sees the deletion (VpnRuntime's watch) — once
    // connected, blocked, and the switch is that block's way out, at once. The question closes, not applied, if the
    // connection or either profile goes away meanwhile.
    var pendingUseId by rememberSaveable { mutableStateOf<String?>(null) }
    val switchingFrom = state.profiles.firstOrNull { it.id == state.connectedProfileId }
    val snackbar = remember { SnackbarHostState() }
    val failure = state.failed?.let { action ->
        stringResource(
            when (action) {
                ProfileAction.Use -> R.string.profiles_use_failed
                ProfileAction.Duplicate -> R.string.profiles_duplicate_failed
                ProfileAction.Delete -> R.string.profiles_delete_failed
                ProfileAction.Test -> R.string.profiles_test_failed
            },
        )
    }
    LaunchedEffect(state.failed) {
        if (failure != null) {
            snackbar.showSnackbar(failure)
            onFailureShown()
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = stringResource(R.string.profiles_title)) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.profiles.isNotEmpty()) {
                // Material's extended button is named by its icon (its text is left out of what TalkBack reads), so the
                // icon carries the words.
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.profiles_add)) },
                    icon = { Icon(PfIcons.Add, contentDescription = stringResource(R.string.profiles_add)) },
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
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileItem(
                        profile = profile,
                        active = profile.id == state.activeProfileId,
                        lastTest = state.lastTests[profile.id],
                        testing = profile.id in state.testing,
                        nowMillis = nowMillis,
                        onClick = { onEditProxy(profile.id) },
                        onUse = {
                            if (switchingFrom != null && switchingFrom.id != profile.id) {
                                pendingUseId = profile.id
                            } else {
                                onUseProxy(profile.id)
                            }
                        },
                        onTest = { onTestProxy(profile.id) },
                        onDuplicate = { onDuplicateProxy(profile.id) },
                        onDelete = { pendingDeleteId = profile.id },
                    )
                }
            }
        }
    }
    state.profiles.firstOrNull { it.id == pendingDeleteId }?.let { profile ->
        // J11: the profile a connection uses is deleted only together with ending that connection.
        val inUse = profile.id == state.connectedProfileId
        PfConfirmDialog(
            title = stringResource(
                if (inUse) R.string.profiles_disconnect_delete_title else R.string.profiles_delete_title,
                profile.displayName,
            ),
            text = if (inUse) {
                stringResource(R.string.profiles_disconnect_delete_body) + "\n\n" + stringResource(R.string.profiles_delete_body)
            } else {
                stringResource(R.string.profiles_delete_body)
            },
            confirmLabel = stringResource(if (inUse) R.string.profiles_disconnect_delete else R.string.profiles_delete),
            dismissLabel = stringResource(R.string.profiles_cancel),
            onConfirm = {
                onDeleteProxy(profile.id)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null },
            destructive = true,
        )
    }
    val switchingTo = state.profiles.firstOrNull { it.id == pendingUseId }?.takeIf { switchingFrom != null && it.id != switchingFrom.id }
    LaunchedEffect(state.loading, pendingUseId, switchingTo == null) {
        if (!state.loading && pendingUseId != null && switchingTo == null) pendingUseId = null
    }
    if (switchingFrom != null && switchingTo != null) {
        PfConfirmDialog(
            title = stringResource(R.string.profiles_switch_title, switchingTo.displayName),
            text = stringResource(
                if (state.connectionHoldsTraffic) R.string.profiles_switch_body else R.string.profiles_switch_checking_body,
                switchingFrom.displayName,
                switchingTo.displayName,
            ),
            confirmLabel = stringResource(R.string.profiles_switch_confirm),
            dismissLabel = stringResource(R.string.profiles_cancel),
            onConfirm = {
                onUseProxy(switchingTo.id)
                pendingUseId = null
            },
            onDismiss = { pendingUseId = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileItem(
    profile: ProxyProfile,
    active: Boolean,
    lastTest: ProfileTest?,
    testing: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
    onUse: () -> Unit,
    onTest: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    PfCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // The label goes under a name too long to leave it room (large fonts), never squeezed letter by letter.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    if (active) {
                        Text(
                            stringResource(R.string.profiles_active).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterVertically),
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
                PfProfileTestLine(profile, lastTest, testing, nowMillis)
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
                        text = { Text(stringResource(R.string.profiles_test)) },
                        leadingIcon = { Icon(PfIcons.Bolt, contentDescription = null) },
                        enabled = !testing,
                        onClick = {
                            menuOpen = false
                            onTest()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.profiles_duplicate)) },
                        leadingIcon = { Icon(PfIcons.Copy, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDuplicate()
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

private const val MINUTE_MILLIS = 60_000L
