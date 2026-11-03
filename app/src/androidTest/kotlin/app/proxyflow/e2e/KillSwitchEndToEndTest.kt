package app.proxyflow.e2e

import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M8's failure injection on the emulators, step by step: scripts/m8_failure_injection.sh runs each step on its own with
 * `am instrument -e m8 <step>` and does what no test inside the app's process can do to it — kill the process, have
 * Android's Always-on VPN with "Block connections without VPN" start and restart it — checking from outside what got
 * out meanwhile (the lab's witness, the captures). Gradle's run of the device tests skips every step.
 */
@RunWith(AndroidJUnit4::class)
class KillSwitchEndToEndTest {
    private val app = App()
    private val arguments = InstrumentationRegistry.getArguments()

    /** The run's token for this step, so the script finds this step's lines and no earlier one's. */
    private val token: String get() = arguments.getString("token") ?: "-"

    private fun only(step: String) =
        assumeTrue("run by scripts/m8_failure_injection.sh only (-e m8 $step)", arguments.getString("m8") == step)

    private fun say(text: String) = Log.i(M8_TAG, "$token $text")

    /** A SOCKS5 profile for the lab's Dante, with sign-in, chosen ("Use this proxy", which Always-on VPN connects); VPN consent and notifications allowed. */
    @Test
    fun prepare() {
        only("prepare")
        val env = E2eConfig.load()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        val profile = app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)
        runBlocking { app.profiles.setActive(profile.id) }
        say("prepared: chosen profile ${profile.id}")
    }

    /** Connects the chosen profile, shows a line reaching the destination through the proxy, then waits to be killed. */
    @Test(timeout = HOLD_MS + SETUP_MS)
    fun connectAndHold() {
        only("connectAndHold")
        val env = E2eConfig.load()
        val chosen = checkNotNull(runBlocking { app.profiles.chosenProfileId.first() }) { "no chosen profile: run the prepare step first" }
        app.connect(chosen)
        val line = "PF8-held-up-$token"
        assertEquals("PROXYFLOW-DEST $line", Shell.exchange(env.destinationName, env.destinationPort, line))
        say("connected pid ${Process.myPid()}: waiting to be killed")
        Thread.sleep(HOLD_MS)
        fail("the process was not killed within ${HOLD_MS / 1_000} s")
    }

    /** After the process was killed while connected: the new process starts Disconnected, with nothing of the old connection left. */
    @Test
    fun afterProcessDeath() {
        only("afterProcessDeath")
        val state = app.controller.state.value
        say("after process death: status ${state.status}, pid ${Process.myPid()}")
        assertEquals(ConnectionStatus.Disconnected, state.status)
        app.settleDisconnected()
        eventually("no status notification is left") { app.notification(ProxyFlowVpnService.STATUS_NOTIFICATION_ID) == null }
    }

    private companion object {
        const val M8_TAG = "ProxyFlowM8"
        const val HOLD_MS = 180_000L
        const val SETUP_MS = 60_000L
    }
}
