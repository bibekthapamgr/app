package app.proxyflow.feature.onboarding

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.proxyflow.core.designsystem.component.BannerTone
import app.proxyflow.core.designsystem.component.PfBanner
import app.proxyflow.core.designsystem.component.PfCard
import app.proxyflow.core.designsystem.component.PfPrimaryButton
import app.proxyflow.core.designsystem.component.PfSectionHeader
import app.proxyflow.core.designsystem.component.PfTextButton
import app.proxyflow.core.designsystem.component.PfTopAppBar
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.Spacing

@Composable
internal fun OnboardingRoute(viewModel: OnboardingViewModel, onFinished: () -> Unit) {
    val finished by viewModel.finished.collectAsStateWithLifecycle()
    LaunchedEffect(finished) { if (finished) onFinished() }
    val context = LocalContext.current
    // Decided once, so the page count doesn't change under the user when the answer comes back.
    val asksForNotifications = rememberSaveable { notificationPermissionNeeded(context) }
    // Whatever the user answers in Android's dialog, the pages are done: Settings can turn notifications on later.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.finish() }
    OnboardingScreen(
        showNotificationsPage = asksForNotifications,
        onAllowNotifications = {
            // A3 is only shown on Android 13+, where the permission exists.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: ActivityNotFoundException) {
                    viewModel.finish()
                }
            } else {
                viewModel.finish()
            }
        },
        onFinish = viewModel::finish,
    )
}

/**
 * Android 13+ asks for the notification permission at run time; on earlier versions notifications are allowed unless
 * the user turned them off, which no dialog can undo, so there is nothing to ask (docs/m1/03 J1: "Android < 13 skips A3").
 */
private fun notificationPermissionNeeded(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * The first-launch pages (boards A1–A3, docs/m1/03 J1): what ProxyFlow is and what the user needs (A1), how it works,
 * its limits and the VPN permission (A2, always before the first Connect is possible), and on Android 13+ the status
 * notification (A3). Back goes to the previous page; on the first, it leaves the app as usual.
 *
 * @param showNotificationsPage Android needs to ask for the notification permission (A3).
 */
@Composable
fun OnboardingScreen(
    showNotificationsPage: Boolean,
    onAllowNotifications: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    startPage: Int = 0,
) {
    var page by rememberSaveable { mutableIntStateOf(startPage) }
    val pages = if (showNotificationsPage) 3 else 2
    BackHandler(enabled = page > 0) { page-- }
    Scaffold(
        modifier = modifier,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = Spacing.screenGutter, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    stringResource(R.string.onboarding_page, page + 1, pages),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                when (page) {
                    0 -> PfPrimaryButton(
                        text = stringResource(R.string.onboarding_next),
                        onClick = { page = 1 },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    1 -> PfPrimaryButton(
                        text = stringResource(R.string.onboarding_continue),
                        onClick = { if (showNotificationsPage) page = 2 else onFinish() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> {
                        PfPrimaryButton(
                            text = stringResource(R.string.onboarding_notifications_allow),
                            onClick = onAllowNotifications,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PfTextButton(
                            text = stringResource(R.string.onboarding_notifications_not_now),
                            onClick = onFinish,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Each page starts at its top.
        key(page) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenGutter, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                when (page) {
                    0 -> WelcomePage()
                    1 -> HowItWorksContent(withTitle = true)
                    else -> NotificationsPage()
                }
            }
        }
    }
}

/** Board A1. */
@Composable
private fun WelcomePage() {
    Icon(PfIcons.Logo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
    Heading(R.string.onboarding_welcome_title)
    Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge)
    PfSectionHeader(stringResource(R.string.onboarding_need_title))
    Need(PfIcons.Globe, R.string.onboarding_need_protocol)
    Need(PfIcons.Phone, R.string.onboarding_need_address)
    Need(PfIcons.Key, R.string.onboarding_need_sign_in)
    PfBanner(text = stringResource(R.string.onboarding_no_proxies), tone = BannerTone.Info)
}

@Composable
private fun Need(icon: ImageVector, @StringRes text: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Board A3 (Android 13+). */
@Composable
private fun NotificationsPage() {
    Icon(PfIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
    Heading(R.string.onboarding_notifications_title)
    Text(stringResource(R.string.onboarding_notifications_body), style = MaterialTheme.typography.bodyLarge)
}

/**
 * Board A2: how ProxyFlow works, what it can carry, and the VPN permission. Shown in onboarding before the first
 * Connect is possible, and again from About ([HowItWorksScreen]).
 */
@Composable
fun HowItWorksContent(withTitle: Boolean) {
    if (withTitle) Heading(R.string.how_title)
    val flowDescription = stringResource(R.string.how_flow_description)
    Text(
        stringResource(R.string.how_flow),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { contentDescription = flowDescription },
    )
    PfSectionHeader(stringResource(R.string.how_while_connected))
    PfCard(modifier = Modifier.fillMaxWidth()) {
        Outcome(R.string.how_tcp, R.string.how_tcp_outcome, PfIcons.Check)
        Outcome(R.string.how_dns, R.string.how_dns_outcome, PfIcons.Check)
        Outcome(R.string.how_udp, R.string.how_udp_outcome, PfIcons.Block)
        Outcome(R.string.how_local, R.string.how_local_outcome, PfIcons.Block)
    }
    Text(stringResource(R.string.how_never_around), style = MaterialTheme.typography.bodyMedium)
    PfBanner(text = stringResource(R.string.how_provider), tone = BannerTone.Warning)
    PfSectionHeader(stringResource(R.string.how_vpn_title))
    Text(stringResource(R.string.how_vpn_body), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.how_outside),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One row of A2's table: a kind of traffic and what happens to it, read together by TalkBack. */
@Composable
private fun Outcome(@StringRes what: Int, @StringRes outcome: Int, icon: ImageVector) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(what), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(outcome),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Heading(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

/** Board A2 on its own, from About ("How ProxyFlow works"). */
@Composable
fun HowItWorksScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = { PfTopAppBar(title = stringResource(R.string.how_title), onBack = onBack) },
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
            HowItWorksContent(withTitle = false)
        }
    }
}
