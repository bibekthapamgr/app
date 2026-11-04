package app.proxyflow.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfPrimaryButton
import app.proxyflow.core.designsystem.component.PfTextButton
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.component.openVpnSettings
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.KillSwitchStatus

@Composable
internal fun KillSwitchGuideRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Back from Android's VPN settings: while ProxyFlow is connected, Android is asked again at once.
    LifecycleResumeEffect(viewModel) {
        viewModel.checkKillSwitch()
        onPauseOrDispose {}
    }
    KillSwitchGuideScreen(state = state, onOpenVpnSettings = { openVpnSettings(context) }, onBack = onBack)
}

/**
 * Board E2: how to turn on Android's own kill switch — Always-on VPN with "Block connections without VPN" — and what
 * it does and doesn't do (M8, ADR-0013). ProxyFlow can only explain it and open the screen: no app can turn it on.
 */
@Composable
fun KillSwitchGuideScreen(state: SettingsUiState, onOpenVpnSettings: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = stringResource(R.string.guide_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(stringResource(R.string.guide_intro), style = MaterialTheme.typography.bodyLarge)
            val status = state.killSwitch?.status
            PfBanner(
                text = listOf(
                    stringResource(
                        R.string.guide_status,
                        stringResource(
                            when (status) {
                                KillSwitchStatus.On -> R.string.guide_status_on
                                KillSwitchStatus.AlwaysOnOnly -> R.string.guide_status_always_on_only
                                KillSwitchStatus.Off -> R.string.guide_status_off
                                null -> R.string.guide_status_unknown
                            },
                        ),
                    ),
                    systemKillSwitchSummary(state).substringAfter('\n'),
                ).joinToString("\n"),
                tone = if (status == KillSwitchStatus.On) BannerTone.Success else BannerTone.Warning,
            )
            Step(1, R.string.guide_step_1)
            Step(2, R.string.guide_step_2)
            Step(3, R.string.guide_step_3)
            Step(4, R.string.guide_step_4)
            Note(R.string.guide_vary)
            Note(R.string.guide_only_you)
            Note(R.string.guide_not_restarted)
            Note(R.string.guide_system_traffic)
            PfPrimaryButton(
                text = stringResource(R.string.guide_open_settings),
                onClick = onOpenVpnSettings,
                icon = PfIcons.Settings,
                modifier = Modifier.fillMaxWidth(),
            )
            PfTextButton(text = stringResource(R.string.guide_later), onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Step(number: Int, @StringRes text: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
