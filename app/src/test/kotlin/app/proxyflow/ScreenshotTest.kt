package app.proxyflow

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ConnectStep
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.DnsProblems
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.ProfileValidator
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.TlsMode
import app.proxyflow.core.model.TlsProblems
import app.proxyflow.core.model.UnsupportedTraffic
import app.proxyflow.feature.about.AboutScreen
import app.proxyflow.feature.home.HomeActions
import app.proxyflow.feature.home.HomeDialog
import app.proxyflow.feature.home.HomeScreen
import app.proxyflow.feature.home.HomeUiState
import app.proxyflow.feature.logs.LogsScreen
import app.proxyflow.feature.logs.LogsUiState
import app.proxyflow.feature.profiles.AddEditActions
import app.proxyflow.feature.profiles.AddEditProxyScreen
import app.proxyflow.feature.profiles.AddEditUiState
import app.proxyflow.feature.profiles.ConnectionTestState
import app.proxyflow.feature.profiles.ProfilesScreen
import app.proxyflow.feature.profiles.ProfilesUiState
import app.proxyflow.feature.settings.SettingsScreen
import app.proxyflow.feature.settings.SettingsUiState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of every screen and state built so far (M2, M3, M5, M6) in light and dark
 * theme, named after the M1 design boards, kept in app/screenshots. CI runs
 * `./gradlew :app:verifyAndRecordRoborazziDebug`: any screen that no longer
 * matches its committed copy fails the build, and the new image is published on
 * the ci-screenshots/<branch> branch to be reviewed and committed. Locally,
 * `recordRoborazziDebug` rewrites them; a plain `testDebugUnitTest` renders every
 * screen but neither compares nor writes files.
 *
 * The proxy form states are the ones its ViewModel produces for that input (D3
 * takes its errors from the validator itself), so those images show what a user
 * sees. D5 shows the only answer "Test connection" can give before the engine
 * exists (M4): not available, never a pass. F2 shows sample log entries:
 * nothing writes to the log before M7.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val defaultZone: TimeZone = TimeZone.getDefault()

    // Log times are shown in the phone's time zone; fix it so screenshots match on every machine.
    @Before
    fun useUtc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(defaultZone)

    @Test
    fun homeEmpty() = captureBothThemes("B1_home_empty") { HomeScreen(HomeUiState.NoProfile, HomeActions()) }

    @Test
    fun homeWithProfile() = captureBothThemes("B2_home_disconnected") { HomeScreen(home(ConnectionState.Idle), HomeActions()) }

    @Test
    fun homeConnecting() = captureBothThemes("B3_home_connecting") {
        HomeScreen(home(ConnectionState(ConnectionStatus.Connecting, "p1", tokyo.name, step = ConnectStep.CheckingProxy)), HomeActions())
    }

    @Test
    fun homeConnected() = captureBothThemes("B4_home_connected") {
        val connected = ConnectionState(ConnectionStatus.Connected, "p1", tokyo.name, connectedSinceMillis = SAMPLE_TIME)
        // 00:12:47 on the timer, as on the design board.
        HomeScreen(home(connected, diagnostics = sampleDiagnostics), HomeActions(), nowMillis = SAMPLE_TIME + 767_000)
    }

    /**
     * B4 when things go wrong while connected (ADR-0011): which TLS check failed, names the proxy couldn't reach,
     * proxy timeouts and the last problem, as counts and codes only, never destinations.
     */
    @Test
    fun homeConnectedProblems() = captureBothThemes("B4_home_connected_problems") {
        val connected = ConnectionState(ConnectionStatus.Connected, "p1", tokyo.name, connectedSinceMillis = SAMPLE_TIME)
        HomeScreen(home(connected, diagnostics = troubleDiagnostics), HomeActions(), nowMillis = SAMPLE_TIME + 767_000)
    }

    @Test
    fun homeReconnecting() = captureBothThemes("B5_home_reconnecting") {
        HomeScreen(home(ConnectionState(ConnectionStatus.Reconnecting, "p1", tokyo.name, step = ConnectStep.CheckingProxy)), HomeActions())
    }

    @Test
    fun homeBlocked() = captureBothThemes("B6_home_blocked_engine_stopped") {
        val error = ConnectionError(ConnectionError.ENGINE_STOPPED, "reading the TUN interface failed")
        HomeScreen(home(ConnectionState(ConnectionStatus.Blocked, "p1", tokyo.name, error = error)), HomeActions())
    }

    @Test
    fun homeConnectFailed() = captureBothThemes("B6b_connect_failed") {
        HomeScreen(home(ConnectionState(profileId = "p1", error = ConnectionError("PROXY_UNREACHABLE"))), HomeActions())
    }

    @Test
    fun homeVpnRevoked() = captureBothThemes("B6d_vpn_revoked") {
        HomeScreen(home(ConnectionState(profileId = "p1", error = ConnectionError(ConnectionError.VPN_REVOKED))), HomeActions())
    }

    @Test
    fun vpnExplainer() = captureDialog("B9_vpn_explainer") {
        HomeScreen(home(ConnectionState.Idle, HomeDialog.VpnExplainer(Intent("android.net.vpn.SETUP_VPN"))), HomeActions())
    }

    @Test
    fun vpnPermissionDenied() = captureDialog("H1_vpn_permission_denied") {
        HomeScreen(home(ConnectionState.Idle, HomeDialog.VpnPermissionDenied), HomeActions())
    }

    @Test
    fun stopWhileBlocked() = captureDialog("B6_stop_confirm") {
        val blocked = ConnectionState(ConnectionStatus.Blocked, "p1", tokyo.name, error = ConnectionError(ConnectionError.ENGINE_STOPPED))
        HomeScreen(home(blocked, HomeDialog.ConfirmStop), HomeActions())
    }

    @Test
    fun profilesEmpty() = captureBothThemes("C1_profiles_empty") {
        ProfilesScreen(ProfilesUiState(loading = false), onAddProxy = {}, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {})
    }

    @Test
    fun profilesList() = captureBothThemes("C2_profiles_list") {
        ProfilesScreen(profilesState, onAddProxy = {}, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {})
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun deleteConfirmation() {
        compose.setContent {
            ProxyFlowTheme { ProfilesScreen(profilesState, onAddProxy = {}, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {}) }
        }
        compose.onNodeWithContentDescription("More actions for Office HTTP").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/C3_delete_confirm_light.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun profileActions() {
        compose.setContent {
            ProxyFlowTheme { ProfilesScreen(profilesState, onAddProxy = {}, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {}) }
        }
        compose.onNodeWithContentDescription("More actions for Office HTTP").performClick()
        compose.waitForIdle() // let the menu draw before the screen is captured
        captureScreenRoboImage("$DIR/C2_profile_actions_light.png")
    }

    @Test
    fun addProxyEmpty() = captureBothThemes("D1_add_proxy") { AddEditProxyScreen(AddEditUiState(), AddEditActions()) }

    @Test
    fun editProxyWithSavedPassword() = captureBothThemes("D1_edit_proxy") {
        // What the form shows for a saved profile: its username decrypted, its password never.
        AddEditProxyScreen(
            AddEditUiState(
                isEdit = true,
                input = ProxyProfileInput.from(tokyo, username = "user"),
                hasSavedPassword = true,
                keepsSavedPassword = true,
                originalName = tokyo.displayName,
            ),
            AddEditActions(),
        )
    }

    @Test
    fun addProxyErrors() {
        val input =
            ProxyProfileInput(protocol = ProxyProtocol.HTTP, server = "proxy example", port = "", requiresSignIn = true, username = "a:b")
        // What the form shows after Save: the validator's own errors for this input.
        val errors = ProfileValidator.validate(input)
        captureBothThemes("D3_validation_errors") {
            AddEditProxyScreen(AddEditUiState(input = input, errors = errors, dirty = true), AddEditActions())
        }
    }

    @Test
    fun addProxyPlaintextWarning() = captureBothThemes("D4_plaintext_warning") {
        AddEditProxyScreen(
            AddEditUiState(
                input = ProxyProfileInput(
                    name = "Office HTTP",
                    protocol = ProxyProtocol.HTTP,
                    server = "203.0.113.10",
                    port = "3128",
                    requiresSignIn = true,
                    username = "office",
                    password = "secret",
                ),
                dirty = true,
            ),
            AddEditActions(),
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun unsavedChangesDialog() {
        compose.setContent {
            ProxyFlowTheme {
                AddEditProxyScreen(
                    AddEditUiState(
                        isEdit = true,
                        input = ProxyProfileInput.from(office, username = "office").copy(port = "3129"),
                        // The port changed, so the saved password is not kept (ProxyProfileInput.sameCredentialTargetAs).
                        hasSavedPassword = true,
                        dirty = true,
                        originalName = "Office HTTP",
                    ),
                    AddEditActions(),
                )
            }
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/D8_unsaved_changes_light.png")
    }

    @Test
    fun testing() = captureBothThemes("D5_testing", scrollTo = "Testing the connection…") {
        AddEditProxyScreen(AddEditUiState(input = tokyoForm, dirty = true, test = ConnectionTestState.Testing), AddEditActions())
    }

    @Test
    fun testNotAvailableYet() = captureBothThemes("D5_test_unavailable", scrollTo = "Testing isn't available yet") {
        AddEditProxyScreen(
            AddEditUiState(input = tokyoForm, dirty = true, test = ConnectionTestState.Done(ConnectionTestResult.Unavailable)),
            AddEditActions(),
        )
    }

    @Test
    fun credentialsUnreadable() = captureBothThemes("H3_credentials_unreadable") {
        // What the form shows when the saved username and password can't be decrypted any more.
        AddEditProxyScreen(
            AddEditUiState(
                isEdit = true,
                input = ProxyProfileInput.from(office),
                credentialsUnreadable = true,
                originalName = office.displayName,
            ),
            AddEditActions(),
        )
    }

    @Test
    fun settings() = captureBothThemes("E1_settings") {
        SettingsScreen(SettingsUiState(loading = false, theme = ThemePreference.System), onThemeChange = {}, onOpenAbout = {})
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun themeChooser() {
        compose.setContent {
            ProxyFlowTheme { SettingsScreen(SettingsUiState(loading = false), onThemeChange = {}, onOpenAbout = {}) }
        }
        compose.onNodeWithText("Theme").performClick()
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/E1_theme_dialog_light.png")
    }

    @Test
    fun logsEmpty() = captureBothThemes("F1_logs_empty") { LogsScreen(LogsUiState(loading = false)) }

    @Test
    fun logsWithSampleEntries() = captureBothThemes("F2_logs_sample") {
        // Sample entries: in M2 nothing writes to the log yet (the engine does from M7).
        LogsScreen(
            LogsUiState(
                loading = false,
                entries = listOf(
                    LogEntry(3, SAMPLE_TIME + 120_000, LogEntry.Level.Error, "AUTH_FAILED", "Proxy rejected the username or password"),
                    LogEntry(2, SAMPLE_TIME + 60_000, LogEntry.Level.Warning, "RECONNECTING", "Network changed, reconnecting"),
                    LogEntry(1, SAMPLE_TIME, LogEntry.Level.Info, null, "Connected to Tokyo residential"),
                ),
            ),
        )
    }

    @Test
    fun about() = captureBothThemes("G1_about") { AboutScreen(version = "0.2.0 (2)", onBack = {}) }

    private fun home(connection: ConnectionState, dialog: HomeDialog? = null, diagnostics: RoutingDiagnostics? = null) =
        HomeUiState.Ready(tokyo, connection, canConnect = true, dialog = dialog, diagnostics = diagnostics)

    /** A screen with a dialog open, in the light theme (dialogs draw in their own window, so the whole screen is captured). */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDialog(name: String, content: @Composable () -> Unit) {
        compose.setContent { ProxyFlowTheme { content() } }
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/${name}_light.png")
    }

    /**
     * Renders [content] in the light theme, captures it, switches to dark and captures again.
     * With [scrollTo], the screen is first scrolled until that text is in view.
     * When verifying, a changed image fails at once; both themes are captured before the
     * test fails, so one run reports and records both.
     */
    private fun captureBothThemes(name: String, scrollTo: String? = null, content: @Composable () -> Unit) {
        var dark by mutableStateOf(false)
        compose.setContent { ProxyFlowTheme(darkTheme = dark) { content() } }
        scrollTo?.let { compose.onNodeWithText(it).performScrollTo() }
        val light = runCatching { compose.onRoot().captureRoboImage("$DIR/${name}_light.png") }
        dark = true
        compose.waitForIdle()
        val darkCapture = runCatching { compose.onRoot().captureRoboImage("$DIR/${name}_dark.png") }
        val failures = listOfNotNull(light.exceptionOrNull(), darkCapture.exceptionOrNull())
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n") { it.message.orEmpty() }, failures.first())
    }

    private companion object {
        const val DIR = "screenshots"
        const val SAMPLE_TIME = 1_793_000_000_000L

        /** A session without problems: what Home shows most of the time. */
        val sampleDiagnostics = RoutingDiagnostics(
            connectionsOpened = 128,
            namesForProxy = 46,
            unsupported = UnsupportedTraffic(udpBlocked = 23, udpTopPorts = listOf(443, 3478), pingsDropped = 4),
        )

        /** A session where the proxy's certificate stopped passing, a name couldn't be reached and the proxy hung. */
        val troubleDiagnostics = RoutingDiagnostics(
            connectionsOpened = 37,
            namesForProxy = 12,
            tls = TlsProblems(failures = 3, lastCode = "TLS_UNTRUSTED"),
            dns = DnsProblems(namesNotReached = 1),
            unsupported = UnsupportedTraffic(udpBlocked = 5, udpTopPorts = listOf(443)),
            proxyTimeouts = 2,
            connectionsFailed = 6,
            lastProblem = ConnectionError("TLS_UNTRUSTED", "proxy certificate is not trusted by this phone"),
        )

        val tokyo = ProxyProfile(
            id = "p1",
            name = "Tokyo residential",
            protocol = ProxyProtocol.SOCKS5,
            host = "proxy.example.com",
            port = 1443,
            tlsMode = TlsMode.Tls,
            requiresSignIn = true,
        )
        val office = ProxyProfile(
            id = "p2",
            name = "Office HTTP",
            protocol = ProxyProtocol.HTTP,
            host = "203.0.113.10",
            port = 3128,
            requiresSignIn = true,
        )
        val backup =
            ProxyProfile(id = "p3", name = "Backup SOCKS", protocol = ProxyProtocol.SOCKS5, host = "socks.example.net", port = 1080)
        val profilesState = ProfilesUiState(loading = false, profiles = listOf(tokyo, office, backup), activeProfileId = "p1")
        val tokyoForm = ProxyProfileInput.from(tokyo, username = "user").copy(password = "secret")
    }
}
