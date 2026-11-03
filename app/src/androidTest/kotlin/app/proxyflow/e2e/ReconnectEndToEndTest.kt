package app.proxyflow.e2e

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.engine.EngineState
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.vpn.ProxyFlowVpnService
import app.proxyflow.core.vpn.RetryPolicy
import app.proxyflow.core.vpn.VpnSession
import java.util.UUID
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M7 end to end on an emulator (ADR-0012): the session's traffic counters against controlled
 * transfers, reconnecting on its own after a real proxy outage — bounded, cancellable, sooner with
 * Retry now — the phone's network (Offline and back, a move to another network), and rapid connects
 * and disconnects, with the real engine, VPN and proxies. Traffic is sent from the shell, a user
 * the VPN covers. Lines that must get nowhere are logged for scripts/m6_evidence.py and held ones
 * go to the sink, which must log nothing (m5_evidence.py); scripts/m7_evidence.py reads the bulk
 * server's log, the transfers the tests logged and the session's reconnection lines in logcat.
 *
 * The network tests tell the session of a network change themselves, as Android would through
 * [app.proxyflow.core.vpn.NetworkMonitor]: switching the emulator's own uplink is what the CI
 * setup avoids (scripts/e2e_device.sh). [theNetworkMonitorKnowsThePhonesNetwork] checks the monitor
 * itself against Android.
 */
@RunWith(AndroidJUnit4::class)
class ReconnectEndToEndTest {
    private val app = App()
    private lateinit var env: E2eConfig
    private lateinit var lab: LabConfig
    private lateinit var gate: Gate
    private lateinit var networks: VpnNetworks
    private lateinit var history: StateHistory

    /** The phone's network as Android reported it, given back to the session after a test told it of another. */
    private var realNetwork: VpnSession.NetworkInfo? = null

    @Before
    fun setUp() {
        env = E2eConfig.load()
        lab = LabConfig.load()
        gate = Gate(lab)
        gate.forward(env.socksPort)
        TestTrust.labCa = null
        TestRetry.policy = RetryPolicy()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        networks = VpnNetworks(app.context)
        history = StateHistory(app.controller)
    }

    @After
    fun tearDown() {
        if (::history.isInitialized) history.close()
        if (::networks.isInitialized) networks.close()
        TestRetry.policy = RetryPolicy()
        if (::gate.isInitialized) runCatching { gate.forward(env.socksPort) }
        app.disconnectAndSettle()
        realNetwork?.let { app.runtime.session.underlyingNetwork(it) }
        app.deleteAllProfiles()
    }

