package app.proxyflow.e2e;

import android.app.admin.DeviceAdminReceiver;

/**
 * The CI emulators' device owner (scripts/m8_failure_injection.sh: {@code dpm set-device-owner}), declared in the test
 * APK only. Its one use is {@link LabAdminCommands}: turning Always-on VPN and "Block connections without VPN" on and
 * off for ProxyFlow, which Android otherwise leaves to the user in its VPN settings. Plain Java, like {@link
 * ShellClient}: the test APK's own process, where the receivers run, has no Kotlin library.
 */
public final class LabDeviceAdmin extends DeviceAdminReceiver {}
