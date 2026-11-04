package app.proxyflow.feature.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * The privacy policy, in the app (M9, ADR-0014): what ProxyFlow keeps on the phone, what it sends where, what Android
 * and Google do of their own, and the permissions. It describes this version; docs/m9/privacy-policy.md carries the same
 * text for the store listing.
 */
@Composable
fun PrivacyPolicyScreen(version: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    DocumentScreen(
        title = stringResource(R.string.privacy_title),
        sections = PRIVACY_SECTIONS,
        onBack = onBack,
        modifier = modifier,
        lead = stringResource(R.string.privacy_summary),
        footer = stringResource(R.string.privacy_applies, version),
    )
}

internal val PRIVACY_SECTIONS = listOf(
    DocumentSection(
        id = "stored",
        title = R.string.privacy_stored_title,
        paragraphs = listOf(
            R.string.privacy_stored_profiles,
            R.string.privacy_stored_credentials,
            R.string.privacy_stored_log,
            R.string.privacy_stored_settings,
            R.string.privacy_stored_backup,
        ),
    ),
    DocumentSection(
        id = "sent",
        title = R.string.privacy_sent_title,
        paragraphs = listOf(
            R.string.privacy_sent_traffic,
            R.string.privacy_sent_credentials,
            R.string.privacy_sent_lookup,
            R.string.privacy_sent_check,
            R.string.privacy_sent_nothing,
            R.string.privacy_sent_shared,
        ),
    ),
    DocumentSection(
        id = "android",
        title = R.string.privacy_android_title,
        paragraphs = listOf(R.string.privacy_android_crash, R.string.privacy_android_outside),
    ),
    DocumentSection(
        id = "permissions",
        title = R.string.privacy_permissions_title,
        paragraphs = listOf(
            R.string.privacy_permission_vpn,
            R.string.privacy_permission_internet,
            R.string.privacy_permission_service,
            R.string.privacy_permission_notifications,
        ),
    ),
    DocumentSection(id = "changes", title = R.string.privacy_changes_title, paragraphs = listOf(R.string.privacy_changes)),
)
