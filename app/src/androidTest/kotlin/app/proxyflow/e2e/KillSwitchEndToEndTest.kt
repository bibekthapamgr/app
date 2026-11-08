package app.proxyflow.e2e

import android.os.Build
import android.os.Process
import android.os.SystemClock
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
 * What a step expects is passed in too: `-e expect <ExitReason key>[,<key>…]` for how Android says the last process
 * ended, and `-e since <ms>`, the phone's time before the scenario connected, so an interruption an earlier scenario left
 * is never taken for this one's.
 */
@RunWith(AndroidJUnit4::class)
class KillSwitchEndToEndTest {
    private val app = App()
    private val arguments = InstrumentationRegistry.getArguments()

    /** The run's token for this step, so the script finds this step's lines and no earlier one's. */
    private val token: String get() = arguments.getString("token") ?: "-"

    /** The phone's time (ms) before the scenario's connection: the interruption to check began after it. */
    private val since: Long
        get() = checkNotNull(arguments.getString("since")?.toLongOrNull()) { "no -e since <ms>: the script passes it" }

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
     * it for Home — and, having read it, still connects nothing of its own accord (ADR-0013 §5).
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
        assertEquals("nothing connected once the interruption was read", ConnectionStatus.Disconnected, app.controller.state.value.status)
        assertTrue("no VPN interface: ${Descriptors.tun()}", Descriptors.tun().isEmpty())
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
        assertEquals(ConnectionStatus.Disconnected, app.controller.state.value.status)
        // Still Android's blocking, not ProxyFlow's: the shell (which Android blocks; ProxyFlow's own uid it lets through)
        // can't reach the witness, which only a connection around ProxyFlow reaches.
        val witnessPort = checkNotNull(E2eConfig.properties()["witness.port"]?.toIntOrNull()) { "no witness.port in the lab's setup" }
        val probe = FromShell.tcp(RUNNER, witnessPort, "PF8-B-check-$token", 3_000)
        say("blocked before connecting: $probe")
        assertTrue("Android still blocks the shell while ProxyFlow is not connected: $probe", LOCKDOWN_REFUSAL.containsMatchIn(probe))
        connectChosen("lockdown-reconnected")
        say("connected again while Android blocks: lines reach the destination through the proxy")
        app.disconnectAndSettle()
    }

    /**
     * `-e expect key[,key…]`: the reasons Android may give for how the last process ended, the first the likeliest; a key
     * that names no reason fails (a typo would otherwise accept "unknown"). Only an interruption of a connection made
     * after [since] counts: one an earlier scenario left is not this one's.
     */
    private fun checkInterruption() {
        val expected = arguments.getString("expect").orEmpty().split(',').map { key ->
            ExitReason.entries.firstOrNull { it.key == key.trim() } ?: throw AssertionError("-e expect: no reason has the key \"$key\"")
        }
        val after = since
        // Read afresh every 250 ms rather than followed as a flow: when it is not found, what is there says why.
        val protection = app.runtime.protection
        val waitUntil = SystemClock.elapsedRealtime() + INTERRUPTION_WAIT_MS
        var stored: Interruption? = null
        while (stored == null || stored.connectedSinceMillis < after) {
            if (SystemClock.elapsedRealtime() > waitUntil) {
                val marker = runBlocking { protection.connectedMarker() }
                throw AssertionError(
                    "no interruption of a connection made since $after within ${INTERRUPTION_WAIT_MS / 1_000} s: stored $stored, " +
                        "marker $marker, session ${app.controller.state.value.status}",
                )
            }
            Thread.sleep(250)
            stored = runBlocking { protection.interruption.first() }
        }
        val found: Interruption = checkNotNull(stored)
        say("interruption: ${found.reason.key}, ended at ${found.endedAtMillis ?: "-"}, connected since ${found.connectedSinceMillis}")
        assertTrue("how Android says the last process ended: ${found.reason}, expected one of $expected", found.reason in expected)
        assertEquals(chosen, found.profileId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && found.reason != ExitReason.Unknown) {
            val ended = assertNotNullAndGet("Android 11+ dates it", found.endedAtMillis)
            assertTrue("it ended after it connected", ended >= found.connectedSinceMillis)
        }
        // Its own entry, dated when that process ended (or when it was found): not an earlier scenario's.
        val logged = app.awaitLog("the interruption") {
            it.event == LogEvent.SessionInterrupted && it.profileId == chosen && it.timeMillis >= after
        }
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

        /** Android's answer about the last process can be slow (ActivityManager, with the phone busy): a minute at most. */
        const val INTERRUPTION_WAIT_MS = 60_000L

        /** The runner, as the emulator reaches it. */
        const val RUNNER = "10.0.2.2"

        /** How a connect that Android's "Block connections without VPN" refuses fails. */
        val LOCKDOWN_REFUSAL = Regex("EACCES|Permission denied|EPERM|Operation not permitted")
    }
}
