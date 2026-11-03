package app.proxyflow.core.model

/**
 * Android's own kill switch for ProxyFlow (M8, ADR-0013): Always-on VPN set to ProxyFlow, with or without "Block
 * connections without VPN", which keeps apps from the network whenever ProxyFlow's VPN is not running — after a
 * crash, when Android ends the process, when another VPN takes over. Only the user can turn it on, in Android's VPN
 * settings: no app can. Android tells an app about it only while that app's VPN runs (`VpnService.isAlwaysOn()`,
 * `isLockdownEnabled()`), so this is what Android said then, and when ([checkedAtMillis], wall clock); it may have
 * been changed in Android's settings since.
 */
data class SystemKillSwitch(val alwaysOn: Boolean, val blocksWithoutVpn: Boolean, val checkedAtMillis: Long) {
    val status: KillSwitchStatus
        get() = when {
            alwaysOn && blocksWithoutVpn -> KillSwitchStatus.On
            alwaysOn -> KillSwitchStatus.AlwaysOnOnly
            else -> KillSwitchStatus.Off
        }
}

/** [SystemKillSwitch] in one word. [key] is logged, so it never changes. */
enum class KillSwitchStatus(val key: String) {
    /** Always-on VPN with "Block connections without VPN": nothing gets out while ProxyFlow's VPN is not running. */
    On("on"),

    /** Always-on VPN without blocking: Android starts ProxyFlow again, but apps use the normal network until it connects. */
    AlwaysOnOnly("always_on_only"),

    /** Neither: when ProxyFlow's VPN is not running, apps use the normal network. */
    Off("off"),
}

/**
 * ProxyFlow's process ended while it was connected, without the connection being stopped (M8, ADR-0013). Android
 * removed the VPN with the process, so from then until ProxyFlow connected again apps used the normal network —
 * unless Android's kill switch blocked them.
 *
 * @param connectedSinceMillis when that connection was established (wall clock).
 * @param endedAtMillis when Android says the process ended (Android 11 and later), or null when it does not say.
 * @param detectedAtMillis when the next process found out.
 */
data class Interruption(
    val profileId: String?,
    val connectedSinceMillis: Long,
    val endedAtMillis: Long?,
    val reason: ExitReason,
    val detectedAtMillis: Long,
)

/**
 * Why ProxyFlow's process ended, as far as Android says (`ApplicationExitInfo`, Android 11 and later, and the boot
 * count). [key] is logged and stored, so it never changes.
 */
enum class ExitReason(val key: String) {
    /** Android ended it to free memory. */
    LowMemory("low_memory"),

    /** An error in ProxyFlow's own code ended it. */
    Crash("crash"),

    /** An error in native code (the packet engine) ended it. */
    NativeCrash("native_crash"),

    /** Android ended it because it stopped responding. */
    NotResponding("not_responding"),

    /** A signal ended it: Android, or a task killer, ending the process (some phones report memory kills this way). */
    Killed("killed"),

    /** The user force-stopped it (Android's settings, or its list of active apps). */
    ForceStopped("force_stopped"),

    /** ProxyFlow was updated. */
    Updated("updated"),

    /** A permission ProxyFlow had was taken away (Android then ends the process). */
    PermissionChanged("permission_changed"),

    /** The phone restarted. */
    PhoneRestarted("phone_restarted"),

    /** Android ended it for another reason it names. */
    Other("other"),

    /** Android does not say (Android 10, or no record of that process). */
    Unknown("unknown"),
    ;

    companion object {
        fun of(key: String?): ExitReason = entries.firstOrNull { it.key == key } ?: Unknown
    }
}
