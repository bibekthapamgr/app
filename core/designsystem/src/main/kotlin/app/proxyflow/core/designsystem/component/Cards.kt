package app.proxyflow.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.proxyflow.core.designsystem.theme.Spacing

/** Content card: white surface with a thin outline (dark: raised surface). Clickable when [onClick] is set. */
@Composable
fun PfCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(width = CardDefaults.outlinedCardBorder().width, color = MaterialTheme.colorScheme.outlineVariant)
    val inner: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(Spacing.md), content = content)
    }
    if (onClick != null) {
        OutlinedCard(onClick = onClick, modifier = modifier, colors = colors, border = border, content = inner)
    } else {
        OutlinedCard(modifier = modifier, colors = colors, border = border, content = inner)
    }
}
