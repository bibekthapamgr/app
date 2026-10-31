package app.proxyflow

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.vpn.ProxyFlowVpnService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The permissions and components the app declares (debug build, as merged).
 * Since M5 the app connects to the user's proxy and runs the VPN service, so it
 * asks for exactly what that needs (ADR-0010) and nothing else; the release
 * build is checked the same way by scripts/check_release_manifest.py. Also
 * checks that backups are off (ADR-0006).
 */
@RunWith(AndroidJUnit4::class)
class ManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun requestsOnlyWhatTheVpnNeeds() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        // androidx.core declares one signature permission of the app's own, used only
        // to protect its internal broadcast receivers; it grants nothing.
        val ownReceiverPermission = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val requested = info.requestedPermissions.orEmpty().filterNot { it == ownReceiverPermission }.sorted()
        assertEquals(
            listOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
            ),
            requested,
        )
    }

    @Test
    fun onlyAndroidCanBindTheVpnService() {
        val component = ComponentName(context, ProxyFlowVpnService::class.java)
        val service = context.packageManager.getServiceInfo(component, 0)
        assertFalse("not exported: the app starts it with explicit intents", service.exported)
        assertEquals(android.Manifest.permission.BIND_VPN_SERVICE, service.permission)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED, service.foregroundServiceType)
        // Android finds it for Always-on VPN through this action.
        val found = context.packageManager.queryIntentServices(Intent(VpnService.SERVICE_INTERFACE).setPackage(context.packageName), 0)
        assertEquals(listOf(component.className), found.map { it.serviceInfo.name })
    }

    @Test
    fun backupIsDisabled() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
