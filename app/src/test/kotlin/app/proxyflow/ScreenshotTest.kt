package app.proxyflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.feature.about.AboutScreen
import app.proxyflow.feature.home.HomeScreen
import app.proxyflow.feature.home.HomeUiState
import app.proxyflow.feature.logs.LogsScreen
import app.proxyflow.feature.logs.LogsUiState
import app.proxyflow.feature.profiles.AddEditActions
import app.proxyflow.feature.profiles.AddEditProxyScreen
import app.proxyflow.feature.profiles.AddEditUiState
import app.proxyflow.feature.profiles.ProfilesScreen
import app.proxyflow.feature.profiles.ProfilesUiState
import app.proxyflow.feature.settings.SettingsScreen
import app.proxyflow.feature.settings.SettingsUiState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * Screenshots of every M2 screen in light and dark theme, named after the M1
 * design boards. They are evidence, and with `verifyRoborazziDebug` a visual
 * regression check. Record with `./gradlew :app:recordRoborazziDebug`; a plain
 * `testDebugUnitTest` renders every screen but writes no files.
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
    fun homeEmpty() = captureBothThemes("B1_home_empty") {
        HomeScreen(HomeUiState.NoProfile, onAddProxy = {}, onChangeProfile = {}, onConnect = {})
    }

    @Test
    fun homeWithProfile() = captureBothThemes("B2_home_disconnected") {
        HomeScreen(
            HomeUiState.Ready(tokyo, ConnectionStatus.Disconnected, canConnect = false),
            onAddProxy = {},
            onChangeProfile = {},
            onConnect = {},
        )
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
        captureScreenRoboImage("$DIR/C3_delete_confirm_light.png")
    }

    @Test
    fun addProxyEmpty() = captureBothThemes("D1_add_proxy") { AddEditProxyScreen(AddEditUiState(), AddEditActions()) }

    @Test
    fun addProxyErrors() = captureBothThemes("D3_validation_errors") {
        AddEditProxyScreen(
            AddEditUiState(
                input = ProxyProfileInput(
                    protocol = ProxyProtocol.HTTP,
                    server = "proxy example",
                    port = "",
                    requiresSignIn = true,
                    username = "a:b",
                ),
                errors = mapOf(
                    ProfileField.Server to ProfileError.ServerInvalid,
                    ProfileField.Port to ProfileError.PortOutOfRange,
                    ProfileField.Username to ProfileError.UsernameHasColon,
                ),
                dirty = true,
            ),
            AddEditActions(),
        )
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
                        input = ProxyProfileInput.from(office).copy(port = "3129"),
                        dirty = true,
                        originalName = "Office HTTP",
                    ),
                    AddEditActions(),
                )
            }
        }
        compose.onNodeWithContentDescription("Back").performClick()
        captureScreenRoboImage("$DIR/D8_unsaved_changes_light.png")
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
        captureScreenRoboImage("$DIR/E1_theme_dialog_light.png")
    }

    @Test
    fun logsEmpty() = captureBothThemes("F1_logs_empty") { LogsScreen(LogsUiState(loading = false), onOpenSettings = {}) }

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
            onOpenSettings = {},
        )
    }

    @Test
    fun about() = captureBothThemes("G1_about") { AboutScreen(version = "0.2.0 (2)", onBack = {}) }

    /** Renders [content] in the light theme, captures it, switches to dark and captures again. */
    private fun captureBothThemes(name: String, content: @Composable () -> Unit) {
        var dark by mutableStateOf(false)
        compose.setContent { ProxyFlowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("$DIR/${name}_light.png")
        dark = true
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$DIR/${name}_dark.png")
    }

    private companion object {
        const val DIR = "screenshots"
        const val SAMPLE_TIME = 1_793_000_000_000L

        val tokyo = ProxyProfile(
            id = "p1",
            name = "Tokyo residential",
            protocol = ProxyProtocol.SOCKS5,
            host = "proxy.example.com",
            port = 1443,
            username = "user",
            hasPassword = true,
            useTls = true,
        )
        val office = ProxyProfile(
            id = "p2",
            name = "Office HTTP",
            protocol = ProxyProtocol.HTTP,
            host = "203.0.113.10",
            port = 3128,
            username = "office",
            hasPassword = true,
        )
        val backup =
            ProxyProfile(id = "p3", name = "Backup SOCKS", protocol = ProxyProtocol.SOCKS5, host = "socks.example.net", port = 1080)
        val profilesState = ProfilesUiState(loading = false, profiles = listOf(tokyo, office, backup), activeProfileId = "p1")
    }
}
