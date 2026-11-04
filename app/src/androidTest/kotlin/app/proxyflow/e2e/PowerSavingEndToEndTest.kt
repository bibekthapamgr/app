package app.proxyflow.e2e

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import app.proxyflow.core.vpn.RetryPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M10: what Android's power saving does to a connection, on each emulator — Doze, App Standby's most restricted bucket and
 * Battery Saver, each turned on the way Android itself does (on battery: none starts while charging). ProxyFlow's VPN runs
 * as a foreground service, which Android keeps on the network in each of them: connected, with each on, the connection
 * stays and a line from the shell (a user Android leaves out of all three) still goes through the proxy to the destination
 * by name, which only a proxy reaches.
 *
 * The test runs in ProxyFlow's own process, whose instrumentation Android ranks as a foreground service — the rank the
 * VPN's foreground service gives that process outside tests — so the network access tested is the one users get. What
 * the battery itself does is a phone's to show (docs/m10/physical-phone-checklist.md).
 */
@RunWith(AndroidJUnit4::class)
class PowerSavingEndToEndTest {
    private val app = App()
    private lateinit var env: E2eConfig

    @Before
    fun setUp() {
        env = E2eConfig.load()
        TestTrust.labCa = null
        TestRetry.policy = RetryPolicy()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
    }

    @After
    fun tearDown() {
        try {
            Shell.run("dumpsys deviceidle unforce")
            Shell.run("cmd power set-mode 0")
            Shell.run("settings put global low_power 0")
            Shell.run("am set-standby-bucket ${app.packageName} active")
            Shell.run("dumpsys battery reset")
        } finally {
            app.disconnectAndSettle()
            app.deleteAllProfiles()
        }
    }

    /** Doze, forced as deep as it goes: Android's own idle maintenance would come only after a long while unmoved. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun deepDozeKeepsTheConnectionAndItsTraffic() {
        app.connect(socks().id)
        assertSent("doze-before")
        Shell.run("dumpsys battery unplug")
        val enabled = Shell.run("dumpsys deviceidle enabled deep").trim()
        if (enabled != "1") Shell.run("dumpsys deviceidle enable deep")
        try {
            val forced = Shell.run("dumpsys deviceidle force-idle").trim()
            eventually("deep Doze ($forced)", diagnose = { Shell.run("dumpsys deviceidle get deep") }) {
                Shell.run("dumpsys deviceidle get deep").trim() == "IDLE"
            }
            Thread.sleep(HOLD_MS)
            assertConnected("in deep Doze")
            assertSent("doze-during")
            Log.i(LAB_TAG, "power: deep Doze (${Shell.run("dumpsys deviceidle get deep").trim()}), connected, a line through the proxy")
        } finally {
            Shell.run("dumpsys deviceidle unforce")
            if (enabled != "1") Shell.run("dumpsys deviceidle disable deep")
            Shell.run("dumpsys battery reset")
        }
        assertSent("doze-after")
    }

    /** App Standby: ProxyFlow put in its most restricted bucket (restricted from Android 11, rare before). */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun theMostRestrictedStandbyBucketKeepsTheConnection() {
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "restricted" else "rare"
        app.connect(socks().id)
        Shell.run("dumpsys battery unplug")
        try {
            Shell.run("am set-standby-bucket ${app.packageName} $bucket")
            val now = Shell.run("am get-standby-bucket ${app.packageName}").trim()
            Thread.sleep(HOLD_MS)
            assertConnected("in the $bucket bucket ($now)")
            assertSent("standby-$bucket")
            Log.i(LAB_TAG, "power: standby bucket $bucket ($now), connected, a line through the proxy")
        } finally {
            Shell.run("am set-standby-bucket ${app.packageName} active")
            Shell.run("dumpsys battery reset")
        }
    }

    /** Battery Saver, which keeps apps that are not in the foreground off the network. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun batterySaverKeepsTheConnection() {
        app.connect(socks().id)
        Shell.run("dumpsys battery unplug")
        try {
            Shell.run("cmd power set-mode 1")
            // The setting Battery Saver reads, for an Android whose `cmd power` has no set-mode.
            if (Shell.run("settings get global low_power").trim() != "1") Shell.run("settings put global low_power 1")
            eventually("Battery Saver on", diagnose = { Shell.run("settings get global low_power") }) {
                Shell.run("settings get global low_power").trim() == "1"
            }
            Thread.sleep(HOLD_MS)
            assertConnected("with Battery Saver on")
            assertSent("battery-saver")
            Log.i(LAB_TAG, "power: Battery Saver on, connected, a line through the proxy")
        } finally {
            Shell.run("cmd power set-mode 0")
            Shell.run("settings put global low_power 0")
            Shell.run("dumpsys battery reset")
        }
    }

    private fun assertConnected(what: String) {
        assertEquals("connected $what\n${Diagnostics.report(app)}", ConnectionStatus.Connected, app.controller.state.value.status)
        assertEquals("the VPN service runs $what", 1, ProxyFlowVpnService.liveInstances.get())
    }

    /** One line from the shell to the destination by name, answered there (m6_evidence.py finds it there once). */
    private fun assertSent(label: String) {
        val line = Lines.next(label)
        Lines.sent(line, env.destinationName, env.destinationPort)
        assertEquals("REPLY PROXYFLOW-DEST $line", FromShell.tcp(env.destinationName, env.destinationPort, line))
    }

    private fun socks(): ProxyProfile =
        app.save("Power SOCKS5", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)

    private companion object {
        const val TEST_TIMEOUT_MS = 120_000L

        /** A while with power saving on before the line: whatever it holds back has had time to. */
        const val HOLD_MS = 10_000L
    }
}
