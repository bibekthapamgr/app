package app.proxyflow.feature.home

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfCard
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfEmptyState
import app.proxyflow.core.designsystem.component.PfLoadingIndicator
import app.proxyflow.core.designsystem.component.PfPrimaryButton
import app.proxyflow.core.designsystem.component.PfSecondaryButton
import app.proxyflow.core.designsystem.component.PfStatusPill
import app.proxyflow.core.designsystem.component.PfTextButton
import app.proxyflow.core.designsystem.component.StatusTone
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.MonoSmall
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.RoutingDiagnostics
import java.util.Locale
import kotlinx.coroutines.delay

/** What Home's buttons and dialogs do; the defaults do nothing (previews, screenshots, tests). */
data class HomeActions(
    val onAddProxy: () -> Unit = {},
    val onChangeProfile: () -> Unit = {},
    val onEditProfile: (String) -> Unit = {},
    /** Connect, Try again, Reconnect, Restart. */
    val onConnect: () -> Unit = {},
    /** Disconnect (B4), Cancel (B3). */
    val onDisconnect: () -> Unit = {},
    /** "Stop and use normal network" while traffic is held; asks first. */
    val onStop: () -> Unit = {},
    val onConfirmStop: () -> Unit = {},
    /** B9's Continue: opens Android's consent screen. */
    val onExplainerContinue: (Intent) -> Unit = {},
    val onDismissDialog: () -> Unit = {},
)

@Composable
internal fun HomeRoute(viewModel: HomeViewModel, onAddProxy: () -> Unit, onChangeProfile: () -> Unit, onEditProfile: (String) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    // Asked once per app run at Connect on Android 13+; the VPN works without it (ADR-0010 §10).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.connect() }
    val connect = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            viewModel.takeNotificationRequest()
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.connect()
        }
    }
    val connected = (state as? HomeUiState.Ready)?.status == ConnectionStatus.Connected
    // The connected timer ticks on whole seconds, only while connected.
    val now by produceState(System.currentTimeMillis(), connected) {
        while (connected) {
            value = System.currentTimeMillis()
            delay(MILLIS_PER_SECOND - value % MILLIS_PER_SECOND)
        }
    }
    HomeScreen(
        state = state,
        nowMillis = now,
        actions = HomeActions(
            onAddProxy = onAddProxy,
            onChangeProfile = {
                viewModel.dismissError()
                onChangeProfile()
            },
            onEditProfile = { id ->
                viewModel.dismissError()
                onEditProfile(id)
            },
            onConnect = connect,
            onDisconnect = viewModel::disconnect,
            onStop = viewModel::requestStop,
            onConfirmStop = viewModel::confirmStop,
            onExplainerContinue = { intent ->
                viewModel.dismissDialog()
                try {
                    consent.launch(intent)
                } catch (e: ActivityNotFoundException) {
                    viewModel.onConsentResult(granted = false)
                }
            },
            onDismissDialog = viewModel::dismissDialog,
        ),
    )
}

@Composable
fun HomeScreen(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier, nowMillis: Long = 0L) {
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
                    onAction = actions.onAddProxy,
                )
                is HomeUiState.Ready -> {
                    ReadyContent(state, actions, nowMillis)
                    HomeDialogs(state, actions)
                }
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
private fun ReadyContent(state: HomeUiState.Ready, actions: HomeActions, nowMillis: Long) {
    val error = state.error
    when (state.status) {
        ConnectionStatus.Connecting -> ConnectingContent(state, actions)
        ConnectionStatus.Reconnecting -> ReconnectingContent(state, actions)
        ConnectionStatus.Connected -> ConnectedContent(state, actions, nowMillis)
        ConnectionStatus.Blocked, ConnectionStatus.Offline -> BlockedContent(state, actions)
        ConnectionStatus.Disconnected -> when {
            error == null -> DisconnectedContent(state, actions)
            // H1 is a dialog over the plain disconnected screen.
            error.code == ConnectionError.VPN_PERMISSION_DENIED -> DisconnectedContent(state, actions)
            error.code == ConnectionError.VPN_REVOKED -> RevokedContent(state, actions)
            else -> ConnectFailedContent(state, error, actions)
        }
    }
}

