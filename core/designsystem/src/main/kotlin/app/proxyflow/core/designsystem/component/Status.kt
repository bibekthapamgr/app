package app.proxyflow.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.designsystem.theme.Spacing

/** The four status colours of the design (never used without an icon and text). */
enum class StatusTone { Connected, Transition, Off, Error }

/** Connection status shown as icon + text + colour, never colour alone (docs/m1/04 §1). */
@Composable
fun PfStatusPill(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = ProxyFlowTheme.colors
    val (color, icon) = when (tone) {
        StatusTone.Connected -> colors.statusConnected to PfIcons.ShieldCheck
        StatusTone.Transition -> colors.statusTransition to PfIcons.Refresh
        StatusTone.Off -> colors.statusOff to PfIcons.Block
        StatusTone.Error -> colors.statusError to PfIcons.ShieldAlert
    }
    Row(
        modifier = modifier
            .border(1.dp, color, CircleShape)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
