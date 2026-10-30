package app.proxyflow.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfChoiceDialog
import app.proxyflow.core.designsystem.component.PfListRow
import app.proxyflow.core.designsystem.component.PfSectionHeader
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ThemePreference

@Composable
internal fun SettingsRoute(viewModel: SettingsViewModel, onOpenAbout: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state = state, onThemeChange = viewModel::setTheme, onOpenAbout = onOpenAbout)
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeChange: (ThemePreference) -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
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

@Composable
private fun themeLabel(theme: ThemePreference): String = stringResource(
    when (theme) {
        ThemePreference.System -> R.string.settings_theme_system
        ThemePreference.Light -> R.string.settings_theme_light
        ThemePreference.Dark -> R.string.settings_theme_dark
    },
)
