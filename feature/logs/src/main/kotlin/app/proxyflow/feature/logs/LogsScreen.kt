package app.proxyflow.feature.logs

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.PfEmptyState
import app.proxyflow.core.designsystem.component.PfLoadingScreen
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.MonoSmall
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.LogEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun LogsRoute(viewModel: LogsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LogsScreen(state = state)
}

@Composable
fun LogsScreen(state: LogsUiState, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = stringResource(R.string.logs_title)) },
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when {
            state.loading -> PfLoadingScreen(content)
            state.entries.isEmpty() -> Box(content) {
                PfEmptyState(
                    icon = PfIcons.Logs,
                    title = stringResource(R.string.logs_empty_title),
                    body = stringResource(R.string.logs_empty_body),
                )
            }
            else -> {
                val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
                LazyColumn(modifier = content, contentPadding = PaddingValues(horizontal = Spacing.screenGutter)) {
                    items(state.entries, key = { it.id }) { entry ->
                        LogRow(entry, time = formatter.format(Instant.ofEpochMilli(entry.timeMillis)))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, time: String) {
    val (icon, tint, label) = levelStyle(entry.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(entry.message, style = MaterialTheme.typography.bodyLarge)
            // The level is written out, so it never depends on colour alone.
            Text(
                listOfNotNull(time, label, entry.code).joinToString(" · "),
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun levelStyle(level: LogEntry.Level): Triple<ImageVector, Color, String> = when (level) {
    LogEntry.Level.Info -> Triple(PfIcons.Info, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.logs_level_info))
    LogEntry.Level.Warning -> Triple(PfIcons.Warning, ProxyFlowTheme.colors.warning, stringResource(R.string.logs_level_warning))
    LogEntry.Level.Error -> Triple(PfIcons.AlertCircle, MaterialTheme.colorScheme.error, stringResource(R.string.logs_level_error))
}
