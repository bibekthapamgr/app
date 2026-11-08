package app.proxyflow.feature.about

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfTextButton
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.theme.Spacing

/**
 * One section of a page of text: a heading, its paragraphs, and optionally a button that opens another screen.
 *
 * @param id how a link names this section (Help's topics), stable across versions.
 */
internal data class DocumentSection(
    val id: String,
    @StringRes val title: Int,
    val paragraphs: List<Int>,
    @StringRes val actionLabel: Int? = null,
)

/**
 * A page of text in sections (the privacy policy, Help), opened at [startSection] when one is named. Each section is one
 * item of a lazy list, so a link to it scrolls there at once, also at 200 % font size.
 *
 * @param lead text shown above the sections, in a banner.
 * @param onAction what a section's button opens, by the section's id.
 */
@Composable
internal fun DocumentScreen(
    title: String,
    sections: List<DocumentSection>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    lead: String? = null,
    footer: String? = null,
    startSection: String? = null,
    onAction: (String) -> Unit = {},
) {
    // The lead, when there is one, is item 0.
    val offset = if (lead != null) 1 else 0
    val start = sections.indexOfFirst { it.id == startSection }.takeIf { it >= 0 }?.plus(offset) ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = start)
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = title, onBack = onBack) },
    ) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenGutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            if (lead != null) {
                item(key = "lead") { PfBanner(text = lead, tone = BannerTone.Info) }
            }
            items(sections, key = { it.id }) { section ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        stringResource(section.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { heading() },
                    )
                    for (paragraph in section.paragraphs) {
                        Text(stringResource(paragraph), style = MaterialTheme.typography.bodyLarge)
                    }
                    section.actionLabel?.let { label ->
                        PfTextButton(text = stringResource(label), onClick = { onAction(section.id) })
                    }
                }
            }
            if (footer != null) {
                item(key = "footer") {
                    Text(
                        footer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
