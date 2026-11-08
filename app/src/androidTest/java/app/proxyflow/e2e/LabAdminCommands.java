package app.proxyflow.e2e;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * What the M8 failure-injection run asks the lab's device owner ({@link LabDeviceAdmin}) to do, sent by the shell:
 *
 * <pre>
 *   am broadcast -n TEST_PACKAGE/app.proxyflow.e2e.LabAdminCommands -a app.proxyflow.e2e.ALWAYS_ON \
 *       --es package APP_PACKAGE --ez lockdown true      Always-on VPN for APP_PACKAGE, blocking without it or not
 *   ... -a app.proxyflow.e2e.ALWAYS_ON --es package ""   Always-on VPN off
 *   ... -a app.proxyflow.e2e.STATUS                      what Android has set
 *   ... -a app.proxyflow.e2e.RELEASE                     Always-on VPN off, then no device owner
 * </pre>
 *
 * The broadcast's result data says what Android has set afterwards ("always-on PACKAGE lockdown true|false", or
 * "always-on none"), or "error CLASS: MESSAGE"; result code 1 for success, 2 for an error. Only the shell can send it:
 * the receiver needs DUMP (AndroidManifest.xml of the test APK).
 */
public final class LabAdminCommands extends BroadcastReceiver {
    static final String ACTION_ALWAYS_ON = "app.proxyflow.e2e.ALWAYS_ON";
    static final String ACTION_STATUS = "app.proxyflow.e2e.STATUS";
    static final String ACTION_RELEASE = "app.proxyflow.e2e.RELEASE";
    private static final String TAG = "ProxyFlowLabAdmin";

    @Override
    public void onReceive(Context context, Intent intent) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, LabDeviceAdmin.class);
        String result;
        try {
            String action = String.valueOf(intent.getAction());
            switch (action) {
                case ACTION_ALWAYS_ON: {
                    String target = intent.getStringExtra("package");
                    if (target != null && target.isEmpty()) target = null;
                    boolean lockdown = intent.getBooleanExtra("lockdown", false);
                    dpm.setAlwaysOnVpnPackage(admin, target, target != null && lockdown);
                    result = status(dpm, admin);
                    break;
                }
                case ACTION_STATUS:
                    result = status(dpm, admin);
                    break;
                case ACTION_RELEASE:
                    if (dpm.isDeviceOwnerApp(context.getPackageName())) {
                        dpm.setAlwaysOnVpnPackage(admin, null, false);
                        dpm.clearDeviceOwnerApp(context.getPackageName());
                    }
                    result = "released, device owner " + dpm.isDeviceOwnerApp(context.getPackageName());
                    break;
                default:
                    result = "error unknown action " + action;
            }
        } catch (Exception e) {
            result = "error " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        Log.i(TAG, result);
        setResultCode(result.startsWith("error") ? 2 : 1);
        setResultData(result);
    }

    private static String status(DevicePolicyManager dpm, ComponentName admin) {
        String current = dpm.getAlwaysOnVpnPackage(admin);
        if (current == null) return "always-on none";
        return "always-on " + current + " lockdown " + dpm.isAlwaysOnVpnLockdownEnabled(admin);
    }
}
