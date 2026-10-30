package app.proxyflow.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.proxyflow.core.designsystem.R
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.designsystem.theme.Spacing

/** Indeterminate progress with an accessible label. */
@Composable
fun PfLoadingIndicator(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.pf_loading)
    CircularProgressIndicator(modifier = modifier.semantics { contentDescription = label })
}

/** Full-screen loading state. */
@Composable
fun PfLoadingScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PfLoadingIndicator() }
}

/** Empty state: icon, title, explanation and an optional action (boards B1, C1, F1). */
@Composable
fun PfEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            PfPrimaryButton(text = actionLabel, onClick = onAction, icon = PfIcons.Add)
        }
    }
}

/** Error state with an optional retry action. */
@Composable
fun PfErrorState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(PfIcons.AlertCircle, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (retryLabel != null && onRetry != null) {
            PfSecondaryButton(text = retryLabel, onClick = onRetry, icon = PfIcons.Refresh)
        }
    }
}

enum class BannerTone { Info, Warning, Error }

/** Inline message block, e.g. the plaintext warning on Add/Edit (board D4). */
@Composable
fun PfBanner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val (container, content, icon) = when (tone) {
        BannerTone.Info ->
            Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, PfIcons.Info)
        BannerTone.Warning -> Triple(ProxyFlowTheme.colors.warningContainer, ProxyFlowTheme.colors.onWarningContainer, PfIcons.Warning)
        BannerTone.Error ->
            Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, PfIcons.AlertCircle)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.medium)
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = content)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = content)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}

/** Small uppercase label above a group of rows (Settings, About). */
@Composable
fun PfSectionHeader(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier.padding(top = Spacing.lg, bottom = Spacing.sm),
    )
}
