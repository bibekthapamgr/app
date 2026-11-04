package app.proxyflow.e2e

import android.app.KeyguardManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowTileService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Quick Settings tile (board B11, M9) on a real Android, tapped through Android's own system UI
 * (`cmd statusbar click-tile`, what a tap on the tile does) with the panel open, as a person taps it: a tap connects the
 * profile Home shows through the lab's SOCKS5 proxy and makes it the chosen one, as Home's Connect does; a second tap
 * disconnects. Without the VPN consent a tap connects nothing: it opens Home, where Connect asks for it behind the
 * explainer (B9).
 */
@RunWith(AndroidJUnit4::class)
class QuickTileEndToEndTest {
    private val app = App()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var env: E2eConfig
    private val tile get() = "${app.packageName}/${ProxyFlowTileService::class.java.name}"

    @Before
    fun setUp() {
        env = E2eConfig.load()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        // A tap on a locked phone asks to unlock first (unlockAndRun): the emulator's screen is woken and unlocked.
        device.wakeUp()
        Shell.run("wm dismiss-keyguard")
        val keyguard = app.context.getSystemService(KeyguardManager::class.java)
        eventually("the phone unlocked", diagnose = { Shell.run("dumpsys window | grep -i keyguard") }) { !keyguard.isKeyguardLocked }
        // Removed first: `add-tile` puts a new tile first, on the panel's first page, but leaves one already there
        // where it is — possibly on a page that isn't shown, whose tiles don't listen.
        Shell.run("cmd statusbar remove-tile $tile")
        val added = Shell.run("cmd statusbar add-tile $tile")
        assumeFalse("this Android has no `cmd statusbar add-tile`: $added", "Unknown command" in added || "unknown command" in added)
    }

    @After
    fun tearDown() {
        Shell.run("cmd statusbar collapse")
        Shell.run("cmd statusbar remove-tile $tile")
        Shell.run("input keyevent KEYCODE_HOME")
        app.setVpnConsent(allowed = true)
        app.disconnectAndSettle()
        app.deleteAllProfiles()
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun aTapConnectsTheActiveProfileAndAnotherDisconnects() {
        val profile = socks()
        assertNull("saved, not chosen: Home shows it as the only one", runBlocking { app.profiles.chosenProfileId.first() })

        openQuickSettings()
        Shell.run("cmd statusbar click-tile $tile")
        val connected = app.awaitStatus(ConnectionStatus.Connected)
        assertEquals(profile.id, connected.profileId)
        assertEquals("chosen, as Home's Connect makes it (M8)", profile.id, runBlocking { app.profiles.chosenProfileId.first() })
        // Through the proxy, by name, as any connection ProxyFlow reports as Connected (m5_evidence.py matches the line).
        val line = "PF-tile-${System.nanoTime()}"
        Log.i(TAG, "exchange $line")
        assertEquals("PROXYFLOW-DEST $line", Shell.exchange(env.destinationName, env.destinationPort, line))

        openQuickSettings() // still open, unless something closed it meanwhile
        Shell.run("cmd statusbar click-tile $tile")
        app.awaitStatus(ConnectionStatus.Disconnected)
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    fun withoutTheVpnConsentATapOpensHomeAndConnectsNothing() {
        socks()
        app.setVpnConsent(allowed = false)

        openQuickSettings()
        Shell.run("cmd statusbar click-tile $tile")
        eventually("Home opened from the tile", diagnose = { Shell.run("dumpsys activity activities") }) {
            Shell.run("dumpsys activity activities").lines().any { "ResumedActivity" in it && "MainActivity" in it }
        }
        assertEquals(ConnectionStatus.Disconnected, app.controller.state.value.status)
    }

    /** The only profile, saved and not chosen: the one Home shows, which the tile connects. */
    private fun socks(): ProxyProfile =
        app.save("Tile", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)

    /**
     * Opens Quick Settings and waits until Android has bound the tile to show it. Android delivers a tap only to a tile
     * that is listening — on a phone the panel is open whenever its tiles can be tapped — and drops one sent to a tile
     * that isn't. The bind Android makes only to tell the service it was added ends at once; the one for an open panel
     * lasts while the panel is open, so the tile must stay bound for a while.
     */
    private fun openQuickSettings() {
        Shell.run("cmd statusbar expand-settings")
        val services = { Shell.run("dumpsys activity services $tile") }
        eventually("Android bound the tile for the open panel", timeoutMillis = BIND_TIMEOUT_MS, diagnose = services) {
            "ServiceRecord" in services() && run {
                Thread.sleep(BOUND_FOR_MS)
                "ServiceRecord" in services()
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 120_000L
        const val BIND_TIMEOUT_MS = 30_000L
        const val BOUND_FOR_MS = 2_000L
        const val TAG = "ProxyFlowE2E"
    }
}
