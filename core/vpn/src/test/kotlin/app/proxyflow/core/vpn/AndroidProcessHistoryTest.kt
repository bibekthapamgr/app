package app.proxyflow.core.vpn

import android.app.ApplicationExitInfo
import app.proxyflow.core.model.ExitReason
import org.junit.Assert.assertEquals
import org.junit.Test

/** How Android's reason for a process's end reads in ProxyFlow (M8): what Home and the log then say. */
class AndroidProcessHistoryTest {
    @Test
    fun androidsReasonsReadAsProxyFlowsOwn() {
        val expected = mapOf(
            ApplicationExitInfo.REASON_LOW_MEMORY to ExitReason.LowMemory,
            ApplicationExitInfo.REASON_CRASH to ExitReason.Crash,
            ApplicationExitInfo.REASON_CRASH_NATIVE to ExitReason.NativeCrash,
            ApplicationExitInfo.REASON_ANR to ExitReason.NotResponding,
            ApplicationExitInfo.REASON_SIGNALED to ExitReason.Killed,
            ApplicationExitInfo.REASON_USER_REQUESTED to ExitReason.ForceStopped,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE to ExitReason.PermissionChanged,
            16 to ExitReason.Updated, // REASON_PACKAGE_UPDATED (Android 14)
            ApplicationExitInfo.REASON_UNKNOWN to ExitReason.Unknown,
        )
        for ((reason, exit) in expected) assertEquals("reason $reason", exit, AndroidProcessHistory.reasonOf(reason))
    }

    /** The Android user ProxyFlow ran as was stopped (a work profile turned off): not "you force-stopped it". */
    @Test
    fun aStoppedAndroidUserIsNotAForceStop() {
        assertEquals(ExitReason.Other, AndroidProcessHistory.reasonOf(ApplicationExitInfo.REASON_USER_STOPPED))
    }

    @Test
    fun reasonsProxyFlowHasNoWordForAreOther() {
        assertEquals(ExitReason.Other, AndroidProcessHistory.reasonOf(ApplicationExitInfo.REASON_EXIT_SELF))
        assertEquals(ExitReason.Other, AndroidProcessHistory.reasonOf(ApplicationExitInfo.REASON_DEPENDENCY_DIED))
        assertEquals(ExitReason.Other, AndroidProcessHistory.reasonOf(999))
    }
}
