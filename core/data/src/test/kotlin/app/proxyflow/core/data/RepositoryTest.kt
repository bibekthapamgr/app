package app.proxyflow.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ThemePreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun themeDefaultsToSystemAndSurvivesANewRepositoryInstance() = runTest(UnconfinedTestDispatcher()) {
        val file = tmp.newFile("settings.preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreSettingsRepository(store)

        repo.settings.test {
            assertEquals(ThemePreference.System, awaitItem().theme)
            repo.setTheme(ThemePreference.Dark)
            assertEquals(ThemePreference.Dark, awaitItem().theme)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(ThemePreference.Dark, repo.settings.first().theme)
        assertTrue("written to disk", file.length() > 0)
    }

    @Test
    fun connectionControllerAndTesterAreHonestAboutThisBuild() = runTest {
        val controller = UnavailableConnectionController()
        assertFalse(controller.canConnect)
        controller.connect("any")
        assertEquals(ConnectionStatus.Disconnected, controller.state.value.status)
        assertEquals(null, controller.consentIntent())
        // Test Connection never claims success without an engine to test with (M3 acceptance).
        assertEquals(ConnectionTestResult.Unavailable, UnavailableConnectionTester().test(null, ProxyProfileInput()))
    }
}