    /**
     * Home's counters against transfers of known size through each protocol: never less than what the apps'
     * connections carried, and at most 2 % + 256 KiB more (other apps' traffic through the VPN meanwhile, which
     * the session counts too). The session's summary in the log carries the same.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun theTrafficCountersMatchControlledTransfers() {
        for (profile in listOf(socks(), http())) {
            val connected = app.connect(profile.id)
            val before = app.awaitTraffic("the first reading") { it.sinceMillis == connected.connectedSinceMillis }

            assertEquals(
                "SENT $UP_BYTES REPLY GOT $UP_BYTES",
                FromShell.bulk(env.destinationName, lab.bulkPort, up = true, bytes = UP_BYTES),
            )
            assertEquals("RECEIVED $DOWN_BYTES", FromShell.bulk(env.destinationName, lab.bulkPort, up = false, bytes = DOWN_BYTES))

            // What the two connections carried: the request lines and the upload up; the reply and the download down.
            val expectedUp = "UP $UP_BYTES\n".length + UP_BYTES + "DOWN $DOWN_BYTES\n".length
            val expectedDown = "GOT $UP_BYTES\n".length + DOWN_BYTES
            val after = app.awaitTraffic("the transfers counted") {
                it.bytesUp - before.bytesUp >= expectedUp && it.bytesDown - before.bytesDown >= expectedDown
            }
            val countedUp = after.bytesUp - before.bytesUp
            val countedDown = after.bytesDown - before.bytesDown
            Log.i(
                LAB_TAG,
                "traffic ${profile.protocol} up expected=$expectedUp counted=$countedUp down expected=$expectedDown counted=$countedDown",
            )
            assertTrue("up: counted $countedUp for $expectedUp", countedUp <= allowed(expectedUp))
            assertTrue("down: counted $countedDown for $expectedDown", countedDown <= allowed(expectedDown))

            app.disconnectAndSettle()
            val summary = app.awaitLog("the session's summary") { it.event == LogEvent.SessionSummary && it.profileId == profile.id }
            assertTrue("$summary", checkNotNull(summary.details[LogDetail.BYTES_UP]).toLong() >= after.bytesUp)
            assertTrue("$summary", checkNotNull(summary.details[LogDetail.BYTES_DOWN]).toLong() >= after.bytesDown)
        }
    }

    /**
     * The proxy goes away while connected: its connections fail, three in a row and ProxyFlow reconnects on its
     * own with traffic held at the same interface; once the proxy is back it is connected again by itself, and
     * the session's time and traffic go on.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aProxyOutageReconnectsOnItsOwnAndTheSessionGoesOn() {
        val profile = gated()
        val connected = app.connect(profile.id)
        assertSent("outage-up")
        val before = app.awaitTraffic("the line counted") { it.bytesUp > 0 }

        gate.refuse()
        val reconnecting = failUntilReconnecting("outage")
        assertEquals(ReconnectReason.ProxyFailures, reconnecting.reconnect?.reason)
        assertEquals("PROXY_UNREACHABLE", reconnecting.reconnect?.lastError?.code)
        assertEquals("the session's time goes on", connected.connectedSinceMillis, reconnecting.connectedSinceMillis)
        assertEquals("the interface stays, so traffic is held", 1, Descriptors.tun().size)
        assertTrue("Android keeps the VPN network", networks.present())
        assertHeld("outage-reconnecting")

        gate.forward(env.socksPort)
        val back = app.await(what = "Connected again on its own") { it.status == ConnectionStatus.Connected }
        assertEquals(connected.connectedSinceMillis, back.connectedSinceMillis)
        assertEquals("the same interface throughout", 1, Descriptors.tun().size)
        assertSent("outage-back")
        val after = app.awaitTraffic("the session's traffic going on") { it.bytesUp > before.bytesUp }
        assertEquals(before.sinceMillis, after.sinceMillis)

        val started = app.awaitLog("the reconnection") { it.event == LogEvent.ReconnectStarted && it.profileId == profile.id }
        assertEquals(ConnectionStage.Reconnecting, started.stage)
        assertEquals("PROXY_UNREACHABLE", started.code)
        app.awaitLog("the reconnection's end") { it.event == LogEvent.Reconnected && it.profileId == profile.id }
    }

    /** The attempts are bounded: with the proxy still away after the last one, traffic stays held until the user acts. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun reconnectingStopsAfterItsLastAttemptWithTrafficHeld() {
        TestRetry.policy = RetryPolicy(delaysMillis = listOf(300L, 300L, 300L), jitter = 0.0)
        val profile = gated()
        app.connect(profile.id)
        assertSent("give-up-up")

        gate.refuse()
        failUntilReconnecting("give-up")
        val blocked = app.await(what = "Blocked after the last attempt") { it.status == ConnectionStatus.Blocked }
        assertEquals("PROXY_UNREACHABLE", blocked.error?.code)
        assertEquals("reconnecting stopped after 3 attempts", blocked.error?.detail)
        assertEquals(listOf(1, 2, 3), history.states.mapNotNull { it.reconnect?.attempt }.distinct())
        assertEquals("the interface stays, so traffic is held", 1, Descriptors.tun().size)
        assertTrue("Android keeps the VPN network", networks.present())
        assertHeld("give-up-blocked")

        gate.forward(env.socksPort)
        Thread.sleep(NO_MORE_ATTEMPTS_MS)
        assertEquals("no attempt runs on its own any more", ConnectionStatus.Blocked, app.controller.state.value.status)
        val gaveUp = app.awaitLog("the end of the attempts") { it.event == LogEvent.ReconnectGaveUp && it.profileId == profile.id }
        assertEquals("3", gaveUp.details[LogDetail.ATTEMPT])

        app.controller.restart()
        app.awaitStatus(ConnectionStatus.Connected)
        assertSent("give-up-restarted")
    }

    /** Disconnect while ProxyFlow waits for its next attempt: none runs after it, and nothing is left behind. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun disconnectingWhileReconnectingCancelsIt() {
        TestRetry.policy = RetryPolicy(delaysMillis = listOf(LONG_WAIT_MS, LONG_WAIT_MS, LONG_WAIT_MS), jitter = 0.0)
        val profile = gated()
        app.connect(profile.id)
        assertSent("cancel-up")

        gate.refuse()
        assertNotNull(failUntilReconnecting("cancel").reconnect?.nextAttemptAtMillis)
        val stoppedAt = SystemClock.elapsedRealtime()
        app.disconnectAndSettle()
        eventually("no session job is left", diagnose = { Diagnostics.report(app) }) { app.runtime.session.activeJobs() == 0 }
        gate.forward(env.socksPort)
        Thread.sleep(NO_MORE_ATTEMPTS_MS)

        assertEquals(ConnectionStatus.Disconnected, app.controller.state.value.status)
        val attempts = app.runtime.log.recent().filter { it.substringBefore(' ').toLong() > stoppedAt && "reconnect attempt" in it }
        assertEquals("no attempt after the disconnect", emptyList<String>(), attempts)
        app.awaitLog("the disconnect") { it.event == LogEvent.Stopped && it.profileId == profile.id }
    }

    /** Retry now runs the waiting attempt at once instead of after its wait. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun retryNowRunsTheNextAttemptAtOnce() {
        TestRetry.policy = RetryPolicy(delaysMillis = listOf(LONG_WAIT_MS, LONG_WAIT_MS, LONG_WAIT_MS), jitter = 0.0)
        val profile = gated()
        app.connect(profile.id)
        assertSent("retry-now-up")

        gate.refuse()
        val due = checkNotNull(failUntilReconnecting("retry-now").reconnect?.nextAttemptAtMillis)
        gate.forward(env.socksPort)
        app.controller.retryNow()

        app.await(timeoutMillis = RETRY_NOW_MS, what = "Connected right after Retry now") { it.status == ConnectionStatus.Connected }
        assertTrue("long before the attempt was due", System.currentTimeMillis() < due - LONG_WAIT_MS / 2)
        assertSent("retry-now-back")
    }

    /** Without a network the engine stops and traffic waits at the interface (B7); with one back, it reconnects at once. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun withoutANetworkTrafficIsHeldUntilOneIsBack() {
        val real = awaitRealNetwork()
        val profile = socks()
        val connected = app.connect(profile.id)
        assertSent("offline-up")

        app.runtime.session.underlyingNetwork(null)
        val offline = app.awaitStatus(ConnectionStatus.Offline)
        assertEquals(ConnectionError.OFFLINE, offline.error?.code)
        assertEquals("the session's time goes on", connected.connectedSinceMillis, offline.connectedSinceMillis)
        eventually("the engine stopped") { ProxyEngine().status().state == EngineState.Stopped }
        assertEquals("the interface stays, so traffic is held", 1, Descriptors.tun().size)
        assertTrue("Android keeps the VPN network", networks.present())
        eventually("the notification says so") {
            app.notificationTitle(ProxyFlowVpnService.STATUS_NOTIFICATION_ID) == "Waiting for a network · traffic paused"
        }
        assertHeld("offline")

        app.runtime.session.underlyingNetwork(real)
        val back = app.await(what = "Connected again with a network") { it.status == ConnectionStatus.Connected }
        assertEquals(connected.connectedSinceMillis, back.connectedSinceMillis)
        assertEquals(ReconnectReason.NetworkBack, history.states.last { it.reconnect != null }.reconnect?.reason)
        assertSent("offline-back")
        app.awaitLog("the time without a network") { it.event == LogEvent.Offline && it.profileId == profile.id }
    }

    /**
     * A move to another network ends the engine's connections, so apps make new ones on the new network: a
     * connection held open through the proxy ends at once, ProxyFlow stays connected, and traffic goes on.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun aNetworkChangeEndsTheEnginesConnectionsAndTrafficGoesOn() {
        val real = awaitRealNetwork()
        val profile = socks()
        app.connect(profile.id)
        assertSent("network-change-before")

        var held = ""
        val holder = thread(name = "held-connection") { held = FromShell.hold(env.destinationName, lab.bulkPort, HOLD_S) }
        eventually("the held connection is open in the engine") {
            ProxyEngine().flows().any { it.port == lab.bulkPort && it.state == "open" }
        }
        val resetsBefore = ProxyEngine().stats().resets
        app.runtime.session.underlyingNetwork(VpnSession.NetworkInfo(id = "e2e-${random()}", transport = "wifi"))
        holder.join(HOLD_ENDS_MS)

        assertFalse("the held connection ended", holder.isAlive)
        Log.i(LAB_TAG, "network change: held connection $held")
        val ended = checkNotNull(Regex("^ENDED (\\d+) (\\w+)$").find(held)) { "the held connection: $held" }
        assertTrue("ended long before its $HOLD_S s: $held", ended.groupValues[1].toLong() < HOLD_S * 1_000L / 2)
        assertTrue("ended by the reset: $held", ended.groupValues[2] in setOf("reset", "closed"))
        assertEquals(resetsBefore + 1, ProxyEngine().stats().resets)
        assertEquals("still connected", ConnectionStatus.Connected, app.controller.state.value.status)
        assertSent("network-change-after")
        val changed = app.awaitLog("the network change") { it.event == LogEvent.NetworkChanged && it.details[LogDetail.NETWORK] == "wifi" }
        assertTrue("$changed", checkNotNull(changed.details[LogDetail.RESET]).toInt() >= 1)
        app.runtime.session.underlyingNetwork(real)
    }

    /** Connect and disconnect as fast as they can be tapped: one consistent end, nothing left behind, and the next connect works. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun rapidConnectsAndDisconnectsEndConsistently() {
        val profile = socks()
        repeat(RAPID_CYCLES) {
            app.controller.connect(profile.id)
            app.controller.disconnect()
        }
        app.awaitStatus(ConnectionStatus.Disconnected)
        eventually("no session job is left", diagnose = { Diagnostics.report(app) }) { app.runtime.session.activeJobs() == 0 }
        eventually("the VPN interface closes") { Descriptors.tun().isEmpty() }
        eventually("the VPN service stops") { ProxyFlowVpnService.liveInstances.get() == 0 }
        eventually("Android has no VPN network") { !networks.present() }
        assertEquals(ConnectionStatus.Disconnected, app.controller.state.value.status)
        assertEquals(null, app.controller.state.value.reconnect)
        assertEquals(null, app.runtime.session.traffic())

        app.connect(profile.id)
        assertSent("rapid-after")
    }

    /** The network monitor reports the network Android gives ProxyFlow's own uid: the phone's real one, never the VPN. */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun theNetworkMonitorKnowsThePhonesNetwork() {
        val profile = socks()
        app.connect(profile.id)
        val reported = awaitRealNetwork()
        val connectivity = app.context.getSystemService(ConnectivityManager::class.java)
        val active = checkNotNull(connectivity.activeNetwork) { "Android gives this app no network" }
        val capabilities = checkNotNull(connectivity.getNetworkCapabilities(active))
        assertFalse("ProxyFlow is outside its VPN", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        assertEquals("the network Android names", active.toString(), reported.id)
        val kind = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        assertEquals(kind, reported.transport)
        Log.i(LAB_TAG, "network monitor: ${reported.transport}")
    }

    /**
     * Fails apps' connections through the broken gate until ProxyFlow reconnects on its own (three in a row
     * suffice; other apps' failing connections count too), at most [MAX_FAILING_LINES] lines; the first state
     * of the reconnection, which has the first attempt's wait.
     */
    private fun failUntilReconnecting(label: String): ConnectionState {
        fun started() = history.states.firstOrNull { it.status == ConnectionStatus.Reconnecting && it.reconnect != null }
        repeat(MAX_FAILING_LINES) { i ->
            started()?.let { return it }
            assertRefused("$label-$i")
        }
        eventually("ProxyFlow reconnecting after failures in a row", diagnose = { Diagnostics.report(app) }) { started() != null }
        return checkNotNull(started())
    }

    private fun awaitRealNetwork(): VpnSession.NetworkInfo {
        eventually("Android reported the phone's network to the session") { app.runtime.session.lastNetwork != null }
        return checkNotNull(app.runtime.session.lastNetwork).also { if (realNetwork == null) realNetwork = it }
    }

    private fun socks(): ProxyProfile =
        app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)

