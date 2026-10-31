package app.proxyflow

import android.app.Application
import android.os.StrictMode
import app.proxyflow.core.vpn.VpnRuntime

open class ProxyFlowApplication :
    Application(),
    VpnRuntime.Provider {
    /**
     * App-wide objects (repositories and the ViewModel factory). They live as
     * long as the process, so no screen state depends on an Activity instance.
     */
    lateinit var container: AppContainer
        private set

    /** For ProxyFlowVpnService, which Android creates (ADR-0010). */
    override val vpnRuntime: VpnRuntime get() = container.vpnRuntime

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        container = createContainer()
    }

    /** Tests replace the Android Keystore, which the JVM does not have, by overriding this. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    /**
     * Debug builds report disk and network access on the main thread to Logcat and
     * crash on any main-thread network call, so such a call cannot slip in unnoticed.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyDeathOnNetwork()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }
}
