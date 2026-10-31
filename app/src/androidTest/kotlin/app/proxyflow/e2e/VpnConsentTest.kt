package app.proxyflow.e2e

import android.util.Log
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.proxyflow.MainActivity
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.vpn.ProxyFlowVpnService
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The VPN permission through the real screens and Android's own consent dialog
 * (com.android.vpndialogs): the explainer (B9) comes first, Android's dialog
 * second, and nothing starts before the user allows it. Refusing shows H1 with
 * nothing changed; allowing connects through the real proxy.
 */
@RunWith(AndroidJUnit4::class)
class VpnConsentTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = App()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private lateinit var env: E2eConfig
    private lateinit var networks: VpnNetworks
    private lateinit var history: StateHistory
    private var establishedBefore = 0

    @Before
    fun setUp() {
        env = E2eConfig.load()
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
    fun refusingAndroidsDialogShowsWhyAndChangesNothing() {
        app.setVpnConsent(allowed = false)
        use(app.save("SOCKS", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, env.socksPassword))

        openAndroidsDialog()
        assertNothingStarted()
        press(By.res("android", "button2"), "(?i)cancel")

        waitForText("VPN permission needed")
        assertEquals(ConnectionStatus.Disconnected, app.controller.state.value.status)
        assertNothingStarted()
        assertTrue(history.neverConnected())
        waitForText("Not now").performClick()
        waitForText("Connect")
    }

    @Test
    fun allowingAndroidsDialogConnectsThroughTheProxy() {
        app.setVpnConsent(allowed = false)
        use(app.save("HTTP", ProxyProtocol.HTTP, env.proxyHost, env.httpPort, env.httpUser, env.httpPassword))

        openAndroidsDialog()
        assertNothingStarted()
        press(By.res("android", "button1"), "(?i)ok")

        waitForText("Connected", CONNECT_TIMEOUT_MS)
        waitForText("Traffic is going through your proxy")
        eventually("the notification says Connected", diagnose = { Diagnostics.report(app) }) {
            app.notificationTitle(ProxyFlowVpnService.STATUS_NOTIFICATION_ID) == "Connected"
        }
        val tun = Descriptors.tun().single()
        val name = ProxyFlowVpnService.interfaceName(tun)
        assertTrue("Android routes into $name: ${networks.current()}", networks.current().any { it.interfaceName == name && it.validated })
        val line = "PF-consent-${System.nanoTime()}"
        Log.i(TAG, "exchange $line")
        assertEquals("PROXYFLOW-DEST $line", Shell.exchange(env.destinationName, env.destinationPort, line))

        waitForText("Disconnect").performScrollTo().performClick()
        waitForText("Not connected")
        eventually("the interface closes", diagnose = { Diagnostics.report(app) }) { Descriptors.tun().isEmpty() }
        eventually("the VPN service stops", diagnose = { Diagnostics.report(app) }) { ProxyFlowVpnService.liveInstances.get() == 0 }
    }

    @Test
    fun aWrongPasswordSaysWhyAndNeverShowsConnected() {
        app.setVpnConsent(allowed = true)
        use(app.save("Wrong", ProxyProtocol.SOCKS5, env.proxyHost, env.socksPort, env.socksUser, "wrong-${System.nanoTime()}"))

        waitForText("Connect").performClick()

        waitForText("Username or password rejected", CONNECT_TIMEOUT_MS)
        waitForText("You're not connected — apps are still using your normal network.")
        assertEquals(ConnectionError("AUTH_FAILED"), app.controller.state.value.error?.copy(detail = null))
        assertTrue("never Connected: ${history.states}", history.neverConnected())
        assertNothingStarted()
    }

    /** Makes [profile] the one Home shows. */
    private fun use(profile: ProxyProfile) {
        runBlocking { app.profiles.setActive(profile.id) }
        waitForText(profile.displayName)
    }

    /** Connect, the explainer (B9), Continue: Android's dialog is in front. */
    private fun openAndroidsDialog() {
        waitForText("Connect").performClick()
        waitForText("Allow a VPN connection")
        waitForText("Only one VPN app can be active. Starting another turns ProxyFlow off.", substring = true)
        compose.onNodeWithText("Continue").performClick()
        val opened = device.wait(Until.hasObject(By.pkg(VPN_DIALOGS)), DIALOG_TIMEOUT_MS)
        // The report only for a failure: it reads ProxyFlow's screen, which isn't there while Android's dialog is.
        if (!opened) throw AssertionError("Android's VPN dialog did not open\n${screenReport()}")
    }

    /** What is in front, what has focus, and what Android logged about starting activities and the VPN. */
    private fun screenReport(): String = buildString {
        val shown = runCatching {
            listOf("Allow a VPN connection", "VPN permission needed", "Connecting…", "Connected", "Not connected")
                .filter { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }
        }.getOrElse { "not on screen (${it.javaClass.simpleName})" }
        appendLine("ProxyFlow shows: $shown")
        appendLine("activities:")
        Shell.run("dumpsys activity activities").lines()
            .filter { "ResumedActivity" in it || "mFocusedApp" in it || "mLastPausedActivity" in it }
            .take(REPORT_LINES).forEach { appendLine("  ${it.trim()}") }
        appendLine("windows:")
        Shell.run("dumpsys window").lines()
            .filter { "mCurrentFocus" in it || "mFocusedApp" in it || "mFocusedWindow" in it }
            .take(REPORT_LINES).forEach { appendLine("  ${it.trim()}") }
        appendLine("log:")
        Shell.run("logcat -d -t 1500").lines()
            .filter { line -> LOG_TAGS.any { it in line } }
            .takeLast(REPORT_LINES * 2).forEach { appendLine("  ${it.trim()}") }
        append(Diagnostics.report(app))
    }

    /** Presses a button of Android's dialog: by its id, or else by its text. */
    private fun press(id: BySelector, text: String) {
        val button = device.wait(Until.findObject(id), DIALOG_TIMEOUT_MS)
            ?: device.wait(Until.findObject(By.pkg(VPN_DIALOGS).text(Pattern.compile(text))), DIALOG_TIMEOUT_MS)
        checkNotNull(button) { "no such button in Android's VPN dialog" }.click()
        assertTrue("Android's dialog closes", device.wait(Until.gone(By.pkg(VPN_DIALOGS)), DIALOG_TIMEOUT_MS))
    }

    private fun assertNothingStarted() {
        assertFalse("no VPN network", networks.present())
        assertEquals("Android never had a VPN network", 0, networks.appearances)
        assertEquals("no VPN interface was established", establishedBefore, ProxyFlowVpnService.establishedInterfaces.get())
        assertTrue("no VPN interface", Descriptors.tun().isEmpty())
        eventually("no VPN service", diagnose = { Diagnostics.report(app) }) { ProxyFlowVpnService.liveInstances.get() == 0 }
    }

    private fun waitForText(text: String, timeoutMillis: Long = UI_TIMEOUT_MS, substring: Boolean = false): SemanticsNodeInteraction {
        compose.waitUntil(timeoutMillis) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNodeWithText(text, substring = substring)
    }

    private companion object {
        const val TAG = "ProxyFlowE2E"
        const val VPN_DIALOGS = "com.android.vpndialogs"
        const val UI_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val DIALOG_TIMEOUT_MS = 30_000L
        const val REPORT_LINES = 12
        val LOG_TAGS = listOf("ActivityTaskManager", "vpndialogs", "ConfirmDialog", "VpnManager", "Vpn:", "AndroidRuntime", "ProxyFlowVpn")
    }
}
