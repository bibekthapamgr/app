package app.proxyflow.core.designsystem.component

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
 * Drops taps on this element that reach it while another app's window covers ProxyFlow's (tapjacking, threat model
 * S4; M8): Android marks such a touch obscured (`MotionEvent.FLAG_WINDOW_IS_OBSCURED` or
 * `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`), and an overlay could make the user tap what it hides — here, actions that send
 * apps to the normal network or delete data. Once a touch of a gesture is obscured, the whole gesture is dropped, so
 * an overlay that goes away before the finger lifts doesn't get the tap through either. [onRefused] runs once for each
 * gesture dropped; by default a short message says why nothing happened.
 *
 * Android 12 and later already drop most touches that pass through another app's window; this covers Android 10 and
 * 11, and windows those rules let through (a mostly transparent one, say).
 */
fun Modifier.refusingObscuredTouches(onRefused: (() -> Unit)? = null): Modifier = composed {
    val context = LocalContext.current
    val refused by rememberUpdatedState(
        onRefused ?: { Toast.makeText(context, R.string.pf_obscured_touch_refused, Toast.LENGTH_LONG).show() },
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
    get() = (motionEvent?.flags ?: 0) and OBSCURED != 0

private const val OBSCURED = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
