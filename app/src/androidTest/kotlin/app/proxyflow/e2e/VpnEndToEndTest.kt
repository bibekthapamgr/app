package app.proxyflow.e2e

import android.app.Notification
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.engine.EngineState
import app.proxyflow.core.engine.ProxyEngine
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5 end to end on an emulator: the real VpnService, engine and app, real proxies
 * (Dante for SOCKS5, Squid for HTTP CONNECT, both with sign-in) on the CI runner,
 * and TCP from the shell — a user the VPN covers — to a destination that is only
 * reachable by name through a proxy. The runner's proxy and destination logs and a
 * packet capture of the emulator are checked afterwards (scripts/m5_evidence.py);
 * each line sent is logged under [TAG] ("exchange", or "held" for one that must not
 * arrive) so they can be matched.
 *
 * Every test checks what Android and the process see, not only ProxyFlow's own
 * state: VPN networks and the interface they route into (ConnectivityManager),
 * interface descriptors (/proc/self/fd), interfaces established, service instances
 * and notifications.
 */
@RunWith(AndroidJUnit4::class)
class VpnEndToEndTest {
    private val app = App()
    private lateinit var env: E2eConfig
    private lateinit var networks: VpnNetworks
    private lateinit var history: StateHistory
    private var establishedBefore = 0

    @Before
    fun setUp() {
        env = E2eConfig.load()
        app.setVpnConsent(allowed = true)
        app.allowNotifications()
        app.disconnectAndSettle(timeoutMillis = SETTLE_BEFORE_TEST_MS)
        app.deleteAllProfiles()
        networks = VpnNetworks(app.context)
        history = StateHistory(app.controller)
        establishedBefore = ProxyFlowVpnService.establishedInterfaces.get()
    }

    @After
    fun tearDown() {
        if (::history.isInitialized) history.close()
        if (::networks.isInitialized) networks.close()
        app.setVpnConsent(allowed = true)
        app.disconnectAndSettle()
        app.deleteAllProfiles()
    }

    @Test
    fun socks5TrafficReachesTheDestinationThroughTheProxyByName() {
        val profile = socks()

        val connected = app.connect(profile.id)

        assertEquals(profile.displayName, connected.profileName)
        assertConnectedAsAndroidSeesIt()
        eventually("the notification says Connected", diagnose = { Diagnostics.report(app) }) {
            app.notificationTitle(ProxyFlowVpnService.STATUS_NOTIFICATION_ID) == "Connected"
        }
        val status = checkNotNull(app.notification(ProxyFlowVpnService.STATUS_NOTIFICATION_ID))
        assertEquals(listOf("Disconnect"), status.actions?.map { it.title.toString() })
        // Threat model I-11: the lock screen gets a version without the profile name.
        assertEquals(profile.displayName, status.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(Notification.VISIBILITY_PRIVATE, status.visibility)
        val public = checkNotNull(status.publicVersion) { "no public version" }
        val publicText = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT)
            .mapNotNull { public.extras.getCharSequence(it)?.toString() }
        assertTrue("the public version names no profile: $publicText", publicText.none { profile.displayName in it })
        assertReachesDestination("socks5")

        app.disconnectAndSettle()
        assertReleased()
    }

    @Test
    fun httpConnectTrafficReachesTheDestinationThroughTheProxyByName() {
        val profile = http()

        app.connect(profile.id)

        assertConnectedAsAndroidSeesIt()
        assertReachesDestination("http-connect")
        app.disconnectAndSettle()
        assertReleased()
    }

    /** Threat model S3: other apps find no port to talk to (the adapters run in-process, ADR-0002). */
    @Test
    fun nothingListensWhileConnected() {
        app.connect(socks().id)
        assertReachesDestination("listeners")

        val listening = Descriptors.listening(Process.myUid())

        assertEquals("sockets of ProxyFlow that accept connections or datagrams", emptyList<String>(), listening)
        app.disconnectAndSettle()
    }

    /**
     * Traffic sent while a switch is under way, and after it failed, must not arrive anywhere:
     * the switch goes to a proxy that never answers (its check runs until the handshake
     * times out), then on to a working one.
     */
    @Test
    fun switchingProfilesKeepsTrafficInTheTunnel() {
        val socks = socks()
        val http = http()
        val silent = app.save("Silent", ProxyProtocol.SOCKS5, env.proxyHost, env.silentPort)
        app.connect(socks.id)
        assertReachesDestination("switch-socks5")

        app.controller.connect(silent.id)
        app.await(what = "Reconnecting to the silent proxy") { it.status == ConnectionStatus.Reconnecting && it.profileId == silent.id }
        assertHeld("switch-reconnecting")
        val blocked = app.await(what = "Blocked by the silent proxy") { it.status == ConnectionStatus.Blocked && it.profileId == silent.id }
        assertEquals("HANDSHAKE_TIMEOUT", blocked.error?.code)
        assertEquals("the interface stays, so traffic is held", 1, Descriptors.tun().size)
        assertTrue("Android keeps the VPN network", networks.present())
        assertHeld("switch-blocked")

        app.connect(http.id)

        assertTrue(
            "switching shows Reconnecting with the new profile: ${history.states}",
            history.states.any { it.status == ConnectionStatus.Reconnecting && it.profileId == http.id },
        )
        assertConnectedAsAndroidSeesIt()
        assertEquals("the old interface is closed once the new one runs", 1, Descriptors.tun().size)
        assertReachesDestination("switch-http-connect")
        app.disconnectAndSettle()
        assertReleased()
    }

