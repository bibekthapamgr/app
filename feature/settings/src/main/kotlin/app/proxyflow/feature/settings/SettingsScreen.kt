package app.proxyflow.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfChoiceDialog
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.PfListRow
import app.proxyflow.core.designsystem.component.PfSectionHeader
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.component.shortWhen
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.LogRetention
import app.proxyflow.core.model.ThemePreference

@Composable
internal fun SettingsRoute(
    viewModel: SettingsViewModel,
    onOpenAbout: () -> Unit,
    onOpenKillSwitchGuide: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Read again each time the screen shows: the user may have changed it in Android's settings meanwhile.
    var notificationsOn by remember { mutableStateOf<Boolean?>(null) }
    // Each time the screen shows: Android is asked again while ProxyFlow is connected (M8).
    LifecycleResumeEffect(viewModel) {
        viewModel.checkKillSwitch()
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    SettingsScreen(
        state = state,
        onThemeChange = viewModel::setTheme,
        onOpenAbout = onOpenAbout,
        onOpenKillSwitchGuide = onOpenKillSwitchGuide,
        onLogRetentionChange = viewModel::setLogRetention,
        onClearLogs = viewModel::clearLogs,
        notificationsOn = notificationsOn,
        onOpenNotificationSettings = { openNotificationSettings(context) },
        onOpenHelp = onOpenHelp,
    )
}

/**
 * Android's notification settings for ProxyFlow, where the user turns its notifications on or off (Android 13+: that
 * also grants or withdraws the permission). A phone without that screen opens ProxyFlow's app info instead.
 */
private fun openNotificationSettings(context: Context) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    for (intent in listOf(notifications, appInfo)) {
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // Try the next one; with neither, nothing opens and the row still says how it is.
        }
    }
}

/**
 * Board E1 (and E1b): the two kill switches, the log's settings and the app's.
 *
 * @param notificationsOn whether Android lets ProxyFlow show notifications (Android 13+: the permission, and on every
 *   version the app's notification setting); null until read, and the row waits.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeChange: (ThemePreference) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenKillSwitchGuide: () -> Unit = {},
    onLogRetentionChange: (LogRetention) -> Unit = {},
    onClearLogs: () -> Unit = {},
    notificationsOn: Boolean? = null,
    onOpenNotificationSettings: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
) {
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
    var choosingRetention by rememberSaveable { mutableStateOf(false) }
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = stringResource(R.string.settings_title)) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter, vertical = Spacing.sm),
        ) {
            PfSectionHeader(stringResource(R.string.settings_section_kill_switch))
            // Not a switch: ProxyFlow's own kill switch is the engine's behaviour while connected (ADR-0004).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Spacing.minTouchTarget)
                    .padding(vertical = Spacing.sm)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(stringResource(R.string.settings_in_app_kill_switch), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_in_app_kill_switch_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PfListRow(
                title = stringResource(R.string.settings_system_kill_switch),
                supportingText = if (state.loading) null else systemKillSwitchSummary(state),
                onClick = onOpenKillSwitchGuide,
            )
            if (state.killSwitch?.status == KillSwitchStatus.On) {
                PfBanner(text = stringResource(R.string.settings_kill_switch_on_note), tone = BannerTone.Info)
            }
            PfSectionHeader(stringResource(R.string.settings_section_logs))
            PfListRow(
                title = stringResource(R.string.settings_log_retention),
                supportingText = if (state.loading) null else retentionLabel(state.logRetention),
                onClick = { choosingRetention = true },
            )
            PfListRow(
                title = stringResource(R.string.settings_clear_logs),
                supportingText = stringResource(R.string.settings_clear_logs_summary),
                onClick = { confirmingClear = true },
                showChevron = false,
            )
            PfSectionHeader(stringResource(R.string.settings_section_app))
            PfListRow(
                title = stringResource(R.string.settings_notifications),
                supportingText = when (notificationsOn) {
                    null -> null
                    true -> stringResource(R.string.settings_notifications_on)
                    false -> stringResource(R.string.settings_notifications_off)
                },
                onClick = onOpenNotificationSettings,
            )
            PfListRow(
                title = stringResource(R.string.settings_theme),
                supportingText = if (state.loading) null else themeLabel(state.theme),
                onClick = { choosingTheme = true },
            )
            PfListRow(title = stringResource(R.string.settings_help), onClick = onOpenHelp)
            PfListRow(title = stringResource(R.string.settings_about), onClick = onOpenAbout)
        }
    }
    if (choosingRetention) {
        PfChoiceDialog(
            title = stringResource(R.string.settings_log_retention),
            options = LogRetention.entries,
            selected = state.logRetention,
            label = { retentionLabel(it) },
            onSelect = {
                onLogRetentionChange(it)
                choosingRetention = false
            },
            onDismiss = { choosingRetention = false },
            dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
    if (confirmingClear) {
        PfConfirmDialog(
            title = stringResource(R.string.settings_clear_logs_title),
            text = stringResource(R.string.settings_clear_logs_message),
            confirmLabel = stringResource(R.string.settings_clear_logs_confirm),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                onClearLogs()
                confirmingClear = false
            },
            onDismiss = { confirmingClear = false },
            destructive = true,
        )
    }
    if (choosingTheme) {
        PfChoiceDialog(
            title = stringResource(R.string.settings_theme),
            options = ThemePreference.entries,
            selected = state.theme,
            label = { themeLabel(it) },
            onSelect = {
                onThemeChange(it)
                choosingTheme = false
            },
            onDismiss = { choosingTheme = false },
            dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
}

/** The system kill switch's status as Android last reported it, and when (boards E1, E1b). */
@Composable
internal fun systemKillSwitchSummary(state: SettingsUiState): String {
    val killSwitch = state.killSwitch
    val status = stringResource(
        when (killSwitch?.status) {
            KillSwitchStatus.On -> R.string.settings_kill_switch_on
            KillSwitchStatus.AlwaysOnOnly -> R.string.settings_kill_switch_always_on_only
            KillSwitchStatus.Off -> R.string.settings_kill_switch_off
            null -> R.string.settings_kill_switch_unknown
        },
    )
    val checked = when {
        killSwitch == null -> stringResource(R.string.settings_kill_switch_never)
        state.connected -> stringResource(R.string.settings_kill_switch_checked_now)
        else -> stringResource(R.string.settings_kill_switch_checked_at, shortWhen(killSwitch.checkedAtMillis))
    }
    return "$status\n$checked"
}

@Composable
private fun retentionLabel(retention: LogRetention): String = stringResource(
    when (retention) {
        LogRetention.Hour -> R.string.settings_log_retention_hour
        LogRetention.Day -> R.string.settings_log_retention_day
        LogRetention.Week -> R.string.settings_log_retention_week
    },
)

@Composable
private fun themeLabel(theme: ThemePreference): String = stringResource(
    when (theme) {
        ThemePreference.System -> R.string.settings_theme_system
        ThemePreference.Light -> R.string.settings_theme_light
        ThemePreference.Dark -> R.string.settings_theme_dark
    },
)
