package app.proxyflow

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.LayoutDirection
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
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogFilter
import app.proxyflow.core.model.LogRetention
import app.proxyflow.core.model.ProfileTest
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
import app.proxyflow.feature.about.HelpScreen
import app.proxyflow.feature.about.HelpTopics
import app.proxyflow.feature.about.LicenceTextScreen
import app.proxyflow.feature.about.LicencesScreen
import app.proxyflow.feature.about.OpenSourceLibrary
import app.proxyflow.feature.about.OpenSourceLicence
import app.proxyflow.feature.about.OpenSourceNotices
import app.proxyflow.feature.about.PrivacyPolicyScreen
import app.proxyflow.feature.home.HomeActions
import app.proxyflow.feature.home.HomeDialog
import app.proxyflow.feature.home.HomeScreen
import app.proxyflow.feature.home.HomeUiState
import app.proxyflow.feature.logs.LogsScreen
import app.proxyflow.feature.logs.LogsUiState
import app.proxyflow.feature.onboarding.OnboardingScreen
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
import org.junit.Assert.assertEquals
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
 * sees. D6 and D7 show the answers of the engine's test (M9, ADR-0014) for a pass and a
 * failed sign-in; nothing but the engine produces a pass. F2 and F3 show sample entries of the kinds the
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

    /** B8, the profile picker, while disconnected: each profile with its last test, the one Home shows chosen. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun profilePicker() {
        compose.setContent {
            ProxyFlowTheme { HomeScreen(home(ConnectionState.Idle).withProfiles(), HomeActions(), nowMillis = SAMPLE_TIME, picking = true) }
        }
        compose.waitForIdle() // let the sheet draw before the screen is captured
        captureScreenRoboImage("$DIR/B8_profile_picker_light.png")
        assertAccessible("B8_profile_picker_light")
    }

    /** B8 while connected: picking another proxy switches the connection to it (J11), which the sheet says first. */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun profilePickerConnected() {
        val connected = ConnectionState(ConnectionStatus.Connected, "p1", tokyo.name, tokyo, connectedSinceMillis = SAMPLE_TIME)
        compose.setContent {
            ProxyFlowTheme(darkTheme = true) {
                HomeScreen(home(connected).withProfiles(), HomeActions(), nowMillis = SAMPLE_TIME, picking = true)
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("$DIR/B8_profile_picker_connected_dark.png")
        assertAccessible("B8_profile_picker_connected_dark")
    }

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
            killSwitch = KillSwitchStatus.AlwaysOnOnly,
        )
        val alwaysOnOnly = killSwitchOn.copy(blocksWithoutVpn = false)
        HomeScreen(home(ConnectionState.Idle, killSwitch = alwaysOnOnly, interruption = interruption), HomeActions())
    }

    @Test
    fun homeConnecting() = captureBothThemes("B3_home_connecting") {
        HomeScreen(home(ConnectionState(ConnectionStatus.Connecting, "p1", tokyo.name, step = ConnectStep.ProxyHandshake)), HomeActions())
    }

    /** B3 when Android started ProxyFlow as Always-on VPN (M8): the interface is up first and holds apps' traffic. */
    @Test
    fun homeConnectingHeld() = captureBothThemes("B3b_home_connecting_held") {
        val held = ConnectionState(ConnectionStatus.Connecting, "p1", tokyo.name, step = ConnectStep.ProxyHandshake, held = true)
        HomeScreen(home(held, killSwitch = killSwitchOn), HomeActions())
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

    /** M10: reconnecting on a network that asks for a sign-in (a captive portal): Home says so beside the failure. */
    @Test
    fun homeReconnectingOnASignInNetwork() = captureBothThemes("B5_home_reconnecting_sign_in") {
        val retry = ReconnectInfo(
            ReconnectReason.ProxyFailures,
            attempt = 2,
            maxAttempts = 8,
            nextAttemptAtMillis = SAMPLE_TIME + 4_000,
            lastError = ConnectionError("PROXY_UNREACHABLE", "connection to the proxy timed out"),
        )
        val reconnecting = ConnectionState(
            ConnectionStatus.Reconnecting,
            "p1",
            tokyo.name,
            connectedSinceMillis = SAMPLE_TIME - 600_000,
            reconnect = retry,
            networkSignIn = true,
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

    /** C2 as the board: one profile that works, one whose sign-in failed yesterday, one never tested (M9). */
    @Test
    fun profilesList() = captureBothThemes("C2_profiles_list") {
        ProfilesScreen(profilesState, onAddProxy = {}, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {}, nowMillis = SAMPLE_TIME)
    }

    /** A test from C2's menu running: its card says so, and Test can't be started twice. */
    @Test
    fun profileTesting() = captureBothThemes("C2_profile_testing") {
        ProfilesScreen(
            profilesState.copy(testing = setOf("p3")),
            onAddProxy = {},
            onEditProxy = {},
            onUseProxy = {},
            onDeleteProxy = {},
            nowMillis = SAMPLE_TIME,
        )
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun deleteConfirmation() {
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(profilesState, onAddProxy = {
                }, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {}, nowMillis = SAMPLE_TIME)
            }
        }
        compose.onNodeWithContentDescription("More actions for Office HTTP").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/C3_delete_confirm_light.png")
        assertAccessible("C3_delete_confirm_light")
    }

    /** "Use this proxy" while connected through another profile (M9, J11): the switch is asked first. */
    @Test
    fun switchConfirmation() = captureSwitchQuestion("C2_switch_confirm_light", holdsTraffic = true)

    /** The same during a first connect: switching checks the other proxy instead, and the question says so. */
    @Test
    fun switchConfirmationWhileChecking() = captureSwitchQuestion("C2_switch_confirm_checking_light", holdsTraffic = false)

    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureSwitchQuestion(name: String, holdsTraffic: Boolean) {
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(
                    profilesState.copy(connectedProfileId = "p1", connectionHoldsTraffic = holdsTraffic),
                    onAddProxy = {},
                    onEditProxy = {},
                    onUseProxy = {},
                    onDeleteProxy = {},
                    nowMillis = SAMPLE_TIME,
                )
            }
        }
        compose.onNodeWithContentDescription("More actions for Office HTTP").performClick()
        compose.onNodeWithText("Use this proxy").performClick()
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/$name.png")
        assertAccessible(name)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun profileActions() {
        compose.setContent {
            ProxyFlowTheme {
                ProfilesScreen(profilesState, onAddProxy = {
                }, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {}, nowMillis = SAMPLE_TIME)
            }
        }
        compose.onNodeWithContentDescription("More actions for Office HTTP").performClick()
        compose.waitForIdle() // let the menu draw before the screen is captured
        captureScreenRoboImage("$DIR/C2_profile_actions_light.png")
        assertAccessible("C2_profile_actions_light")
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
        assertAccessible("D8_unsaved_changes_light")
    }

    /** D9 (J11): Save on changes to the profile the connection runs on asks before it reconnects with them. */
    @Test
    fun reconnectWithChanges() = captureDialog("D9_reconnect_with_changes") {
        AddEditProxyScreen(
            AddEditUiState(isEdit = true, input = tokyoForm.copy(port = "1444"), dirty = true, confirmReconnect = tokyo.name),
            AddEditActions(),
        )
    }

    /** D9 during a first connect: saving checks the new settings instead. */
    @Test
    fun reconnectWithChangesWhileChecking() = captureDialog("D9_reconnect_checking") {
        AddEditProxyScreen(
            AddEditUiState(
                isEdit = true,
                input = tokyoForm.copy(port = "1444"),
                dirty = true,
                confirmReconnect = tokyo.name,
                confirmReconnectHolds = false,
            ),
            AddEditActions(),
        )
    }

    @Test
    fun testing() = captureBothThemes("D5_testing", scrollTo = "Testing the connection…") {
        AddEditProxyScreen(AddEditUiState(input = tokyoForm, dirty = true, test = ConnectionTestState.Testing), AddEditActions())
    }

    /** D6: the engine opened a test connection through the proxy (EngineConnectionTester); nothing else shows this. */
    @Test
    fun testPassed() = captureBothThemes("D6_test_passed", scrollTo = "Proxy works") {
        AddEditProxyScreen(
            AddEditUiState(input = tokyoForm, dirty = true, test = ConnectionTestState.Done(ConnectionTestResult.Passed(182), tokyo)),
            AddEditActions(),
        )
    }

    /** D7: the proxy rejected the sign-in, in the words Home uses for it (the error catalogue), with its code. */
    @Test
    fun testFailed() = captureBothThemes("D7_test_failed", scrollTo = "Code: AUTH_FAILED · proxy rejected the credentials") {
        val rejected = ConnectionTestResult.Failed(ConnectionError("AUTH_FAILED", "proxy rejected the credentials"), fromEngine = true)
        AddEditProxyScreen(
            AddEditUiState(input = officeForm, dirty = true, test = ConnectionTestState.Done(rejected, office)),
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
        SettingsScreen(
            SettingsUiState(loading = false, theme = ThemePreference.System),
            onThemeChange = {},
            onOpenAbout = {},
            notificationsOn = true,
        )
    }

    /** E1 with Android's kill switch on, as Android said while ProxyFlow was last connected (M8), and notifications off (E1b). */
    @Test
    fun settingsKillSwitchOn() = captureBothThemes("E1b_settings_kill_switch_on") {
        SettingsScreen(
            SettingsUiState(loading = false, killSwitch = killSwitchOn),
            onThemeChange = {},
            onOpenAbout = {},
            notificationsOn = false,
        )
    }

    /** The rows below the kill switches (M9): the log's settings, notifications, theme, help and About. */
    @Test
    fun settingsBelowTheKillSwitches() = captureBothThemes("E1c_settings_logs_app", scrollTo = "About & privacy") {
        SettingsScreen(
            SettingsUiState(loading = false, logRetention = LogRetention.Week),
            onThemeChange = {},
            onOpenAbout = {},
            notificationsOn = false,
        )
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
        assertAccessible("E1_theme_dialog_light")
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
        assertAccessible("F3_log_detail_light")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun clearLogConfirmation() {
        compose.setContent {
            ProxyFlowTheme { LogsScreen(LogsUiState(loading = false, entries = sampleLog, total = sampleLog.size, confirmingClear = true)) }
        }
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/F2_clear_confirm_light.png")
        assertAccessible("F2_clear_confirm_light")
    }

    @Test
    fun about() = captureBothThemes("G1_about") { AboutScreen(version = "0.2.0 (2)", onBack = {}) }

    /** The open-source licences (M9), with a sample of what scripts/licences.py lists from the release build. */
    @Test
    fun licences() = captureBothThemes("G4_licences") { LicencesScreen(sampleNotices, onBack = {}, onOpenLicence = {}) }

    /** One licence's full text, as the app ships it (Go's). */
    @Test
    fun licenceText() = captureBothThemes("G4_licence_text") {
        val go = sampleNotices.licences.first { it.id == "BSD-3-Clause-Go" }
        LicenceTextScreen(title = go.title, text = OpenSourceNotices.text(LocalContext.current, go), onBack = {})
    }

    /** About's links (M9): how it works, the privacy policy, help and the licences. */
    @Test
    fun aboutLinks() = captureBothThemes("G1_about_links", scrollTo = "Open-source licences") {
        AboutScreen(version = "0.2.0 (2)", onBack = {})
    }

    @Test
    fun privacyPolicy() = captureBothThemes("G2_privacy_policy") { PrivacyPolicyScreen(version = "0.2.0 (2)", onBack = {}) }

    @Test
    fun help() = captureBothThemes("G3_help") { HelpScreen(onBack = {}) }

    /** "Learn more" on Home for a certificate the phone doesn't trust opens Help at this topic (M9). */
    @Test
    fun helpCertificates() = captureBothThemes("G3_help_certificates") { HelpScreen(onBack = {}, topic = HelpTopics.CERTIFICATES) }

    /** First launch (M9, docs/m1/03 J1): what ProxyFlow is and what the user needs. */
    @Test
    fun onboardingWelcome() = captureBothThemes("A1_onboarding_welcome") {
        OnboardingScreen(showNotificationsPage = true, onAllowNotifications = {}, onFinish = {}, startPage = 0)
    }

    /** How it works, the limits and the VPN permission, before anything can connect. */
    @Test
    fun onboardingHowItWorks() = captureBothThemes("A2_onboarding_how_it_works") {
        OnboardingScreen(showNotificationsPage = true, onAllowNotifications = {}, onFinish = {}, startPage = 1)
    }

    @Test
    fun onboardingNotifications() = captureBothThemes("A3_onboarding_notifications") {
        OnboardingScreen(showNotificationsPage = true, onAllowNotifications = {}, onFinish = {}, startPage = 2)
    }

    // M9, the brief's task 8: the smallest common phone width (320 dp) at Android's largest font size (200 %), set as
    // Android sets it (the configuration's font scale), so dialogs and sheets, drawn in windows of their own, scale too.
    // Each screen keeps its words and its main action reachable: scrolled to it, which must then be on screen, or shown
    // where it stays (a floating or bottom button, the top bar). Light theme only (both themes are above at 100 %).

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontHomeConnected() = captureLargeFont("B4_home_connected", action = hasText("Disconnect")) {
        val connected = ConnectionState(ConnectionStatus.Connected, "p1", tokyo.name, connectedSinceMillis = SAMPLE_TIME)
        HomeScreen(
            home(connected, diagnostics = sampleDiagnostics, traffic = sampleTraffic),
            HomeActions(),
            nowMillis =
            SAMPLE_TIME + 767_000,
        )
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontHomeDisconnected() = captureLargeFont("B2_home_disconnected", action = hasText("Connect")) {
        HomeScreen(home(ConnectionState.Idle), HomeActions())
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontConnectFailed() = captureLargeFont("B6b_connect_failed", action = hasText("Try again")) {
        HomeScreen(home(ConnectionState(profileId = "p1", error = ConnectionError("PROXY_UNREACHABLE"))), HomeActions())
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontProfilePicker() {
        compose.setContent {
            ProxyFlowTheme { HomeScreen(home(ConnectionState.Idle).withProfiles(), HomeActions(), nowMillis = SAMPLE_TIME, picking = true) }
        }
        compose.onNodeWithText("Manage profiles").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        captureScreenRoboImage("$DIR/large_font/B8_profile_picker.png")
        assertAccessible("B8_profile_picker (200 %)")
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontProfiles() = captureLargeFont("C2_profiles_list", action = hasContentDescription("Add proxy"), scroll = false) {
        ProfilesScreen(profilesState, onAddProxy = {}, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {}, nowMillis = SAMPLE_TIME)
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontAddProxy() = captureLargeFont("D1_add_proxy", action = hasText("Test connection")) {
        AddEditProxyScreen(AddEditUiState(input = tokyoForm, dirty = true), AddEditActions())
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontTestFailed() = captureLargeFont("D7_test_failed", action = hasText("Code: AUTH_FAILED · proxy rejected the credentials")) {
        val rejected = ConnectionTestResult.Failed(ConnectionError("AUTH_FAILED", "proxy rejected the credentials"), fromEngine = true)
        AddEditProxyScreen(
            AddEditUiState(input = officeForm, dirty = true, test = ConnectionTestState.Done(rejected, office)),
            AddEditActions(),
        )
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontSettings() = captureLargeFont("E1_settings", action = hasText("About & privacy")) {
        SettingsScreen(SettingsUiState(loading = false, theme = ThemePreference.System), onThemeChange = {
        }, onOpenAbout = {}, notificationsOn = true)
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontLogs() = captureLargeFont("F2_logs", action = hasContentDescription("Copy log"), scroll = false) {
        LogsScreen(LogsUiState(loading = false, entries = sampleLog, total = sampleLog.size))
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontHowItWorks() = captureLargeFont("A2_onboarding_how_it_works", action = hasText("Continue"), scroll = false) {
        OnboardingScreen(showNotificationsPage = true, onAllowNotifications = {}, onFinish = {}, startPage = 1)
    }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontNotificationsPage() =
        captureLargeFont("A3_onboarding_notifications", action = hasText("Allow notifications"), scroll = false) {
            OnboardingScreen(showNotificationsPage = true, onAllowNotifications = {}, onFinish = {}, startPage = 2)
        }

    @Test
    @Config(qualifiers = SMALL_PHONE, fontScale = LARGEST_FONT)
    fun largeFontPrivacyPolicy() =
        captureLargeFont("G2_privacy_policy", action = hasText("Changes and contact"), scroll = false, lazyList = true) {
            PrivacyPolicyScreen(version = "0.2.0 (2)", onBack = {})
        }

    // M10's localisation review: right to left, as Arabic, Hebrew, Persian and Urdu are written. ProxyFlow declares
    // supportsRtl, so on a phone set to one of them Android mirrors its screens, English words and all (it ships in
    // English only until decision D8). Six screens, light theme, the top of the screen as it opens; the layout must be
    // right to left (Robolectric's ldrtl), and the icons that point a way must turn round with it (defect M10-2).

    @Test
    @Config(qualifiers = "+ldrtl")
    fun rightToLeftHomeDisconnected() = captureRightToLeft("B2_home_disconnected") { HomeScreen(home(ConnectionState.Idle), HomeActions()) }

    @Test
    @Config(qualifiers = "+ldrtl")
    fun rightToLeftHomeConnected() = captureRightToLeft("B4_home_connected") { connectedHome() }

    @Test
    @Config(qualifiers = "+ldrtl")
    fun rightToLeftConnectFailed() = captureRightToLeft("B6b_connect_failed") { connectFailedHome() }

    @Test
    @Config(qualifiers = "+ldrtl")
    fun rightToLeftProfiles() = captureRightToLeft("C2_profiles_list") { profilesList() }

    @Test
    @Config(qualifiers = "+ldrtl")
    fun rightToLeftAddProxy() = captureRightToLeft("D1_add_proxy") { addProxyForm() }

    @Test
    @Config(qualifiers = "+ldrtl")
    fun rightToLeftSettings() = captureRightToLeft("E1_settings") { settingsScreen() }

    @Composable
    private fun connectedHome() {
        val connected = ConnectionState(ConnectionStatus.Connected, "p1", tokyo.name, connectedSinceMillis = SAMPLE_TIME)
        HomeScreen(
            home(connected, diagnostics = sampleDiagnostics, traffic = sampleTraffic),
            HomeActions(),
            nowMillis =
            SAMPLE_TIME + 767_000,
        )
    }

    @Composable
    private fun connectFailedHome() =
        HomeScreen(home(ConnectionState(profileId = "p1", error = ConnectionError("PROXY_UNREACHABLE"))), HomeActions())

    @Composable
    private fun profilesList() =
        ProfilesScreen(profilesState, onAddProxy = {}, onEditProxy = {}, onUseProxy = {}, onDeleteProxy = {}, nowMillis = SAMPLE_TIME)

    @Composable
    private fun addProxyForm() = AddEditProxyScreen(AddEditUiState(input = tokyoForm, dirty = true), AddEditActions())

    @Composable
    private fun settingsScreen() = SettingsScreen(SettingsUiState(loading = false, theme = ThemePreference.System), onThemeChange = {
    }, onOpenAbout = {}, notificationsOn = true)

    /** [content] laid out right to left, as the test's @Config sets — which must be in force — captured in screenshots/rtl and checked. */
    private fun captureRightToLeft(name: String, content: @Composable () -> Unit) {
        var direction: LayoutDirection? = null
        compose.setContent {
            direction = LocalLayoutDirection.current
            ProxyFlowTheme { content() }
        }
        val captured = runCatching { compose.onRoot().captureRoboImage("$DIR/rtl/$name.png") }
        assertEquals("laid out right to left", LayoutDirection.Rtl, direction)
        assertAccessible("$name (right to left)")
        captured.getOrThrow()
    }

    /**
     * [content] in the light theme at the font scale the test's @Config sets, scrolled to its main [action] (unless it
     * stays on screen anyway: [scroll] false; in a lazy list, [lazyList], by the list itself), which must then be
     * displayed; captured and checked.
     */
    private fun captureLargeFont(
        name: String,
        action: SemanticsMatcher,
        scroll: Boolean = true,
        lazyList: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent { ProxyFlowTheme { content() } }
        // Scrolled first, so the image shows what the checks find; captured whatever they find, so it can be looked at.
        val reached = runCatching {
            if (lazyList) compose.onNode(hasScrollAction()).performScrollToNode(action)
            if (scroll) compose.onNode(action).performScrollTo()
        }
        val captured = runCatching { compose.onRoot().captureRoboImage("$DIR/large_font/$name.png") }
        reached.getOrThrow()
        compose.onNode(action).assertIsDisplayed()
        assertAccessible("$name (200 %)")
        captured.getOrThrow()
    }

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

    /** Home's state with the saved profiles and their last tests, for the picker (B8). */
    private fun HomeUiState.Ready.withProfiles() = copy(profiles = profilesState.profiles, lastTests = profilesState.lastTests)

    /** A screen with a dialog open, in the light theme (dialogs draw in their own window, so the whole screen is captured). */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureDialog(name: String, content: @Composable () -> Unit) {
        compose.setContent { ProxyFlowTheme { content() } }
        compose.waitForIdle() // let the dialog draw before the screen is captured
        captureScreenRoboImage("$DIR/${name}_light.png")
        assertAccessible(name)
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
        val accessible = runCatching { assertAccessible(name) }
        dark = true
        compose.waitForIdle()
        val darkCapture = runCatching { compose.onRoot().captureRoboImage("$DIR/${name}_dark.png") }
        val failures = listOfNotNull(light.exceptionOrNull(), accessible.exceptionOrNull(), darkCapture.exceptionOrNull())
        if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n") { it.message.orEmpty() }, failures.first())
    }

    /** Every control on the screen as drawn has a name TalkBack reads and a 48 dp touch target (Accessibility.kt, M9). */
    private fun assertAccessible(name: String) {
        val problems = compose.accessibilityProblems(name)
        if (problems.isNotEmpty()) throw AssertionError("accessibility:\n" + problems.joinToString("\n"))
    }

    private companion object {
        const val DIR = "screenshots"

        /** The smallest common phone width, 320 dp (a 4.7-inch phone), for the 200 % font size tests. */
        const val SMALL_PHONE = "w320dp-h568dp-normal-long-notround-any-xhdpi-keyshidden-nonav"

        /** Android's largest font size (Settings, Display size and text, 200 %). */
        const val LARGEST_FONT = 2f
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
        val profilesState = ProfilesUiState(
            loading = false,
            profiles = listOf(tokyo, office, backup),
            activeProfileId = "p1",
            // As the tester keeps them (ADR-0014): Tokyo passed 2 minutes ago, Office's sign-in failed 20 hours ago.
            lastTests = mapOf(
                "p1" to ProfileTest(SAMPLE_TIME - 2 * 60_000, code = null, latencyMillis = 182),
                "p2" to ProfileTest(SAMPLE_TIME - 20 * 3_600_000, code = "AUTH_FAILED", latencyMillis = null),
            ),
        )
        val tokyoForm = ProxyProfileInput.from(tokyo, username = "user").copy(password = "secret")

        /** Part of what scripts/licences.py lists from a release build: the engine's modules and some of the app's libraries. */
        val sampleNotices = OpenSourceNotices(
            licences = listOf(
                OpenSourceLicence("Apache-2.0", "Apache License 2.0", "licences/Apache-2.0.txt"),
                OpenSourceLicence("BSD-3-Clause-Go", "BSD 3-Clause License (The Go Authors)", "licences/BSD-3-Clause-Go.txt"),
                OpenSourceLicence("gVisor", "gVisor licence (Apache License 2.0; some files MIT or BSD)", "licences/gVisor.txt"),
            ),
            libraries = listOf(
                OpenSourceLibrary("androidx.activity:activity-compose", "1.13.0", "Apache-2.0"),
                OpenSourceLibrary("androidx.room:room-runtime", "2.8.4", "Apache-2.0"),
                OpenSourceLibrary("github.com/google/btree", "v1.1.2", "Apache-2.0"),
                OpenSourceLibrary("org.jetbrains.kotlinx:kotlinx-coroutines-android", "1.10.2", "Apache-2.0"),
                OpenSourceLibrary("Go standard library and runtime", "go1.27.3", "BSD-3-Clause-Go"),
                OpenSourceLibrary("golang.org/x/net", "v0.60.0", "BSD-3-Clause-Go"),
                OpenSourceLibrary("gvisor.dev/gvisor", "v0.0.0-20261029062900-94fcb330700e", "gVisor"),
            ),
        )
        val officeForm = ProxyProfileInput.from(office, username = "office-user").copy(password = "wrong")
    }
}
