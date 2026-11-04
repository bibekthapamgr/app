package app.proxyflow.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.proxyflow.core.designsystem.R
import app.proxyflow.core.designsystem.icon.PfIcons
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.designsystem.theme.Spacing
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfile

/**
 * A profile's last connection test as boards C2 and B8 show it (M9, ADR-0014): "Works · 182 ms · tested 2 min ago", a
 * failure in the error catalogue's words with when it happened, "Not tested yet", or the test running now — an icon and
 * a colour as well as the words, never colour alone (docs/m1/04 §1). A change is announced (TalkBack), since a test
 * started from a list ends while the user waits.
 *
 * @param nowMillis the time "tested … ago" counts from; a test dated after it (the phone's clock moved) reads "just now".
 */
@Composable
fun PfProfileTestLine(profile: ProxyProfile, lastTest: ProfileTest?, testing: Boolean, nowMillis: Long, modifier: Modifier = Modifier) {
    val announced = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodySmall
    when {
        testing -> Row(
            announced,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The words say it: the indicator itself is not announced as well.
            CircularProgressIndicator(Modifier.size(ICON_SIZE), strokeWidth = 2.dp)
            Text(stringResource(R.string.pf_test_running), style = style, color = quiet)
        }
        lastTest == null -> Text(stringResource(R.string.pf_test_never), modifier = announced, style = style, color = quiet)
        else -> {
            val passed = lastTest.passed
            val color = if (passed) ProxyFlowTheme.colors.statusConnected else ProxyFlowTheme.colors.statusError
            val ago = testedAgo(lastTest.atMillis, nowMillis)
            val text = if (passed) {
                stringResource(R.string.pf_test_works, lastTest.latencyMillis ?: 0L, ago)
            } else {
                stringResource(
                    R.string.pf_test_failed,
                    proxyCheckErrorTitle(ConnectionError(lastTest.code ?: ConnectionError.INTERNAL), profile),
                    ago,
                )
            }
            Row(announced, horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (passed) PfIcons.Check else PfIcons.Close,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(ICON_SIZE),
                )
                Text(text, style = style.copy(fontWeight = FontWeight.Medium), color = color)
            }
        }
    }
}

/** "tested just now", "tested 2 min ago", "tested 5 hours ago", then the day and time ("tested 3 Nov, 14:02"). */
@Composable
private fun testedAgo(atMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - atMillis).coerceAtLeast(0)
    return when {
        elapsed < MINUTE_MILLIS -> stringResource(R.string.pf_tested_now)
        elapsed < HOUR_MILLIS -> (elapsed / MINUTE_MILLIS).toInt().let { pluralStringResource(R.plurals.pf_tested_minutes, it, it) }
        elapsed < DAY_MILLIS -> (elapsed / HOUR_MILLIS).toInt().let { pluralStringResource(R.plurals.pf_tested_hours, it, it) }
        else -> stringResource(R.string.pf_tested_on, shortWhen(atMillis))
    }
}

private val ICON_SIZE = 16.dp
private const val MINUTE_MILLIS = 60_000L
private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
