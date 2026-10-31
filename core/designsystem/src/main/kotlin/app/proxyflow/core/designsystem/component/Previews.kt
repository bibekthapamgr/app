package app.proxyflow.core.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.designsystem.theme.Spacing

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ComponentGalleryPreview() {
    ProxyFlowTheme {
        Surface {
            Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PfStatusPill("Not connected", StatusTone.Off)
                PfStatusPill("Connected", StatusTone.Connected)
                PfPrimaryButton("Connect", onClick = {}, icon = PfIcons.Power)
                PfPrimaryButton("Saving", onClick = {}, loading = true)
                PfSecondaryButton("Edit profile", onClick = {})
                PfDestructiveButton("Delete", onClick = {})
                PfTextField(value = "proxy.example.com", onValueChange = {}, label = "Server")
                PfTextField(value = "99999", onValueChange = {}, label = "Port", errorText = "Port must be between 1 and 65535")
                PfPasswordField(value = "secret", onValueChange = {}, label = "Password")
                PfSwitchRow(title = "Encrypt connection (TLS)", subtitle = "Recommended", checked = true, onCheckedChange = {})
                PfBanner(text = "Without TLS, your password can be seen on the network.", tone = BannerTone.Warning)
                PfCard { Text("Card content") }
                PfListRow(title = "Theme", supportingText = "System default", onClick = {})
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    ProxyFlowTheme {
        Surface {
            PfEmptyState(
                icon = PfIcons.Profiles,
                title = "No proxies yet",
                body = "Save each proxy you use as a profile.",
                actionLabel = "Add proxy",
                onAction = {},
            )
        }
    }
}
