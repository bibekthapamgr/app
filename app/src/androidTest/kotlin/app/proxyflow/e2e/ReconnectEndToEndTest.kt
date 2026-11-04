package app.proxyflow.e2e

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.engine.EngineFlow
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
import kotlinx.coroutines.runBlocking
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
        try {
            if (::history.isInitialized) history.close()
            if (::networks.isInitialized) networks.close()
            TestRetry.policy = RetryPolicy()
            if (::gate.isInitialized) runCatching { gate.forward(env.socksPort) }
            // The phone's real network first: a network a test told the session of must never stay for the next test.
            realNetwork?.let { app.runtime.session.underlyingNetwork(it) }
        } finally {
            app.disconnectAndSettle()
            app.deleteAllProfiles()
        }
    }

    /**
     * Transfers of known size through each protocol. Each transfer's own connection, as the engine counts it (what
     * Home's counters add up), carried exactly the bytes the app sent and received — no packet header, no
     * retransmission. Home's counters for the session are never less than what the transfers carried; whatever they
     * read more is other apps' traffic through the VPN meanwhile, which they count by design, and is reported for the
     * evidence. The session's summary in the log carries the same.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun theTrafficCountersMatchControlledTransfers() {
        for (profile in listOf(socks(), http())) {
            val connected = app.connect(profile.id)
            val before = app.awaitTraffic("the first reading") { it.sinceMillis == connected.connectedSinceMillis }

            val upload = transfer(profile, up = true, bytes = UP_BYTES)
            assertEquals("SENT $UP_BYTES REPLY GOT $UP_BYTES", upload.result)
            val download = transfer(profile, up = false, bytes = DOWN_BYTES)
            assertEquals("RECEIVED $DOWN_BYTES", download.result)

            val expectedUp = upload.expectedUp + download.expectedUp
            val expectedDown = upload.expectedDown + download.expectedDown
            val after = app.awaitTraffic("the transfers in the session's counters") {
                it.bytesUp - before.bytesUp >= expectedUp && it.bytesDown - before.bytesDown >= expectedDown
            }
            Log.i(
                LAB_TAG,
                "traffic ${profile.protocol} session up=${after.bytesUp - before.bytesUp}/$expectedUp " +
                    "down=${after.bytesDown - before.bytesDown}/$expectedDown",
            )

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
        val established = ProxyFlowVpnService.establishedInterfaces.get()
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
        assertEquals("the same interface throughout", established, ProxyFlowVpnService.establishedInterfaces.get())
        assertEquals(1, Descriptors.tun().size)
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

    /**
     * Disconnect while ProxyFlow waits for its next attempt: none runs after it — waited for past when it was due, with
     * the proxy back — and nothing is left behind.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun disconnectingWhileReconnectingCancelsIt() {
        TestRetry.policy = RetryPolicy(delaysMillis = listOf(CANCEL_WAIT_MS, CANCEL_WAIT_MS, CANCEL_WAIT_MS), jitter = 0.0)
        val profile = gated()
        app.connect(profile.id)
        assertSent("cancel-up")

        gate.refuse()
        assertNotNull(failUntilReconnecting("cancel").reconnect?.nextAttemptAtMillis)
        // Still reconnecting when Disconnect comes; a reconnection already over would only show that it stays over.
        val atDisconnect = app.controller.state.value
        assertEquals("reconnecting at the disconnect\n${history.describe()}", ConnectionStatus.Reconnecting, atDisconnect.status)
        app.controller.disconnect()
        app.awaitStatus(ConnectionStatus.Disconnected)
        val stoppedAt = SystemClock.elapsedRealtime() // the session has handled the disconnect: no attempt may follow it
        app.settleDisconnected()
        eventually("no session job is left", diagnose = { Diagnostics.report(app) }) { app.runtime.session.activeJobs() == 0 }
        gate.forward(env.socksPort)
        Thread.sleep(CANCEL_WAIT_MS + NO_MORE_ATTEMPTS_MS)

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
        val reconnecting = failUntilReconnecting("retry-now")
        val due = checkNotNull(reconnecting.reconnect?.nextAttemptAtMillis) { "no wait: $reconnecting" }
        gate.forward(env.socksPort)
        // What is left of the first attempt's wait when Retry now is tapped: far more than Connected may take after it.
        val waitLeft = due - System.currentTimeMillis()
        assertTrue("$waitLeft ms of the wait left\n${history.describe()}\n${Diagnostics.report(app)}", waitLeft > 2 * RETRY_NOW_MS)
        val tapped = SystemClock.elapsedRealtime()
        app.controller.retryNow()

        app.await(timeoutMillis = RETRY_NOW_MS, what = "Connected right after Retry now") { it.status == ConnectionStatus.Connected }
        val took = SystemClock.elapsedRealtime() - tapped
        Log.i(LAB_TAG, "retry now: connected $took ms after the tap, $waitLeft ms before the attempt was due")
        assertSent("retry-now-back")
    }

    /**
     * M10: the phone's network asks for a sign-in (a captive portal), as Android would tell the session: every state says
     * so, connected or not, and nothing else changes; the proxy then fails — a portal lets nothing through until the user
     * signs in — and ProxyFlow reconnects, holding traffic; once signed in, the next attempt runs at once instead of after
     * its wait.
     */
    @Test(timeout = TEST_TIMEOUT_MS)
    fun signingInToTheNetworkReconnectsAtOnce() {
        TestRetry.policy = RetryPolicy(delaysMillis = listOf(LONG_WAIT_MS, LONG_WAIT_MS, LONG_WAIT_MS), jitter = 0.0)
        val real = awaitRealNetwork()
        val profile = gated()
        app.connect(profile.id)
        assertSent("sign-in-up")
        val resets = ProxyEngine().stats().resets

        app.runtime.session.underlyingNetwork(real.copy(signIn = true))
        val asked = app.await(what = "the state saying the network asks for a sign-in") { it.networkSignIn }
        assertEquals("still connected: no move to another network", ConnectionStatus.Connected, asked.status)
        assertEquals("nothing reset", resets, ProxyEngine().stats().resets)

        gate.refuse()
        val reconnecting = failUntilReconnecting("sign-in")
        assertTrue("every state says so", reconnecting.networkSignIn)
        val due = checkNotNull(reconnecting.reconnect?.nextAttemptAtMillis) { "no wait: $reconnecting" }
        assertHeld("sign-in-reconnecting")

        gate.forward(env.socksPort)
        val waitLeft = due - System.currentTimeMillis()
        assertTrue("$waitLeft ms of the wait left\n${history.describe()}", waitLeft > 2 * RETRY_NOW_MS)
        app.runtime.session.underlyingNetwork(real)
        val back = app.await(RETRY_NOW_MS, "Connected right after signing in") { it.status == ConnectionStatus.Connected }
        assertFalse(back.networkSignIn)
        assertEquals(ReconnectReason.NetworkBack, history.states.last { it.reconnect != null }.reconnect?.reason)
        assertSent("sign-in-back")
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
        // Open in the engine, and its request through: a reset before the request would end it as a bad request instead.
        eventually("the held connection is open in the engine") {
            ProxyEngine().flows().any { it.port == lab.bulkPort && it.state == "open" && it.bytesUp >= "HOLD $HOLD_S\n".length }
        }
        val resetsBefore = ProxyEngine().stats().resets
        app.runtime.session.underlyingNetwork(VpnSession.NetworkInfo(id = "e2e-${random()}", transport = "wifi"))
        holder.join(HOLD_ENDS_MS)

        assertFalse("the held connection ended", holder.isAlive)
        Log.i(LAB_TAG, "network change: held connection $held")
        val ended = checkNotNull(Regex("^ENDED (\\d+) (\\w+)$").find(held)) { "the held connection: $held" }
        assertTrue("ended long before its $HOLD_S s: $held", ended.groupValues[1].toLong() < HOLD_S * 1_000L / 2)
        assertEquals("ended by the reset: $held", "reset", ended.groupValues[2])
        assertEquals(resetsBefore + 1, ProxyEngine().stats().resets)
        assertEquals("still connected", ConnectionStatus.Connected, app.controller.state.value.status)
        assertSent("network-change-after")
        val changed = app.awaitLog("the network change") {
            it.event == LogEvent.NetworkChanged && it.profileId == profile.id && it.details[LogDetail.RESET] != null
        }
        assertEquals("wifi", changed.details[LogDetail.NETWORK])
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
        // The taps only queue events: once the session has handled them all, the last one has won.
        runBlocking { app.runtime.session.awaitIdle() }
        assertEquals("the last tap wins", ConnectionStatus.Disconnected, app.controller.state.value.status)
        // The service starts they asked for still come, each let go at once: nothing may be left once they have.
        settled("nothing is left behind") {
            app.runtime.session.activeJobs() == 0 &&
                Descriptors.tun().isEmpty() &&
                ProxyFlowVpnService.liveInstances.get() == 0 &&
                !networks.present()
        }
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
        assertEquals(
            "a sign-in page as Android says (M10)",
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            reported.signIn,
        )
        Log.i(LAB_TAG, "network monitor: ${reported.transport}${if (reported.signIn) ", asks for a sign-in" else ""}")
    }

    /**
     * Fails apps' connections through the broken gate until ProxyFlow reconnects on its own (three in a row
     * suffice; other apps' failing connections count too), at most [MAX_FAILING_LINES] lines; the first state
     * of the reconnection, which has the first attempt's wait.
     */
    private fun failUntilReconnecting(label: String): ConnectionState {
        val from = history.states.size // what came before the proxy went away does not count
        fun started() = history.states.drop(from).firstOrNull { it.status == ConnectionStatus.Reconnecting && it.reconnect != null }
        repeat(MAX_FAILING_LINES) { i ->
            assertRefused("$label-$i")
            // The session hears of each failure a moment later; a line sent once traffic is held waits out its whole timeout.
            val until = SystemClock.elapsedRealtime() + FAILURE_HEARD_MS
            while (started() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            started()?.let { return it }
        }
        eventually("ProxyFlow reconnecting after failures in a row", diagnose = { Diagnostics.report(app) }) { started() != null }
        return checkNotNull(started())
    }

    /** Waits until [condition] has held for [QUIET_MS] in a row (things that come late, such as service starts, have come). */
    private fun settled(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + SETTLE_MS
        var since: Long? = null
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (condition()) {
                if (since == null) since = now
                if (now - since >= QUIET_MS) return
            } else {
                since = null
            }
            if (now > deadline) throw AssertionError("timed out waiting until $what\n${Diagnostics.report(app)}")
            Thread.sleep(50)
        }
    }

    private class Transfer(val result: String, val expectedUp: Long, val expectedDown: Long)

    /**
     * One transfer from the shell to the bulk server; its connection stays open a moment after the data, while the
     * engine's own count for it is read: exactly the request line and the data one way, the reply the other.
     */
    private fun transfer(profile: ProxyProfile, up: Boolean, bytes: Long): Transfer {
        val expectedUp = if (up) "UP $bytes\n".length + bytes else "DOWN $bytes\n".length.toLong()
        val expectedDown = if (up) "GOT $bytes\n".length.toLong() else bytes
        var result = ""
        val client =
            thread(name = "bulk") { result = FromShell.bulk(env.destinationName, lab.bulkPort, up, bytes, lingerMillis = LINGER_MS) }
        var counted: EngineFlow? = null
        while (client.isAlive) {
            ProxyEngine().flows().lastOrNull { it.port == lab.bulkPort }?.let { counted = it }
            if (counted?.let { it.bytesUp == expectedUp && it.bytesDown == expectedDown } == true) break
            Thread.sleep(20)
        }
        client.join(TRANSFER_MS)
        val direction = if (up) "up" else "down"
        Log.i(
            LAB_TAG,
            "traffic ${profile.protocol} $direction flow up=${counted?.bytesUp}/$expectedUp down=${counted?.bytesDown}/$expectedDown",
        )
        assertEquals("$direction: what the transfer's connection carried up", expectedUp, counted?.bytesUp)
        assertEquals("$direction: what the transfer's connection carried down", expectedDown, counted?.bytesDown)
        return Transfer(result, expectedUp, expectedDown)
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

    /**
     * One line that must get nowhere (m6_evidence.py checks no destination has it), to the destination's address: a line
     * sent just as traffic is held then waits out only its connect timeout, while a name's lookup, held too, has none.
     */
    private fun assertRefused(label: String) {
        val line = Lines.next(label)
        Lines.refused(line, env.destinationAddress, env.destinationPort)
        val result = FromShell.tcp(env.destinationAddress, env.destinationPort, line)
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

    private fun random() = UUID.randomUUID().toString().replace("-", "").take(12)

    private companion object {
        const val TEST_TIMEOUT_MS = 120_000L
        const val UP_BYTES = 1_048_576L
        const val DOWN_BYTES = 4_194_304L

        /** How long a transfer's connection stays open after its data, for the engine's count to be read. */
        const val LINGER_MS = 2_000
        const val TRANSFER_MS = 60_000L

        /** Apps' connections that fail through the broken gate before ProxyFlow must be reconnecting (three in a row suffice). */
        const val MAX_FAILING_LINES = 6

        /**
         * Far longer than a reconnection's start can take on a slow emulator (a line sent just as traffic is held waits
         * out its timeout), so Retry now still has most of it left to cut short.
         */
        const val LONG_WAIT_MS = 120_000L

        /**
         * Long enough that the reconnection is still waiting when the test disconnects, however long the probe took (a
         * line sent just as traffic is held waits out its 10 s connect timeout); short enough to wait past afterwards.
         */
        const val CANCEL_WAIT_MS = 20_000L
        const val NO_MORE_ATTEMPTS_MS = 2_000L
        const val FAILURE_HEARD_MS = 3_000L
        const val QUIET_MS = 2_000L
        const val SETTLE_MS = 30_000L
        const val RETRY_NOW_MS = 20_000L
        const val HOLD_S = 60
        const val HOLD_ENDS_MS = 15_000L
        const val HELD_S = 5
        const val RAPID_CYCLES = 10
    }
}
