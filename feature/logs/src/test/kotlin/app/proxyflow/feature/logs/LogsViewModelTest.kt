package app.proxyflow.feature.logs

import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.testing.FakeLogRepository
import app.proxyflow.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LogsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun emptyLogIsNotLoadingAndHasNoEntries() = runTest {
        val vm = LogsViewModel(FakeLogRepository())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    @Test
    fun newestEntriesComeFirst() = runTest {
        val repository = FakeLogRepository()
        val vm = LogsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }

        repository.state.value = listOf(
            LogEntry(1, timeMillis = 1_000, level = LogEntry.Level.Info, code = null, message = "first"),
            LogEntry(3, timeMillis = 3_000, level = LogEntry.Level.Error, code = "AUTH_FAILED", message = "third"),
            LogEntry(2, timeMillis = 2_000, level = LogEntry.Level.Warning, code = null, message = "second"),
        )

        assertEquals(listOf("third", "second", "first"), vm.uiState.value.entries.map { it.message })
    }
}
