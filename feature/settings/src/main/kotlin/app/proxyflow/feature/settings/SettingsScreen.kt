package app.proxyflow.feature.settings

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfChoiceDialog
import app.proxyflow.core.designsystem.component.PfListRow
import app.proxyflow.core.designsystem.component.PfSectionHeader
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.component.shortWhen
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.ThemePreference

@Composable
internal fun SettingsRoute(viewModel: SettingsViewModel, onOpenAbout: () -> Unit, onOpenKillSwitchGuide: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Each time the screen shows: Android is asked again while ProxyFlow is connected (M8).
    LifecycleResumeEffect(viewModel) {
        viewModel.checkKillSwitch()
        onPauseOrDispose {}
    }
    SettingsScreen(
        state = state,
        onThemeChange = viewModel::setTheme,
        onOpenAbout = onOpenAbout,
        onOpenKillSwitchGuide = onOpenKillSwitchGuide,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeChange: (ThemePreference) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenKillSwitchGuide: () -> Unit = {},
) {
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
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
            PfBanner(text = stringResource(R.string.settings_coming), tone = BannerTone.Info)
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
            PfSectionHeader(stringResource(R.string.settings_section_app))
            PfListRow(
                title = stringResource(R.string.settings_theme),
                supportingText = if (state.loading) null else themeLabel(state.theme),
                onClick = { choosingTheme = true },
            )
            PfListRow(title = stringResource(R.string.settings_about), onClick = onOpenAbout)
        }
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
private fun themeLabel(theme: ThemePreference): String = stringResource(
    when (theme) {
        ThemePreference.System -> R.string.settings_theme_system
        ThemePreference.Light -> R.string.settings_theme_light
        ThemePreference.Dark -> R.string.settings_theme_dark
    },
)
