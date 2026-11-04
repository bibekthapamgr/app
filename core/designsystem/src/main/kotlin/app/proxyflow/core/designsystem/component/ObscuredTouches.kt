package app.proxyflow.core.designsystem.component

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import app.proxyflow.core.designsystem.R

/**
 * Drops taps on this element that pass through another app's window over ProxyFlow's (tapjacking, threat model S4;
 * M8): Android marks such a touch `MotionEvent.FLAG_WINDOW_IS_OBSCURED`, and an overlay could make the user tap what
 * it hides — here, actions that send apps to the normal network or delete data. This is the flag Android's own
 * `filterTouchesWhenObscured` checks; `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` is not checked, as Android sets it whenever
 * any window overlaps any part of ProxyFlow's, where the finger is or not (a chat bubble in a corner), which would
 * leave these actions unusable while such a window is on screen. Once a touch of a gesture is obscured, the whole
 * gesture is dropped, so an overlay that goes away before the finger lifts doesn't get the tap through either.
 * [onRefused] runs once for each gesture dropped; by default a short message says why nothing happened, at most once
 * every few seconds however many taps an overlay makes.
 *
 * Android 12 and later already drop most touches that pass through another app's window; this covers Android 10 and
 * 11, and windows those rules let through (a mostly transparent one, say).
 */
fun Modifier.refusingObscuredTouches(onRefused: (() -> Unit)? = null): Modifier = composed {
    val context = LocalContext.current
    val refused by rememberUpdatedState(
        onRefused ?: {
            if (refusalNotice.due()) Toast.makeText(context, R.string.pf_obscured_touch_refused, Toast.LENGTH_LONG).show()
        },
    )
    pointerInput(Unit) {
        awaitEachGesture {
            var dropping = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (!dropping && event.obscured) {
                    dropping = true
                    refused()
                }
                if (dropping) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** The touch passed through another app's window over ProxyFlow's. */
internal val PointerEvent.obscured: Boolean
    get() = (motionEvent?.flags ?: 0) and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0

/**
 * At most one message per [intervalMillis] for dropped taps, across every guarded element: an overlay that keeps
 * tapping must not queue a message per tap. Touches arrive on the main thread only.
 */
internal class RefusalNotice(private val intervalMillis: Long, private val clock: () -> Long) {
    private var lastAt: Long? = null

    fun due(): Boolean {
        val now = clock()
        val last = lastAt
        if (last != null && now - last < intervalMillis) return false
        lastAt = now
        return true
    }
}

// Toast.LENGTH_LONG shows for 3.5 s: one message at a time.
private val refusalNotice = RefusalNotice(intervalMillis = 3_500) { SystemClock.uptimeMillis() }
