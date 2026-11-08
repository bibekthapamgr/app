package app.proxyflow.core.designsystem.component

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A moment as the phone writes times (12 or 24 hours, its language): the time alone when it is today, with the day
 * otherwise — "14:02", "3 Nov, 14:02". For "when Android last said" and "when ProxyFlow stopped" (M8).
 */
fun shortWhen(context: Context, millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val flags = if (DateUtils.isToday(millis) && millis <= nowMillis) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateTime(context, millis, flags)
}

/** [shortWhen] in a composable. */
@Composable
fun shortWhen(millis: Long): String = shortWhen(LocalContext.current, millis)
