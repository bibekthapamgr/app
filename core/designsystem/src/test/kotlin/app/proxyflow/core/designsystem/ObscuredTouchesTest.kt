package app.proxyflow.core.designsystem

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.component.PfConfirmDialog
import app.proxyflow.core.designsystem.component.RefusalNotice
import app.proxyflow.core.designsystem.component.refusingObscuredTouches
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Taps that pass through another app's window are dropped (threat model S4, M8): Android marks such a touch with
 * FLAG_WINDOW_IS_OBSCURED, and the touches here carry Android's flags as Android's would, sent to the screen the way
 * Compose's own test input sends its touches.
 */
@RunWith(AndroidJUnit4::class)
class ObscuredTouchesTest {
    @get:Rule
    val compose = createComposeRule()

    private var clicks = 0
    private var refusals = 0
    private lateinit var view: View

    private fun show() {
        compose.setContent {
            view = LocalView.current
            ProxyFlowTheme {
                TextButton(onClick = { clicks++ }, modifier = Modifier.refusingObscuredTouches { refusals++ }) { Text("Disconnect") }
            }
        }
    }

    @Test
    fun aTapThroughAnotherAppsWindowDoesNothing() {
        show()
        tap("Disconnect", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, clicks)
        assertEquals(1, refusals)

        tap("Disconnect", MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, clicks)
        assertEquals("once for each gesture dropped", 2, refusals)
    }

    /**
     * Another window over some other part of the screen (a chat bubble in a corner) marks every touch partially
     * obscured, where the finger is or not: as with Android's own filterTouchesWhenObscured, that tap goes through.
     */
    @Test
    fun aWindowElsewhereOnTheScreenDoesNotStopTheTap() {
        show()
        tap("Disconnect", MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertEquals(1, clicks)
        assertEquals(0, refusals)
    }

    /** The default message comes at most once per interval, however many taps an overlay makes. */
    @Test
    fun theMessageIsNotRepeatedForEveryTap() {
        var now = 10_000L
        val notice = RefusalNotice(intervalMillis = 3_500) { now }
        assertTrue(notice.due())
        now += 1_000
        assertFalse(notice.due())
        now += 2_499
        assertFalse(notice.due())
        now += 1
        assertTrue("one interval after the last message", notice.due())
        assertFalse(notice.due())
    }

    @Test
    fun aTapNobodyCoversGoesThrough() {
        show()
        tap("Disconnect", flags = 0)
        assertEquals(1, clicks)
        assertEquals(0, refusals)
        // The test framework's own taps carry no flag either.
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals(2, clicks)
    }

    @Test
    fun aGestureCoveredOnlyWhenTheFingerLiftsIsDroppedToo() {
        show()
        tap("Disconnect", flags = 0, upFlags = MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, clicks)
        assertEquals(1, refusals)
    }

    /** The guard on a destructive confirmation (delete, discard) lets an ordinary tap through, and Cancel stays as it was. */
    @Test
    fun aDestructiveConfirmationStillTakesAnOrdinaryTap() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            ProxyFlowTheme {
                PfConfirmDialog(
                    title = "Delete proxy?",
                    text = "This can't be undone.",
                    confirmLabel = "Delete",
                    dismissLabel = "Cancel",
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    destructive = true,
                )
            }
        }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun aThirdButtonIsShownWhenAsked() {
        var neutral = 0
        compose.setContent {
            ProxyFlowTheme {
                PfConfirmDialog(
                    title = "VPN permission needed",
                    text = "-",
                    confirmLabel = "Try again",
                    dismissLabel = "Not now",
                    onConfirm = {},
                    onDismiss = {},
                    neutralLabel = "Open VPN settings",
                    onNeutral = { neutral++ },
                )
            }
        }
        compose.onNodeWithText("Not now").assertExists()
        compose.onNodeWithText("Open VPN settings").performClick()
        assertEquals(1, neutral)
    }

    /** A finger down and up at the middle of [text], with [flags] on the down and [upFlags] on the up. */
    private fun tap(text: String, flags: Int, upFlags: Int = flags) {
        val center = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center
        val start = SystemClock.uptimeMillis()
        compose.runOnUiThread {
            val screen = IntArray(2).also { view.getLocationOnScreen(it) }
            val touches = listOf(Triple(MotionEvent.ACTION_DOWN, flags, start), Triple(MotionEvent.ACTION_UP, upFlags, start + 40))
            for ((action, flagsOf, time) in touches) {
                val event = MotionEvent.obtain(
                    start,
                    time,
                    action,
                    1,
                    arrayOf(
                        MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = MotionEvent.TOOL_TYPE_FINGER
                        },
                    ),
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = screen[0] + center.x
                            y = screen[1] + center.y
                        },
                    ),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    flagsOf,
                )
                event.offsetLocation(-screen[0].toFloat(), -screen[1].toFloat())
                view.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        compose.waitForIdle()
    }
}