/** Board B2. */
@Composable
private fun DisconnectedContent(state: HomeUiState.Ready, actions: HomeActions) {
    StatusHeader(stringResource(R.string.home_status_disconnected), StatusTone.Off, stringResource(R.string.home_normal_network))
    ProfileCard(state.profile, actions.onChangeProfile)
    PfPrimaryButton(
        text = stringResource(R.string.home_connect),
        onClick = actions.onConnect,
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

/** Board B6b: the pre-flight check failed, and nothing was changed on the phone. */
@Composable
private fun ConnectFailedContent(state: HomeUiState.Ready, error: ConnectionError, actions: HomeActions) {
    StatusHeader(stringResource(R.string.home_status_disconnected), StatusTone.Off, null)
    PfBanner(title = errorTitle(error, state.profile), text = errorMessage(error, state.profile), tone = BannerTone.Error)
    Note(stringResource(R.string.home_not_connected_note))
    ErrorCode(error)
    ProfileCard(state.profile, actions.onChangeProfile)
    PfPrimaryButton(
        text = stringResource(R.string.home_try_again),
        onClick = actions.onConnect,
        icon = PfIcons.Refresh,
        modifier = Modifier.fillMaxWidth(),
    )
    PfSecondaryButton(
        text = stringResource(R.string.home_edit_profile),
        onClick = { actions.onEditProfile(state.profile.id) },
        icon = PfIcons.Edit,
        modifier = Modifier.fillMaxWidth(),
    )
    PfTextButton(
        text = stringResource(R.string.home_use_another_proxy),
        onClick = actions.onChangeProfile,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Board B6d: Android removed the VPN; apps use the normal network. */
@Composable
private fun RevokedContent(state: HomeUiState.Ready, actions: HomeActions) {
    StatusHeader(stringResource(R.string.home_status_disconnected), StatusTone.Off, null)
    PfBanner(
        title = stringResource(R.string.home_error_vpn_revoked_title),
        text = stringResource(R.string.home_error_vpn_revoked_message),
        tone = BannerTone.Warning,
    )
    Note(stringResource(R.string.home_revoked_note))
    Code(ConnectionError.VPN_REVOKED)
    ProfileCard(state.profile, actions.onChangeProfile)
    PfPrimaryButton(
        text = stringResource(R.string.home_reconnect),
        onClick = actions.onConnect,
        icon = PfIcons.Power,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Board B3: the check runs before anything changes on the phone. */
@Composable
private fun ConnectingContent(state: HomeUiState.Ready, actions: HomeActions) {
    StatusHeader(
        stringResource(R.string.home_status_connecting),
        StatusTone.Transition,
        stringResource(R.string.home_connecting_to, state.profile.displayName),
    )
    PfCard(modifier = Modifier.fillMaxWidth()) {
        val checking = state.connection.step != ConnectStep.StartingProtection
        Step(stringResource(R.string.home_step_permission), StepState.Done)
        Step(stringResource(R.string.home_step_checking), if (checking) StepState.Current else StepState.Done)
        Step(stringResource(R.string.home_step_starting), if (checking) StepState.Pending else StepState.Current)
    }
    Note(stringResource(R.string.home_connecting_note))
    PfSecondaryButton(
        text = stringResource(R.string.home_cancel),
        onClick = actions.onDisconnect,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Switching profiles or restarting: the VPN interface stays, so traffic is held meanwhile. */
@Composable
private fun ReconnectingContent(state: HomeUiState.Ready, actions: HomeActions) {
    StatusHeader(
        stringResource(R.string.home_status_reconnecting),
        StatusTone.Transition,
        stringResource(R.string.home_reconnecting_to, state.profile.displayName),
    )
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PfLoadingIndicator() }
    Note(stringResource(R.string.home_reconnecting_note))
    PfTextButton(
        text = stringResource(R.string.home_stop_normal_network),
        onClick = actions.onStop,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Board B4: what goes through the proxy, and the diagnostics since connecting (ADR-0011).
 * Traffic counters and active connections arrive with statistics in M7.
 */
@Composable
private fun ConnectedContent(state: HomeUiState.Ready, actions: HomeActions, nowMillis: Long) {
    StatusHeader(stringResource(R.string.home_status_connected), StatusTone.Connected, null)
    val since = state.connection.connectedSinceMillis
    if (since != null) {
        Text(
            elapsed(nowMillis - since),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        stringResource(R.string.home_connected_note),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        listOf(state.profile.displayName, protocolLabel(state.profile)).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    RoutingCard(state.diagnostics)
    DiagnosticsCard(state.profile, state.diagnostics)
    Note(stringResource(R.string.home_https_note))
    PfSecondaryButton(
        text = stringResource(R.string.home_disconnect),
        onClick = actions.onDisconnect,
        icon = PfIcons.Power,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The blocked layout (board B6a's): the interface stays up with nothing going around the proxy. */
@Composable
private fun BlockedContent(state: HomeUiState.Ready, actions: HomeActions) {
    val error = state.error ?: ConnectionError(ConnectionError.INTERNAL)
    val status = if (state.status == ConnectionStatus.Offline) R.string.home_status_offline else R.string.home_status_blocked
    StatusHeader(stringResource(status), StatusTone.Error, null)
    PfBanner(title = errorTitle(error, state.profile), text = errorMessage(error, state.profile), tone = BannerTone.Error)
    Note(stringResource(R.string.home_blocked_note))
    ErrorCode(error)
    if (error.code == ConnectionError.PROFILE_DELETED) {
        // Nothing to restart or edit: stopping is the way out, and it is still confirmed.
        PfPrimaryButton(
            text = stringResource(R.string.home_stop_normal_network),
            onClick = actions.onStop,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        PfPrimaryButton(
            text = stringResource(if (error.code == ConnectionError.ENGINE_STOPPED) R.string.home_restart else R.string.home_try_again),
            onClick = actions.onConnect,
            icon = PfIcons.Refresh,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error.code != ConnectionError.ENGINE_STOPPED) {
            PfSecondaryButton(
                text = stringResource(R.string.home_edit_profile),
                onClick = { actions.onEditProfile(state.profile.id) },
                icon = PfIcons.Edit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PfTextButton(
            text = stringResource(R.string.home_stop_normal_network),
            onClick = actions.onStop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HomeDialogs(state: HomeUiState.Ready, actions: HomeActions) {
    val dialog = state.dialog
    when {
        dialog is HomeDialog.VpnExplainer -> PfConfirmDialog(
            title = stringResource(R.string.home_vpn_explainer_title),
            text = listOf(
                stringResource(R.string.home_vpn_explainer_body),
                stringResource(R.string.home_vpn_explainer_no_servers),
                stringResource(R.string.home_vpn_explainer_key_icon),
                stringResource(R.string.home_vpn_explainer_one_vpn),
            ).joinToString("\n\n"),
            confirmLabel = stringResource(R.string.home_continue),
            dismissLabel = stringResource(R.string.home_cancel),
            onConfirm = { actions.onExplainerContinue(dialog.consent) },
            onDismiss = actions.onDismissDialog,
        )
        dialog == HomeDialog.ConfirmStop -> PfConfirmDialog(
            title = stringResource(R.string.home_stop_title),
            text = stringResource(R.string.home_stop_message),
            confirmLabel = stringResource(R.string.home_stop_normal_network),
            dismissLabel = stringResource(R.string.home_cancel),
            onConfirm = actions.onConfirmStop,
            onDismiss = actions.onDismissDialog,
            destructive = true,
        )
        dialog == HomeDialog.VpnPermissionDenied ||
            (state.status == ConnectionStatus.Disconnected && state.error?.code == ConnectionError.VPN_PERMISSION_DENIED) ->
            PfConfirmDialog(
                title = stringResource(R.string.home_error_vpn_permission_title),
                text = stringResource(R.string.home_error_vpn_permission_message) + "\n\n" +
                    stringResource(R.string.home_code, ConnectionError.VPN_PERMISSION_DENIED),
                confirmLabel = stringResource(R.string.home_try_again),
                dismissLabel = stringResource(R.string.home_not_now),
                onConfirm = actions.onConnect,
                onDismiss = actions.onDismissDialog,
            )
    }
}

@Composable
private fun StatusHeader(status: String, tone: StatusTone, line: String?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PfStatusPill(text = status, tone = tone)
        if (line != null) {
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorCode(error: ConnectionError) = Code(listOfNotNull(error.code, error.detail).joinToString(" · "))

@Composable
private fun Code(code: String) {
    Text(stringResource(R.string.home_code, code), style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private enum class StepState { Done, Current, Pending }

@Composable
private fun Step(text: String, step: StepState) {
    val description = stringResource(
        when (step) {
            StepState.Done -> R.string.home_step_done
            StepState.Current -> R.string.home_step_current
            StepState.Pending -> R.string.home_step_pending
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (step) {
                StepState.Done -> Icon(PfIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                StepState.Current -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                StepState.Pending -> Box(
                    Modifier
                        .size(16.dp)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (step == StepState.Pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** What goes through the proxy and what doesn't (docs/m6), with what the connection carried so far. */
@Composable
private fun RoutingCard(diagnostics: RoutingDiagnostics?) {
    PfCard(modifier = Modifier.fillMaxWidth()) {
        CardLabel(stringResource(R.string.home_route_title))
        InfoRow(
            stringResource(R.string.home_route_apps),
            diagnostics?.let { count(R.plurals.home_route_connections, it.connectionsOpened) }
                ?: stringResource(R.string.home_route_via_proxy),
        )
        InfoRow(
            stringResource(R.string.home_route_dns),
            diagnostics?.let { count(R.plurals.home_route_names, it.namesForProxy) } ?: stringResource(R.string.home_route_by_proxy),
        )
        InfoRow(stringResource(R.string.home_route_udp), stringResource(R.string.home_route_blocked))
    }
}

/**
 * The four kinds of problems M6 tells apart (ADR-0011): TLS to the proxy, DNS, unsupported
 * traffic and proxy timeouts, and the last failed connection's code. Counts only.
 */
@Composable
private fun DiagnosticsCard(profile: ProxyProfile, diagnostics: RoutingDiagnostics?) {
    val waiting = stringResource(R.string.home_diag_waiting)
    PfCard(modifier = Modifier.fillMaxWidth()) {
        CardLabel(stringResource(R.string.home_diag_title))
        val tls = diagnostics?.tls
        InfoRow(
            stringResource(R.string.home_diag_tls),
            when {
                !profile.useTls -> stringResource(R.string.home_diag_tls_off)
                tls == null -> waiting
                tls.failures == 0L -> stringResource(R.string.home_diag_no_failures)
                else -> count(R.plurals.home_diag_failed, tls.failures)
            },
            detail = tls?.lastCode?.takeIf { profile.useTls && tls.failures > 0 }?.let { errorTitle(ConnectionError(it), profile) },
        )
        val dns = diagnostics?.dns
        InfoRow(
            stringResource(R.string.home_diag_dns),
            when {
                dns == null -> waiting
                dns.total == 0L -> stringResource(R.string.home_diag_no_failures)
                else -> count(R.plurals.home_diag_failed, dns.total)
            },
            detail = dns?.let {
                listOfNotNull(
                    countOrNull(R.plurals.home_diag_dns_names, it.namesNotReached),
                    countOrNull(R.plurals.home_diag_dns_proxy_name, it.proxyNameLookups),
                    countOrNull(R.plurals.home_diag_dns_refused, it.queriesRefused),
                    countOrNull(R.plurals.home_diag_dns_dropped, it.lookupsDropped),
                    countOrNull(R.plurals.home_diag_dns_stale, it.staleAddresses),
                ).joinToString("\n").ifEmpty { null }
            },
        )
        val unsupported = diagnostics?.unsupported
        val blocked = unsupported?.let { it.udpBlocked + it.pingsDropped }
        InfoRow(
            stringResource(R.string.home_diag_unsupported),
            when {
                blocked == null -> waiting
                blocked == 0L -> stringResource(R.string.home_diag_none_blocked)
                else -> count(R.plurals.home_diag_blocked, blocked)
            },
            detail = unsupported?.takeIf { blocked != null && blocked > 0 }?.let {
                val ports = it.udpTopPorts.takeIf { list -> list.isNotEmpty() }?.joinToString(", ")
                val portsText = ports?.let { list -> pluralStringResource(R.plurals.home_diag_udp_ports, it.udpTopPorts.size, list) }
                val udp = countOrNull(R.plurals.home_diag_udp, it.udpBlocked)?.let { text -> portsText?.let { p -> "$text ($p)" } ?: text }
                listOfNotNull(udp, countOrNull(R.plurals.home_diag_pings, it.pingsDropped)).joinToString(" · ") + "\n" +
                    stringResource(R.string.home_diag_unsupported_note)
            },
        )
        InfoRow(
            stringResource(R.string.home_diag_timeouts),
            when {
                diagnostics == null -> waiting
                diagnostics.proxyTimeouts == 0L -> stringResource(R.string.home_diag_none)
                else -> count(R.plurals.home_diag_timed_out, diagnostics.proxyTimeouts)
            },
        )
        diagnostics?.lastProblem?.let { problem ->
            Text(
                stringResource(R.string.home_diag_last, listOfNotNull(problem.code, problem.detail).joinToString(" · ")),
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun InfoRow(label: String, value: String, detail: String? = null) {
    // One node for screen readers: label, value and detail are read together.
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true, properties = {}),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A count in words; counts beyond Int still show (as the largest Int, which never happens in practice). */
@Composable
private fun count(@PluralsRes plural: Int, value: Long): String {
    val n = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(plural, n, n)
}

@Composable
private fun countOrNull(@PluralsRes plural: Int, value: Long): String? = if (value > 0) count(plural, value) else null

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
private fun protocolLabel(profile: ProxyProfile): String =
    if (profile.useTls) stringResource(R.string.home_protocol_with_tls, profile.protocol.name) else profile.protocol.name

/** The catalogue title for [error] (docs/m1/04 §4). */
@Composable
private fun errorTitle(error: ConnectionError, profile: ProxyProfile): String = when (error.code) {
    "PROXY_UNREACHABLE" -> stringResource(R.string.home_error_unreachable_title)
    "PROXY_DNS_FAILED" -> stringResource(R.string.home_error_dns_title)
    "AUTH_REQUIRED" -> stringResource(R.string.home_error_auth_required_title)
    "AUTH_FAILED" -> stringResource(R.string.home_error_auth_failed_title)
    "TLS_UNTRUSTED" -> stringResource(R.string.home_error_tls_untrusted_title)
    "TLS_HOSTNAME" -> stringResource(R.string.home_error_tls_hostname_title)
    "TLS_EXPIRED" -> stringResource(R.string.home_error_tls_expired_title)
    "TLS_HANDSHAKE" -> stringResource(R.string.home_error_tls_handshake_title)
    "PROTOCOL_ERROR" -> stringResource(R.string.home_error_protocol_title, profile.protocol.name)
    "HANDSHAKE_TIMEOUT" -> stringResource(R.string.home_error_timeout_title)
    "TARGET_NOT_ALLOWED" -> stringResource(R.string.home_error_refused_title)
    "TARGET_UNREACHABLE", "TARGET_REFUSED" -> stringResource(R.string.home_error_unreachable_target_title)
    "PROXY_GENERAL_FAILURE" -> stringResource(R.string.home_error_proxy_failure_title)
    "TARGET_UNSUPPORTED" -> stringResource(R.string.home_error_unsupported_title)
    ConnectionError.CREDENTIAL_UNREADABLE -> stringResource(R.string.home_error_credentials_title)
    ConnectionError.ENGINE_STOPPED -> stringResource(R.string.home_error_engine_stopped_title)
    ConnectionError.PROFILE_DELETED -> stringResource(R.string.home_error_profile_deleted_title)
    else -> stringResource(R.string.home_error_internal_title)
}

/** The catalogue message for [error], filled in with the profile's server. */
@Composable
private fun errorMessage(error: ConnectionError, profile: ProxyProfile): String = when (error.code) {
    "PROXY_UNREACHABLE" -> stringResource(R.string.home_error_unreachable_message, profile.endpoint)
    "PROXY_DNS_FAILED" -> stringResource(R.string.home_error_dns_message, profile.host)
    "AUTH_REQUIRED" -> stringResource(R.string.home_error_auth_required_message)
    "AUTH_FAILED" -> stringResource(R.string.home_error_auth_failed_message)
    "TLS_UNTRUSTED" -> stringResource(R.string.home_error_tls_untrusted_message)
    "TLS_HOSTNAME" -> stringResource(R.string.home_error_tls_hostname_message, profile.tlsServerName ?: profile.host)
    "TLS_EXPIRED" -> stringResource(R.string.home_error_tls_expired_message)
    "TLS_HANDSHAKE" -> stringResource(R.string.home_error_tls_handshake_message)
    "PROTOCOL_ERROR" -> stringResource(R.string.home_error_protocol_message)
    "HANDSHAKE_TIMEOUT" -> stringResource(R.string.home_error_timeout_message)
    "TARGET_NOT_ALLOWED" -> stringResource(R.string.home_error_refused_message, profile.endpoint)
    "TARGET_UNREACHABLE", "TARGET_REFUSED" -> stringResource(R.string.home_error_unreachable_target_message, profile.endpoint)
    "PROXY_GENERAL_FAILURE" -> stringResource(R.string.home_error_proxy_failure_message, profile.endpoint)
    "TARGET_UNSUPPORTED" -> stringResource(R.string.home_error_unsupported_message)
    ConnectionError.CREDENTIAL_UNREADABLE -> stringResource(R.string.home_error_credentials_message)
    ConnectionError.ENGINE_STOPPED -> stringResource(R.string.home_error_engine_stopped_message)
    ConnectionError.PROFILE_DELETED -> stringResource(R.string.home_error_profile_deleted_message)
    else -> stringResource(R.string.home_error_internal_message)
}

private fun elapsed(millis: Long): String {
    val seconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

private const val MILLIS_PER_SECOND = 1_000L