    @Test
    fun aWrongPasswordNeverConnects() {
        val profiles = listOf(
            app.save("Wrong SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, "wrong-${random()}"),
            app.save("Wrong HTTP", ProxyProtocol.HTTP, env.proxyHost, env.httpPort, env.httpUser, "wrong-${random()}"),
        )

        for (profile in profiles) {
            val ended = app.connectExpectingFailure(profile.id)
            assertEquals("${profile.protocol}", "AUTH_FAILED", ended.error?.code)
        }

        assertNeverConnected()
    }

    @Test
    fun anUnreachableProxyNeverConnects() {
        val closed = app.save("Closed port", ProxyProtocol.SOCKS5, env.proxyHost, env.closedPort)

        val ended = app.connectExpectingFailure(closed.id)

        assertEquals("PROXY_UNREACHABLE", ended.error?.code)
        assertNeverConnected()
    }

    @Test
    fun cancellingTheCheckChangesNothing() {
        val silent = app.save("Silent", ProxyProtocol.SOCKS5, env.proxyHost, env.silentPort)

        app.controller.connect(silent.id)
        app.await(what = "Connecting") { it.status == ConnectionStatus.Connecting && it.profileId == silent.id }
        Thread.sleep(CHECK_RUNNING_MS) // the check waits for a proxy that never answers
        assertTrue("nothing is switched over during the check", Descriptors.tun().isEmpty())
        assertFalse(networks.present())
        app.controller.disconnect()
        val ended = app.awaitStatus(ConnectionStatus.Disconnected)

        assertNull("a cancel is not an error", ended.error)
        assertNeverConnected()
        // Its socket is shut down: the check returns at once, not after its 15 s timeout.
        eventually("the cancelled check has returned", timeoutMillis = CHECK_CUT_SHORT_MS, diagnose = { Diagnostics.report(app) }) {
            app.runtime.session.activeJobs() == 0
        }
    }

    @Test
    fun repeatedStartsMakeOneSession() {
        val profile = socks()

        repeat(3) { app.controller.connect(profile.id) }
        // A start of the service itself, as Android would deliver a second Connect.
        app.context.startService(Intent(app.context, ProxyFlowVpnService::class.java).setAction(ProxyFlowVpnService.ACTION_CONNECT))
        app.awaitStatus(ConnectionStatus.Connected)
        repeat(2) { app.controller.connect(profile.id) }
        Thread.sleep(SETTLE_MS)

        assertEquals("one interface", 1, Descriptors.tun().size)
        assertEquals("one service", 1, ProxyFlowVpnService.liveInstances.get())
        assertEquals("connected once", 1, history.states.count { it.status == ConnectionStatus.Connected })
        app.disconnectAndSettle()
        assertReleased()
    }

    @Test
    fun repeatedConnectAndDisconnectLeaveNothingBehind() {
        val profile = socks()
        var socketsAfterSecond = 0
        var threadsAfterSecond = 0

        repeat(CYCLES) { cycle ->
            app.connect(profile.id)
            assertEquals(1, Descriptors.tun().size)
            assertEquals(1, ProxyFlowVpnService.liveInstances.get())
            app.disconnectAndSettle()
            assertReleased()
            if (cycle == 1) {
                socketsAfterSecond = Descriptors.sockets()
                threadsAfterSecond = Descriptors.threads()
            }
        }

        eventually("sockets back to the second cycle's count ($socketsAfterSecond)") { Descriptors.sockets() <= socketsAfterSecond }
        val threads = Descriptors.threads()
        Log.i(
            TAG,
            "cycles=$CYCLES sockets=${Descriptors.sockets()} threads=$threads (after the second cycle $socketsAfterSecond/$threadsAfterSecond)",
        )
        assertTrue("threads $threads, after the second cycle $threadsAfterSecond", threads <= threadsAfterSecond + THREAD_SLACK)
    }

    @Test
    fun revocationClosesEverythingAndSaysSo() {
        val profile = socks()
        app.connect(profile.id)

        // The user withdraws the permission; Android takes the VPN away at the next prepare().
        app.setVpnConsent(allowed = false)
        assertNotNull("Android asks for consent again", VpnService.prepare(app.context))
        val revoked = app.await(what = "VPN_REVOKED") { it.error?.code == ConnectionError.VPN_REVOKED }

        assertEquals(ConnectionStatus.Disconnected, revoked.status)
        eventually("the VPN service stops") { ProxyFlowVpnService.liveInstances.get() == 0 }
        eventually("the interface closes") { Descriptors.tun().isEmpty() }
        eventually("Android removed the VPN network") { !networks.present() }
        eventually("the problem alert shows") {
            app.notificationTitle(ProxyFlowVpnService.PROBLEM_NOTIFICATION_ID) == "Disconnected by Android"
        }
        assertReleased()

        app.setVpnConsent(allowed = true)
        app.connect(profile.id)
        eventually("a new connection clears the alert") { app.notification(ProxyFlowVpnService.PROBLEM_NOTIFICATION_ID) == null }
        app.disconnectAndSettle()
    }

    @Test
    fun anEngineFailureHoldsTrafficUntilRestart() {
        val profile = socks()
        app.connect(profile.id)
        assertReachesDestination("before-engine-failure")

        // Make the engine's reads fail (EISDIR) while a duplicate keeps the interface itself alive.
        val tun = Descriptors.tun().single()
        val keep = ParcelFileDescriptor.fromFd(tun)
        val directory = Os.open("/", OsConstants.O_RDONLY, 0)
        try {
            Os.dup2(directory, tun)
        } finally {
            Os.close(directory)
        }
        val blocked = app.awaitStatus(ConnectionStatus.Blocked)

        assertEquals(ConnectionError.ENGINE_STOPPED, blocked.error?.code)
        assertTrue("the VPN stays, so apps' traffic is held", networks.present())
        eventually("the notification says Traffic blocked") {
            app.notificationTitle(ProxyFlowVpnService.STATUS_NOTIFICATION_ID) == "Traffic blocked"
        }
        assertHeld("engine-failure")

        app.controller.restart()
        app.awaitStatus(ConnectionStatus.Connected)
        keep.close()

        assertEquals(1, Descriptors.tun().size)
        assertConnectedAsAndroidSeesIt()
        assertReachesDestination("after-restart")
        app.disconnectAndSettle()
        assertReleased()
    }

    private fun socks(): ProxyProfile =
        app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword)

