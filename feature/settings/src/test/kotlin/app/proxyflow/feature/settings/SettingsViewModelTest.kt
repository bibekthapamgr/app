package app.proxyflow.feature.settings

import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.InMemoryProtectionStore
import app.proxyflow.core.data.UnavailableConnectionController
import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogRetention
import app.proxyflow.core.model.SystemKillSwitch
import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import app.proxyflow.core.testing.FakeLogRepository
import app.proxyflow.core.testing.FakeSettingsRepository
import app.proxyflow.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun loadingUntilTheStoredSettingsArrive() {
        val vm = SettingsViewModel(FakeSettingsRepository(), InMemoryProtectionStore(), UnavailableConnectionController())
        assertTrue(vm.uiState.value.loading)
    }

    @Test
    fun showsAndChangesTheStoredTheme() = runTest {
        val repository = FakeSettingsRepository(UserSettings(theme = ThemePreference.Light))
        val vm = SettingsViewModel(repository, InMemoryProtectionStore(), UnavailableConnectionController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertEquals(SettingsUiState(loading = false, theme = ThemePreference.Light), vm.uiState.value)

        vm.setTheme(ThemePreference.Dark)

        assertEquals(ThemePreference.Dark, repository.state.value.theme)
        assertEquals(ThemePreference.Dark, vm.uiState.value.theme)
    }

    @Test
    fun showsTheKillSwitchAsAndroidLastReportedItAndWhetherThatIsNow() = runTest {
        val protection = InMemoryProtectionStore()
        val controller = RecordingController()
        val vm = SettingsViewModel(FakeSettingsRepository(), protection, controller)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertNull("Android never said", vm.uiState.value.killSwitch)
        assertFalse(vm.uiState.value.connected)

        val reported = SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = 1_000)
        protection.recordKillSwitch(reported)
        controller.current.value = ConnectionState(ConnectionStatus.Connected, "p1")

        assertEquals(reported, vm.uiState.value.killSwitch)
        assertTrue("connected: Android was asked as the screen showed", vm.uiState.value.connected)

        controller.current.value = ConnectionState(ConnectionStatus.Reconnecting, "p1")
        assertFalse("only Connected means Android's answer is current", vm.uiState.value.connected)
    }

    @Test
    fun showsAndChangesHowLongTheLogKeepsEntries() = runTest {
        val repository = FakeSettingsRepository(UserSettings(logRetention = LogRetention.Hour))
        val vm = SettingsViewModel(repository, InMemoryProtectionStore(), UnavailableConnectionController())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertEquals(LogRetention.Hour, vm.uiState.value.logRetention)

        vm.setLogRetention(LogRetention.Week)

        assertEquals(LogRetention.Week, repository.state.value.logRetention)
        assertEquals(LogRetention.Week, vm.uiState.value.logRetention)
    }

    @Test
    fun clearingTheLogsEmptiesTheLog() = runTest {
        val logs = FakeLogRepository(listOf(LogEntry(timeMillis = 1, level = LogEntry.Level.Info, event = LogEvent.Connected)))
        val vm = SettingsViewModel(FakeSettingsRepository(), InMemoryProtectionStore(), UnavailableConnectionController(), logs)

        vm.clearLogs()

        assertTrue(logs.state.value.isEmpty())
        assertEquals("a Clear starts a new generation", 1L, logs.generation)
    }

    @Test
    fun checkingTheKillSwitchAsksTheConnection() {
        val controller = RecordingController()
        SettingsViewModel(FakeSettingsRepository(), InMemoryProtectionStore(), controller).checkKillSwitch()
        assertEquals(1, controller.checks)
    }

    private class RecordingController : ConnectionController {
        val current = MutableStateFlow(ConnectionState.Idle)
        override val state: StateFlow<ConnectionState> = current
        override val canConnect: Boolean = true
        var checks = 0

        override fun connect(profileId: String) = Unit

        override fun disconnect() = Unit

        override fun checkSystemKillSwitch() {
            checks++
        }
    }
}
