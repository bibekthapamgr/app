package app.proxyflow.feature.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfSectionHeader
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.Spacing

/**
 * Static About & privacy page. [version] comes from the app module's
 * BuildConfig, e.g. "0.2.0 (2)". Links to the privacy policy, licences and
 * help are added once those pages exist (M9/M11).
 */
@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = stringResource(R.string.about_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(PfIcons.Logo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                Column {
                    Text(stringResource(R.string.about_app_name), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.about_version, version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            PfBanner(text = stringResource(R.string.about_build_scope), tone = BannerTone.Info)
            PfSectionHeader(stringResource(R.string.about_section_privacy))
            Bullet(R.string.about_privacy_no_account)
            Bullet(R.string.about_privacy_on_phone)
            Bullet(R.string.about_privacy_provider)
            PfSectionHeader(stringResource(R.string.about_section_carry))
            Bullet(R.string.about_carry_tcp)
            Bullet(R.string.about_carry_blocked)
            Bullet(R.string.about_carry_ipv6)
            Text(
                stringResource(R.string.about_independent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.lg),
            )
        }
    }
}

@Composable
private fun Bullet(@StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}