    private fun http(): ProxyProfile = app.save("HTTP", ProxyProtocol.HTTP, env.proxyHost, env.httpPort, env.httpUser, env.httpPassword)

    /** One line from the shell to the destination, by name, and its answer. */
    private fun assertReachesDestination(label: String) {
        val line = "PF-$label-${random()}"
        Log.i(TAG, "exchange $line")
        assertEquals("PROXYFLOW-DEST $line", Shell.exchange(env.destinationName, env.destinationPort, line))
    }

    /** One line to the destination's address while traffic is held: no answer, and it never arrives (m5_evidence.py). */
    private fun assertHeld(label: String) {
        val line = "PF-held-$label-${random()}"
        Log.i(TAG, "held $line")
        assertEquals(
            "nothing reaches the destination while traffic is held",
            "",
            Shell.exchange(env.destinationAddress, env.destinationPort, line, timeoutSeconds = HELD_TIMEOUT_S),
        )
    }

    /**
     * Connected as Android sees it: one interface, and Android's VPN network ready and leading
     * into that interface (so apps' traffic goes to it), seen by the sampler too — a sampler
     * that saw nothing would make every "Android never had a VPN network" check meaningless.
     */
    private fun assertConnectedAsAndroidSeesIt() {
        val tun = Descriptors.tun()
        assertEquals("one VPN interface", 1, tun.size)
        val name = checkNotNull(ProxyFlowVpnService.interfaceName(tun.single())) { "the interface's name can't be read" }
        val vpn = networks.current()
        assertTrue("Android routes into $name and its network is ready: $vpn", vpn.any { it.interfaceName == name && it.validated })
        assertTrue("the sampler saw the VPN network", networks.appearances >= 1)
        assertEquals("one VPN service", 1, ProxyFlowVpnService.liveInstances.get())
        assertEquals(EngineState.Running, ProxyEngine().status().state)
    }

    private fun assertReleased() {
        assertTrue("no VPN interface", Descriptors.tun().isEmpty())
        assertEquals("no VPN service", 0, ProxyFlowVpnService.liveInstances.get())
        assertEquals("the engine stopped", EngineState.Stopped, ProxyEngine().status().state)
        assertEquals("no session job", 0, app.runtime.session.activeJobs())
        eventually("Android has no VPN network", diagnose = { Diagnostics.report(app) }) { !networks.present() }
        eventually("the status notification is removed") { app.notification(ProxyFlowVpnService.STATUS_NOTIFICATION_ID) == null }
    }

    private fun assertNeverConnected() {
        assertTrue("never Connected: ${history.states}", history.neverConnected())
        assertEquals("no VPN interface was established", establishedBefore, ProxyFlowVpnService.establishedInterfaces.get())
        assertEquals("Android never had a VPN network", 0, networks.appearances)
        assertTrue("no VPN interface", Descriptors.tun().isEmpty())
        eventually("the VPN service stops", diagnose = { Diagnostics.report(app) }) { ProxyFlowVpnService.liveInstances.get() == 0 }
    }

    private fun random() = UUID.randomUUID().toString().replace("-", "").take(16)

    private companion object {
        const val TAG = "ProxyFlowE2E"
        const val CYCLES = 10
        const val THREAD_SLACK = 6
        const val CHECK_RUNNING_MS = 1_500L
        const val CHECK_CUT_SHORT_MS = 3_000L
        const val SETTLE_MS = 1_000L
        const val HELD_TIMEOUT_S = 5
    }
}