    private fun http(): ProxyProfile = app.save("HTTP", ProxyProtocol.HTTP, env.proxyHost, env.httpPort, env.httpUser, env.httpPassword)

    /** SOCKS5 through the gate, which relays to Dante until a test breaks it. */
    private fun gated(): ProxyProfile =
        app.save("Gate", ProxyProtocol.SOCKS5, env.proxyHost, lab.gatePort, env.socksUser, env.socksPassword)

    /** One line from the shell to the destination by name, answered there (m6_evidence.py finds it there once). */
    private fun assertSent(label: String) {
        val line = Lines.next(label)
        Lines.sent(line, env.destinationName, env.destinationPort)
        assertEquals("REPLY PROXYFLOW-DEST $line", FromShell.tcp(env.destinationName, env.destinationPort, line))
    }

    /** One line that must get nowhere (m6_evidence.py checks no destination has it). */
    private fun assertRefused(label: String) {
        val line = Lines.next(label)
        Lines.refused(line, env.destinationName, env.destinationPort)
        val result = FromShell.tcp(env.destinationName, env.destinationPort, line)
        assertTrue("the connection must fail: $result", result.startsWith("ERROR"))
    }

    /** One line to the sink while traffic is held: no answer, and m5_evidence.py fails on any connection the sink logged. */
    private fun assertHeld(label: String) {
        val line = "PF-held-$label-${random()}"
        Log.i(LAB_TAG, "held $line")
        assertEquals(
            "nothing gets out while traffic is held",
            "",
            Shell.exchange(env.destinationAddress, env.sinkPort, line, timeoutSeconds = HELD_S),
        )
    }

    private fun allowed(expected: Long): Long = expected + expected / 50 + TOLERANCE_BYTES

    private fun random() = UUID.randomUUID().toString().replace("-", "").take(12)

    private companion object {
        const val TEST_TIMEOUT_MS = 120_000L
        const val UP_BYTES = 1_048_576L
        const val DOWN_BYTES = 4_194_304L

        /** Other apps' traffic through the VPN during a transfer, which the session's counters include by design. */
        const val TOLERANCE_BYTES = 256L * 1024

        /** Apps' connections that fail through the broken gate before ProxyFlow must be reconnecting (three in a row suffice). */
        const val MAX_FAILING_LINES = 6
        const val LONG_WAIT_MS = 60_000L
        const val NO_MORE_ATTEMPTS_MS = 2_000L
        const val RETRY_NOW_MS = 20_000L
        const val HOLD_S = 60
        const val HOLD_ENDS_MS = 15_000L
        const val HELD_S = 5
        const val RAPID_CYCLES = 10
    }
}
