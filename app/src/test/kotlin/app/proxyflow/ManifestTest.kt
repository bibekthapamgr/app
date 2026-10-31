package app.proxyflow

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M2 acceptance: "No network work runs on the main thread". This build makes no
 * network connections at all: it requests no permissions, so Android would
 * refuse a socket even if code tried to open one. Also checks that backups
 * are off (ADR-0006).
 */
@RunWith(AndroidJUnit4::class)
class ManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun requestsNoPermissions() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        // androidx.core declares one signature permission of the app's own, used only
        // to protect its internal broadcast receivers; it grants nothing.
        val ownReceiverPermission = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val requested = info.requestedPermissions.orEmpty().filterNot { it == ownReceiverPermission }
        assertEquals(emptyList<String>(), requested)
    }

    @Test
    fun backupIsDisabled() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
