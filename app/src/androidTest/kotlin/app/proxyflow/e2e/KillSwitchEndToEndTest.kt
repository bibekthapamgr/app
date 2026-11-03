package app.proxyflow.e2e

import android.os.Build
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M8's failure injection on the emulators, step by step: scripts/m8_failure_injection.sh runs each step on its own with
 * `am instrument -e m8 <step>` and does what no test inside the app's process can do to it — kill the process, crash it,
 * have Android's Always-on VPN with "Block connections without VPN" start it — checking from outside what got out
 * meanwhile (the lab's witness, the captures, Android's crash report). Gradle's run of the device tests skips every step.
 *
 * What a step expects is passed in too: `-e expect <ExitReason key>` for how Android says the last process ended.
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

    private val chosen: String
        get() = runBlocking { app.profiles.chosenProfileId.first() } ?: error("no chosen profile: run prepare first")

    /**
     * A SOCKS5 profile for the lab's Dante, with sign-in, chosen ("Use this proxy", which Always-on VPN connects); VPN
     * consent and notifications allowed.
     */
    @Test
    fun prepare() {
        only("prepare")
        val env = E2eConfig.load()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        val profile = app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)
        runBlocking {
            app.profiles.setActive(profile.id)
            app.runtime.protection.setInterruption(null)
        }
        say("prepared: chosen profile ${profile.id}")
    }

    /** Connects the chosen profile, shows a line reaching the destination through the proxy, then waits to be killed. */
    @Test(timeout = HOLD_MS + SETUP_MS)
    fun connectAndHold() {
        only("connectAndHold")
        connectChosen("held-up")
        say("connected pid ${Process.myPid()}: waiting to be killed")
        Thread.sleep(HOLD_MS)
        fail("the process was not killed within ${HOLD_MS / 1_000} s")
    }

    /**
     * Connects, then an exception with a planted sign-in in its message reaches the top of a thread, as a bug's would:
     * the process ends, and Android's crash report must not have the planted secret (CrashReports, M8).
     */
    @Test(timeout = HOLD_MS + SETUP_MS)
    fun crashWithPlantedSecret() {
        only("crashWithPlantedSecret")
        connectChosen("crash-up")
        say("connected pid ${Process.myPid()}: crashing")
        thread(name = "pf8-crash") {
            throw IllegalStateException("PF8-planted-$token proxy pf8user:PF8SECRET$token@planted.pf8.invalid:1080 failed")
        }
        Thread.sleep(HOLD_MS)
        fail("the process did not end after the crash")
    }

    /**
     * After the last process ended while connected (killed, crashed): this one starts Disconnected with nothing of the
     * old connection left, has found the interruption with the reason Android gives ([expected]), logged it, and kept
     * it for Home.
     */
    @Test
    fun afterProcessDeath() {
        only("afterProcessDeath")
        val state = app.controller.state.value
        say("after process death: status ${state.status}, pid ${Process.myPid()}")
        assertEquals(ConnectionStatus.Disconnected, state.status)
        app.settleDisconnected()
        eventually("no status notification is left") { app.notification(ProxyFlowVpnService.STATUS_NOTIFICATION_ID) == null }
        checkInterruption()
    }

    /**
     * Android's Always-on VPN with "Block connections without VPN" started ProxyFlow, which was then killed: this
     * process kept what Android said while it ran (on, blocking), found the interruption, and connecting again — what
     * the user does to get the network back — works while Android blocks everything else.
     */
    @Test
    fun afterLockdownKill() {
        only("afterLockdownKill")
        val killSwitch = runBlocking { withTimeout(WAIT_MS) { app.runtime.protection.killSwitch.filterNotNull().first() } }
        say("kept kill switch: ${killSwitch.status.key}, checked at ${killSwitch.checkedAtMillis}")
        assertEquals("what Android said while ProxyFlow ran for Always-on VPN", KillSwitchStatus.On, killSwitch.status)
        checkInterruption()
        connectChosen("lockdown-reconnected")
        say("connected again while Android blocks: lines reach the destination through the proxy")
        app.disconnectAndSettle()
    }

    /** `-e expect key[,key…]`: the reasons Android may give for how the last process ended, the first the likeliest. */
    private fun checkInterruption() {
        val expected = arguments.getString("expect").orEmpty().split(',').map { ExitReason.of(it.trim()) }
        val found: Interruption = runBlocking { withTimeout(WAIT_MS) { app.runtime.protection.interruption.filterNotNull().first() } }
        say("interruption: ${found.reason.key}, ended at ${found.endedAtMillis ?: "-"}, connected since ${found.connectedSinceMillis}")
        assertTrue("how Android says the last process ended: ${found.reason}, expected one of $expected", found.reason in expected)
        assertEquals(chosen, found.profileId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && found.reason != ExitReason.Unknown) {
            val ended = assertNotNullAndGet("Android 11+ dates it", found.endedAtMillis)
            assertTrue("it ended after it connected", ended >= found.connectedSinceMillis)
        }
        val logged = app.awaitLog("the interruption") { it.event == LogEvent.SessionInterrupted && it.profileId == chosen }
        assertEquals(found.reason.key, logged.details[LogDetail.REASON])
        runBlocking { app.runtime.protection.setInterruption(null) }
    }

    private fun connectChosen(label: String) {
        val env = E2eConfig.load()
        app.connect(chosen)
        val line = "PF8-$label-$token"
        assertEquals("PROXYFLOW-DEST $line", Shell.exchange(env.destinationName, env.destinationPort, line))
    }

    private fun <T : Any> assertNotNullAndGet(message: String, value: T?): T {
        assertNotNull(message, value)
        return value!!
    }

    private companion object {
        const val M8_TAG = "ProxyFlowM8"
        const val HOLD_MS = 180_000L
        const val SETUP_MS = 60_000L
        const val WAIT_MS = 15_000L
    }
}
