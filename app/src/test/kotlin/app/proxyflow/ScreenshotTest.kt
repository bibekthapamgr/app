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
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.DnsProblems
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogFilter
import app.proxyflow.core.model.ProfileValidator
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.ReconnectInfo
import app.proxyflow.core.model.ReconnectReason
import app.proxyflow.core.model.RoutingDiagnostics
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.TlsMode
import app.proxyflow.core.model.TlsProblems
import app.proxyflow.core.model.TrafficStats
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
import app.proxyflow.feature.settings.KillSwitchGuideScreen
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
 * Screenshots of every screen and state built so far (M2, M3, M5 to M8) in light and dark
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
 * exists (M4): not available, never a pass. F2 and F3 show sample entries of the kinds the
 * session writes (ADR-0012).
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

    /** B2 with Android's kill switch on, as Android last said (M8): apps have no internet while ProxyFlow is off. */
    @Test
    fun homeKillSwitchOn() = captureBothThemes("B2_home_kill_switch_on") {
        HomeScreen(home(ConnectionState.Idle, killSwitch = killSwitchOn), HomeActions())
    }

    /** M8: the last process ended while connected (Android freed memory), seen on the next start. */
    @Test
    fun homeInterrupted() = captureBothThemes("B2_home_interrupted") {
        val interruption = Interruption(
            profileId = "p1",
            connectedSinceMillis = SAMPLE_TIME - 3_600_000,
            endedAtMillis = SAMPLE_TIME,
            reason = ExitReason.LowMemory,
            detectedAtMillis = SAMPLE_TIME + 60_000,
        )
        val alwaysOnOnly = killSwitchOn.copy(blocksWithoutVpn = false)
        HomeScreen(home(ConnectionState.Idle, killSwitch = alwaysOnOnly, interruption = interruption), HomeActions())
    }

    @Test
    fun homeConnecting() = captureBothThemes("B3_home_connecting") {
        HomeScreen(home(ConnectionState(ConnectionStatus.Connecting, "p1", tokyo.name, step = ConnectStep.ProxyHandshake)), HomeActions())
    }

    @Test
    fun homeConnected() = captureBothThemes("B4_home_connected") {
        val connected = ConnectionState(ConnectionStatus.Connected, "p1", tokyo.name, connectedSinceMillis = SAMPLE_TIME)
        // 00:12:47 on the timer, as on the design board.
        HomeScreen(
            home(connected, diagnostics = sampleDiagnostics, traffic = sampleTraffic),
            HomeActions(),
            nowMillis =
            SAMPLE_TIME + 767_000,
        )
    }

    /**
     * B4 when things go wrong while connected (ADR-0011): which TLS check failed, names the proxy couldn't reach,
     * proxy timeouts and the last problem, as counts and codes only, never destinations.
     */
    @Test
    fun homeConnectedProblems() = captureBothThemes("B4_home_connected_problems") {
        val connected = ConnectionState(ConnectionStatus.Connected, "p1", tokyo.name, connectedSinceMillis = SAMPLE_TIME)
        HomeScreen(
            home(connected, diagnostics = troubleDiagnostics, traffic = sampleTraffic),
            HomeActions(),
            nowMillis =
            SAMPLE_TIME + 767_000,
        )
    }

    /** B5 (ADR-0012): reconnecting on its own after the proxy stopped answering, attempt 3 of 8 waiting its turn. */
    @Test
    fun homeReconnecting() = captureBothThemes("B5_home_reconnecting") {
        val retry = ReconnectInfo(
            ReconnectReason.ProxyFailures,
            attempt = 3,
            maxAttempts = 8,
            nextAttemptAtMillis = SAMPLE_TIME + 4_000,
            lastError = ConnectionError("PROXY_UNREACHABLE", "connection to the proxy timed out"),
        )
        val reconnecting =
            ConnectionState(
                ConnectionStatus.Reconnecting,
                "p1",
                tokyo.name,
                connectedSinceMillis = SAMPLE_TIME - 600_000,
                reconnect = retry,
            )
        HomeScreen(home(reconnecting), HomeActions(), nowMillis = SAMPLE_TIME)
    }

    /** A switch to another profile or a restart: the interface stays, so traffic is held while the new check runs. */
    @Test
    fun homeSwitching() = captureBothThemes("B5_home_switching") {
        HomeScreen(home(ConnectionState(ConnectionStatus.Reconnecting, "p1", tokyo.name, step = ConnectStep.ProxyHandshake)), HomeActions())
    }

    /** B7: no network, traffic held until one is back. */
    @Test
    fun homeOffline() = captureBothThemes("B7_home_offline") {
        val offline = ConnectionState(
            ConnectionStatus.Offline,
            "p1",
            tokyo.name,
            connectedSinceMillis = SAMPLE_TIME - 600_000,
            error = ConnectionError(ConnectionError.OFFLINE),
        )
        HomeScreen(home(offline), HomeActions())
    }

    /** Reconnecting stopped after its last attempt: traffic stays held until the user tries again or stops. */
    @Test
    fun homeReconnectStopped() = captureBothThemes("B6_home_reconnect_stopped") {
        val error = ConnectionError("PROXY_UNREACHABLE", "reconnecting stopped after 8 attempts")
        HomeScreen(home(ConnectionState(ConnectionStatus.Blocked, "p1", tokyo.name, error = error)), HomeActions())
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

    /** Android started ProxyFlow as Always-on VPN, but no proxy was chosen (M8). */
    @Test
    fun homeAlwaysOnWithoutAProfile() = captureBothThemes("B6b_always_on_no_profile") {
        HomeScreen(home(ConnectionState(error = ConnectionError(ConnectionError.ALWAYS_ON_NO_PROFILE))), HomeActions())
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

    /** E1 with Android's kill switch on, as Android said while ProxyFlow was last connected (M8). */
    @Test
    fun settingsKillSwitchOn() = captureBothThemes("E1b_settings_kill_switch_on") {
        SettingsScreen(SettingsUiState(loading = false, killSwitch = killSwitchOn), onThemeChange = {}, onOpenAbout = {})
    }

    /** E2: how to turn on Android's kill switch, which only the user can (M8). */
    @Test
    fun killSwitchGuide() = captureBothThemes("E2_kill_switch_guide") {
        KillSwitchGuideScreen(SettingsUiState(loading = false), onOpenVpnSettings = {}, onBack = {})
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
    fun logsWithSampleEntries() = captureBothThemes("F2_logs") {
        LogsScreen(LogsUiState(loading = false, entries = sampleLog, total = sampleLog.size))
    }

    @Test
    fun logsFiltered() = captureBothThemes("F2_logs_errors") {
        val errors = sampleLog.filter { LogFilter.Errors.matches(it) }
        LogsScreen(LogsUiState(loading = false, entries = errors, total = sampleLog.size, filter = LogFilter.Errors))
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun logDetail() {
        val failed = sampleLog.first { it.event == LogEvent.ReconnectAttemptFailed }
        compose.setContent {
            ProxyFlowTheme {
                LogsScreen(
                    LogsUiState(
                        loading = false,
                        entries = sampleLog,
                        total = sampleLog.size,
                        selected = failed,
                        profileNames = mapOf("p1" to tokyo.name),
                    ),
                )
            }
        }
        compose.waitForIdle() // let the sheet draw before the screen is captured
        captureScreenRoboImage("$DIR/F3_log_detail_light.png")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun clearLogConfirmation() {
        compose.setContent {
            ProxyFlowTheme { LogsScreen(LogsUiState(loading = false, entries = sampleLog, total = sampleLog.size, confirmingClear = true)) }
        }
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/F2_clear_confirm_light.png")
    }

    @Test
    fun about() = captureBothThemes("G1_about") { AboutScreen(version = "0.2.0 (2)", onBack = {}) }

    private fun home(
        connection: ConnectionState,
        dialog: HomeDialog? = null,
        diagnostics: RoutingDiagnostics? = null,
        traffic: TrafficStats? = null,
        killSwitch: SystemKillSwitch? = null,
        interruption: Interruption? = null,
    ) = HomeUiState.Ready(
        tokyo,
        connection,
        canConnect = true,
        dialog = dialog,
        diagnostics = diagnostics,
        traffic = traffic,
        killSwitch = killSwitch,
        interruption = interruption,
    )

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

        /** Android's kill switch as Android said while ProxyFlow was last connected. */
        val killSwitchOn = SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = SAMPLE_TIME)

        /** What a session of 12 minutes carried. */
        val sampleTraffic = TrafficStats(bytesUp = 4_820_000, bytesDown = 61_300_000, activeConnections = 14, sinceMillis = SAMPLE_TIME)

        /**
         * A session as the session logs it (ADR-0012): connected, the proxy stopped answering, one attempt
         * failed and the next reconnected, then a disconnect and the session's summary. Newest first.
         */
        val sampleLog: List<LogEntry> = run {
            fun entry(
                id: Long,
                afterMillis: Long,
                event: LogEvent,
                stage: ConnectionStage?,
                level: LogEntry.Level = LogEntry.Level.Info,
                code: String? = null,
                details: Map<String, String> = emptyMap(),
            ) = LogEntry(id, SAMPLE_TIME + afterMillis, level, event, stage, code, "p1", details)
            listOf(
                entry(1, 0, LogEvent.ConnectRequested, ConnectionStage.Starting, details = mapOf(LogDetail.PROTOCOL to "SOCKS5")),
                entry(2, 1_380, LogEvent.Connected, ConnectionStage.Connected, details = mapOf(LogDetail.SETUP_MS to "1380")),
                entry(3, 312_000, LogEvent.ReconnectStarted, ConnectionStage.Reconnecting, LogEntry.Level.Warning, "PROXY_UNREACHABLE"),
                entry(
                    4,
                    314_100,
                    LogEvent.ReconnectAttemptFailed,
                    ConnectionStage.Reconnecting,
                    LogEntry.Level.Warning,
                    "PROXY_UNREACHABLE",
                    mapOf(
                        LogDetail.ATTEMPT to "1",
                        LogDetail.MAX_ATTEMPTS to "8",
                        LogDetail.NEXT_TRY_MS to "2000",
                        LogDetail.DETAIL to "connection to the proxy timed out",
                    ),
                ),
                entry(5, 317_600, LogEvent.Reconnected, ConnectionStage.Connected, details = mapOf(LogDetail.ATTEMPT to "2")),
                entry(
                    6,
                    580_000,
                    LogEvent.ConnectionProblems,
                    ConnectionStage.Connected,
                    LogEntry.Level.Warning,
                    "TARGET_UNREACHABLE",
                    mapOf(LogDetail.COUNT to "3"),
                ),
                entry(7, 767_000, LogEvent.Stopped, ConnectionStage.Stopped, details = mapOf(LogDetail.BY to "user")),
                entry(
                    8,
                    767_000,
                    LogEvent.SessionSummary,
                    ConnectionStage.Stopped,
                    details = mapOf(LogDetail.DURATION_MS to "767000", LogDetail.BYTES_UP to "4820000", LogDetail.BYTES_DOWN to "61300000"),
                ),
            ).reversed()
        }

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
