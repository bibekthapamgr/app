package app.proxyflow.feature.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfChoiceChips
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfEmptyState
import app.proxyflow.core.designsystem.component.PfLoadingScreen
import app.proxyflow.core.designsystem.component.PfSecondaryButton
import app.proxyflow.core.designsystem.component.PfTextField
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.MonoSmall
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.Formats
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogExport
import app.proxyflow.core.model.LogFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** What the Logs screen's controls do; the defaults do nothing (previews, screenshots, tests). */
data class LogsActions(
    val onFilter: (LogFilter) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSelect: (LogEntry) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    /** Copy and Export: the entries shown, as sanitized text (board F2). */
    val onCopy: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onCopyEntry: (LogEntry) -> Unit = {},
    val onClear: () -> Unit = {},
    val onConfirmClear: () -> Unit = {},
    val onDismissClear: () -> Unit = {},
)

@Composable
internal fun LogsRoute(viewModel: LogsViewModel, appVersion: String) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.logs_copied)
    val copy = { text: String ->
        copyToClipboard(context, text)
        // Android 13 and later confirm a copy themselves.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
    }
    LogsScreen(
        state = state,
        zone = zone,
        snackbar = snackbar,
        actions = LogsActions(
            onFilter = viewModel::setFilter,
            onQuery = viewModel::setQuery,
            onSelect = viewModel::select,
            onCloseDetail = viewModel::closeDetail,
            onCopy = { copy(exportText(context, state, appVersion, zone)) },
            onExport = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.logs_export_title))
                    .putExtra(Intent.EXTRA_TEXT, exportText(context, state, appVersion, zone))
                context.startActivity(Intent.createChooser(send, context.getString(R.string.logs_export)))
            },
            onCopyEntry = { entry -> copy(LogExport.line(entry, zone)) },
            onClear = viewModel::requestClear,
            onConfirmClear = viewModel::confirmClear,
            onDismissClear = viewModel::dismissClear,
        ),
    )
}

/**
 * The text Copy and Export hand over: header lines (app and Android version, when, how many of how
 * many entries, the filter and search), then the entries shown, sanitized once more (LogExport) —
 * at most what fits in one transaction to the other app, the newest kept.
 */
