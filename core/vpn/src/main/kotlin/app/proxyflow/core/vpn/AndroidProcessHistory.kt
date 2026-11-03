package app.proxyflow.core.vpn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.annotation.RequiresApi
import app.proxyflow.core.data.ConnectedMarker
import app.proxyflow.core.model.ExitReason

/**
 * [ProcessHistory] from Android: the process id and the phone's boot count (`Settings.Global.BOOT_COUNT`) for the
 * marker, and Android's record of how that process ended (`ApplicationExitInfo`, Android 11 and later). The record's
 * description is never read: it can hold an exception's message.
 */
internal class AndroidProcessHistory(context: Context) : ProcessHistory {
    private val context = context.applicationContext

    override fun marker(profileId: String?, sinceMillis: Long) = ConnectedMarker(profileId, sinceMillis, Process.myPid(), bootCount())

    override fun ending(marker: ConnectedMarker): ProcessHistory.Ending {
        val boot = bootCount()
        if (marker.bootCount != null && boot != null && boot != marker.bootCount) {
            return ProcessHistory.Ending(ExitReason.PhoneRestarted, null)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ProcessHistory.Ending(ExitReason.Unknown, null)
        val record = try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, marker.pid, 1)
                ?.firstOrNull()
        } catch (e: RuntimeException) {
            null // Android could not answer: the reason stays unknown
        }
        // An older process that had the same id ended before this connection began: not this one.
        if (record == null || record.timestamp < marker.sinceMillis) return ProcessHistory.Ending(ExitReason.Unknown, null)
        return ProcessHistory.Ending(reasonOf(record.reason), record.timestamp)
    }

    private fun bootCount(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    internal companion object {
        /** `ApplicationExitInfo.REASON_PACKAGE_UPDATED` (Android 14), a constant also on older Androids' records. */
        private const val REASON_PACKAGE_UPDATED = 16

        @RequiresApi(Build.VERSION_CODES.R)
        fun reasonOf(reason: Int): ExitReason = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReason.LowMemory
            ApplicationExitInfo.REASON_CRASH -> ExitReason.Crash
            ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReason.NativeCrash
            ApplicationExitInfo.REASON_ANR -> ExitReason.NotResponding
            ApplicationExitInfo.REASON_SIGNALED -> ExitReason.Killed
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> ExitReason.ForceStopped
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> ExitReason.PermissionChanged
            REASON_PACKAGE_UPDATED -> ExitReason.Updated
            ApplicationExitInfo.REASON_UNKNOWN -> ExitReason.Unknown
            else -> ExitReason.Other
        }
    }
}
