package app.proxyflow.feature.settings

import app.proxyflow.core.model.ThemePreference
import app.proxyflow.core.model.UserSettings
import app.proxyflow.core.testing.FakeSettingsRepository
import app.proxyflow.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun loadingUntilTheStoredSettingsArrive() {
        val vm = SettingsViewModel(FakeSettingsRepository())
        assertTrue(vm.uiState.value.loading)
    }

    @Test
    fun showsAndChangesTheStoredTheme() = runTest {
        val repository = FakeSettingsRepository(UserSettings(theme = ThemePreference.Light))
        val vm = SettingsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertEquals(SettingsUiState(loading = false, theme = ThemePreference.Light), vm.uiState.value)

        vm.setTheme(ThemePreference.Dark)

        assertEquals(ThemePreference.Dark, repository.state.value.theme)
        assertEquals(ThemePreference.Dark, vm.uiState.value.theme)
    }
}
