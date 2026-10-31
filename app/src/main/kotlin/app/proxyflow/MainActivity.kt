package app.proxyflow

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import app.proxyflow.core.designsystem.theme.ProxyFlowTheme
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import app.proxyflow.ui.ProxyFlowApp

class MainActivity : ComponentActivity() {
    /** The app's navigation controller, so UI tests can describe the back stack when they fail. */
    @get:VisibleForTesting
    internal var navController: NavHostController? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ProxyFlowApplication).container
        enableEdgeToEdge()
        setContent {
            val settings: UserSettings? by container.settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            // Until the saved theme is read (a few ms) only the window background shows,
            // so a dark-theme user never sees a light frame.
            val theme = settings?.theme ?: return@setContent
            val darkTheme = when (theme) {
                ThemePreference.System -> isSystemInDarkTheme()
                ThemePreference.Light -> false
                ThemePreference.Dark -> true
            }
            // Status- and navigation-bar icons follow the app theme, not only the system setting.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose {}
            }
            val navController = rememberNavController()
            SideEffect { this@MainActivity.navController = navController }
            ProxyFlowTheme(darkTheme = darkTheme) {
                ProxyFlowApp(
                    viewModelFactory = container.viewModelFactory,
                    version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    navController = navController,
                )
            }
        }
    }

    private companion object {
        // The same scrims androidx.activity uses by default for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