internal fun exportText(context: Context, state: LogsUiState, appVersion: String, zone: ZoneId, now: Instant = Instant.now()): String {
    val included = LogExport.newestFitting(state.entries, zone)
    val filter = context.getString(filterLabelRes(state.filter))
    val count = if (state.query.isBlank()) {
        context.getString(R.string.logs_export_count, included.size, state.total, filter)
    } else {
        context.getString(R.string.logs_export_count_search, included.size, state.total, filter, state.query.trim())
    }
    val header = listOf(
        context.getString(R.string.logs_export_title),
        context.getString(R.string.logs_export_app, appVersion, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
        context.getString(R.string.logs_export_when, LogExport.TIME.format(now.atZone(zone))),
        count,
        context.getString(R.string.logs_export_privacy),
    )
    return LogExport.text(included, header, zone)
}

private fun copyToClipboard(context: Context, text: String) {
    val clip = ClipData.newPlainText(context.getString(R.string.logs_export_title), text)
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

@Composable
fun LogsScreen(
    state: LogsUiState,
    modifier: Modifier = Modifier,
    actions: LogsActions = LogsActions(),
    zone: ZoneId = ZoneId.systemDefault(),
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PfTopAppBar(
                title = stringResource(R.string.logs_title),
                actions = {
                    val shown = state.entries.isNotEmpty()
                    IconButton(onClick = actions.onCopy, enabled = shown) {
                        Icon(PfIcons.Copy, contentDescription = stringResource(R.string.logs_copy))
                    }
                    IconButton(onClick = actions.onExport, enabled = shown) {
                        Icon(PfIcons.Share, contentDescription = stringResource(R.string.logs_export))
                    }
                    IconButton(onClick = actions.onClear, enabled = state.total > 0) {
                        Icon(PfIcons.Delete, contentDescription = stringResource(R.string.logs_clear))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when {
            state.loading -> PfLoadingScreen(content)
            state.total == 0 -> Box(content) {
                PfEmptyState(
                    icon = PfIcons.Logs,
                    title = stringResource(R.string.logs_empty_title),
                    body = stringResource(R.string.logs_empty_body),
                )
            }
            else -> Column(content) {
                Controls(state, actions)
                if (state.entries.isEmpty()) {
                    Box(Modifier.fillMaxSize()) {
                        PfEmptyState(
                            icon = PfIcons.Search,
                            title = stringResource(R.string.logs_no_match_title),
                            body = stringResource(R.string.logs_no_match_body),
                        )
                    }
                } else {
                    EntryList(state.entries, zone, actions.onSelect)
                }
            }
        }
    }
    state.selected?.let { entry ->
        EntrySheet(
            entry = entry,
            profileName = entry.profileId?.let { state.profileNames[it] },
            zone = zone,
            onCopy = { actions.onCopyEntry(entry) },
            onDismiss = actions.onCloseDetail,
        )
    }
    if (state.confirmingClear) {
        PfConfirmDialog(
            title = stringResource(R.string.logs_clear_title),
            text = stringResource(R.string.logs_clear_message),
            confirmLabel = stringResource(R.string.logs_clear_confirm),
            dismissLabel = stringResource(R.string.logs_cancel),
            onConfirm = actions.onConfirmClear,
            onDismiss = actions.onDismissClear,
            destructive = true,
        )
    }
}

/** Board F2's filters (docs/m1/03 J10), the search field and what the log keeps. */
@Composable
private fun Controls(state: LogsUiState, actions: LogsActions) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.screenGutter, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val labels = LogFilter.entries.associateWith { filterLabel(it) }
        PfChoiceChips(
            options = LogFilter.entries,
            selected = state.filter,
            label = { labels.getValue(it) },
            onSelect = actions.onFilter,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        PfTextField(
            value = state.query,
            onValueChange = actions.onQuery,
            label = stringResource(R.string.logs_search),
            trailingIcon = {
                if (state.query.isEmpty()) {
                    Icon(PfIcons.Search, contentDescription = null)
                } else {
                    IconButton(onClick = { actions.onQuery("") }) {
                        Icon(PfIcons.Close, contentDescription = stringResource(R.string.logs_search_clear))
                    }
                }
            },
        )
        Text(
            stringResource(R.string.logs_kept),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EntryList(entries: List<LogEntry>, zone: ZoneId, onSelect: (LogEntry) -> Unit) {
    val formatter = remember(zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(zone) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Spacing.screenGutter)) {
        items(entries, key = { it.id }) { entry ->
            LogRow(entry, time = formatter.format(Instant.ofEpochMilli(entry.timeMillis)), onClick = { onSelect(entry) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, time: String, onClick: () -> Unit) {
    val (icon, tint, label) = levelStyle(entry.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(eventTitle(entry), style = MaterialTheme.typography.bodyLarge)
            // The level is written out, so it never depends on colour alone.
            Text(
                listOfNotNull(time, label, entry.code, entry.stage?.let { stageLabel(it) }).joinToString(" · "),
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Board F3: everything one entry holds, what to do about its code, and Copy for just this entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(entry: LogEntry, profileName: String?, zone: ZoneId, onCopy: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter)
                .padding(bottom = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            val (icon, tint, level) = levelStyle(entry.level)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Icon(icon, contentDescription = null, tint = tint)
                Text(eventTitle(entry), style = MaterialTheme.typography.titleLarge)
            }
            Field(R.string.logs_detail_time, LogExport.TIME.format(Instant.ofEpochMilli(entry.timeMillis).atZone(zone)))
            Field(R.string.logs_detail_level, level)
            entry.stage?.let { Field(R.string.logs_detail_stage, stageLabel(it)) }
            entry.code?.let { Field(R.string.logs_detail_code, it) }
            // The profile's name while it exists; otherwise the short id the export uses.
            entry.profileId?.let { id -> Field(R.string.logs_detail_profile, profileName ?: id.filter { it.isLetterOrDigit() }.take(8)) }
            if (entry.details.isNotEmpty()) {
                Text(
                    stringResource(R.string.logs_detail_details),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                entry.details.toSortedMap().forEach { (key, value) ->
                    Text("$key = $value", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            hint(entry)?.let { PfBanner(title = stringResource(R.string.logs_detail_hint), text = it, tone = BannerTone.Info) }
            PfSecondaryButton(
                text = stringResource(R.string.logs_copy_event),
                onClick = onCopy,
                icon = PfIcons.Copy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Field(@StringRes label: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.35f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.65f),
        )
    }
}

@Composable
private fun levelStyle(level: LogEntry.Level): Triple<ImageVector, Color, String> = when (level) {
    LogEntry.Level.Info -> Triple(PfIcons.Info, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.logs_level_info))
    LogEntry.Level.Warning -> Triple(PfIcons.Warning, ProxyFlowTheme.colors.warning, stringResource(R.string.logs_level_warning))
    LogEntry.Level.Error -> Triple(PfIcons.AlertCircle, MaterialTheme.colorScheme.error, stringResource(R.string.logs_level_error))
}

@StringRes
private fun filterLabelRes(filter: LogFilter): Int = when (filter) {
    LogFilter.All -> R.string.logs_filter_all
    LogFilter.Errors -> R.string.logs_filter_errors
    LogFilter.Connection -> R.string.logs_filter_connection
    LogFilter.Blocked -> R.string.logs_filter_blocked
}

@Composable
private fun filterLabel(filter: LogFilter): String = stringResource(filterLabelRes(filter))

@Composable
internal fun stageLabel(stage: ConnectionStage): String = stringResource(
    when (stage) {
        ConnectionStage.Starting -> R.string.logs_stage_starting
        ConnectionStage.VpnPermission -> R.string.logs_stage_vpn_permission
        ConnectionStage.ProxyHandshake -> R.string.logs_stage_proxy_handshake
        ConnectionStage.Authenticated -> R.string.logs_stage_authenticated
        ConnectionStage.Connected -> R.string.logs_stage_connected
        ConnectionStage.Reconnecting -> R.string.logs_stage_reconnecting
        ConnectionStage.Failed -> R.string.logs_stage_failed
        ConnectionStage.Stopped -> R.string.logs_stage_stopped
    },
)

/** One line saying what happened, from the event and its details; codes and keys stay in the line below it. */
@Composable
internal fun eventTitle(entry: LogEntry): String {
    val details = entry.details
    fun number(key: String) = details[key]?.toLongOrNull()
    return when (entry.event) {
        LogEvent.ConnectRequested -> stringResource(
            when (details[LogDetail.SWITCH]) {
                "restart" -> R.string.logs_event_restart_requested
                "other_profile" -> R.string.logs_event_switch_requested
                else -> R.string.logs_event_connect_requested
            },
        )
        LogEvent.StageReached -> stringResource(R.string.logs_event_stage, entry.stage?.let { stageLabel(it) } ?: "?")
        LogEvent.Connected -> number(LogDetail.SETUP_MS)?.let { stringResource(R.string.logs_event_connected_in, Formats.seconds(it)) }
            ?: stringResource(R.string.logs_event_connected)
        LogEvent.ConnectFailed -> stringResource(R.string.logs_event_connect_failed)
        LogEvent.Blocked -> stringResource(R.string.logs_event_blocked)
        LogEvent.ReconnectStarted -> stringResource(
            when (details[LogDetail.REASON]) {
                "network_back" -> R.string.logs_event_reconnect_network_back
                "network_changed" -> R.string.logs_event_reconnect_network_changed
                else -> R.string.logs_event_reconnect_proxy
            },
        )
        LogEvent.ReconnectAttemptFailed -> stringResource(
            R.string.logs_event_attempt_failed,
            number(LogDetail.ATTEMPT) ?: 0,
            number(LogDetail.MAX_ATTEMPTS) ?: 0,
        )
        LogEvent.Reconnected -> count(R.plurals.logs_event_reconnected, number(LogDetail.ATTEMPT) ?: 1)
        LogEvent.ReconnectGaveUp -> count(R.plurals.logs_event_gave_up, number(LogDetail.ATTEMPT) ?: 0)
        LogEvent.Offline -> stringResource(R.string.logs_event_offline)
        LogEvent.NetworkChanged -> stringResource(
            R.string.logs_event_network_changed,
            stringResource(
                when (details[LogDetail.NETWORK]) {
                    "wifi" -> R.string.logs_network_wifi
                    "cellular" -> R.string.logs_network_cellular
                    "ethernet" -> R.string.logs_network_ethernet
                    else -> R.string.logs_network_other
                },
            ),
        )
        LogEvent.Stopped -> stringResource(
            if (details[LogDetail.BY] == "profile_deleted") R.string.logs_event_stopped_profile_deleted else R.string.logs_event_stopped,
        )
        LogEvent.Revoked -> stringResource(R.string.logs_event_revoked)
        LogEvent.SessionSummary -> stringResource(
            R.string.logs_event_session_summary,
            Formats.clock(number(LogDetail.DURATION_MS) ?: 0),
            Formats.bytes(number(LogDetail.BYTES_UP) ?: 0),
            Formats.bytes(number(LogDetail.BYTES_DOWN) ?: 0),
        )
        LogEvent.ConnectionProblems -> count(R.plurals.logs_event_connection_problems, number(LogDetail.COUNT) ?: 0)
    }
}

@Composable
private fun count(plural: Int, value: Long): String {
    val n = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(plural, n, n)
}

/** What the user can do about an entry's code (docs/m1/04 §4), or null when there is nothing to do. */
@Composable
private fun hint(entry: LogEntry): String? {
    val code = entry.code ?: return null
    val error = ConnectionError(code, entry.details[LogDetail.DETAIL])
    val hint = when (code) {
        "AUTH_REQUIRED", "AUTH_FAILED" -> if (error.unsupportedSignIn) R.string.logs_hint_auth_unsupported else R.string.logs_hint_auth
        "TLS_UNTRUSTED", "TLS_HOSTNAME" -> R.string.logs_hint_tls
        "TLS_EXPIRED" -> R.string.logs_hint_tls_expired
        "TLS_HANDSHAKE" -> R.string.logs_hint_tls_handshake
        "PROXY_DNS_FAILED" -> R.string.logs_hint_dns
        "HANDSHAKE_TIMEOUT" -> R.string.logs_hint_timeout
        "PROXY_UNREACHABLE" -> R.string.logs_hint_unreachable
        "PROTOCOL_ERROR" -> if (error.noConnect) R.string.logs_hint_no_connect else R.string.logs_hint_protocol
        "TARGET_NOT_ALLOWED", "TARGET_UNREACHABLE", "TARGET_REFUSED", "TARGET_UNSUPPORTED", "PROXY_GENERAL_FAILURE" ->
            R.string.logs_hint_target
        ConnectionError.OFFLINE -> R.string.logs_hint_offline
        ConnectionError.ENGINE_STOPPED -> R.string.logs_hint_engine
        ConnectionError.CREDENTIAL_UNREADABLE -> R.string.logs_hint_credentials
        ConnectionError.VPN_REVOKED -> R.string.logs_hint_revoked
        else -> return null
    }
    return stringResource(hint)
}
