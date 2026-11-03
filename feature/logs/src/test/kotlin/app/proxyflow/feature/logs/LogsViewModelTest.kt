package app.proxyflow.feature.logs

import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.model.ConnectionStage
import app.proxyflow.core.model.LogDetail
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogEvent
import app.proxyflow.core.model.LogFilter
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.FakeLogRepository
import app.proxyflow.core.testing.MainDispatcherRule
import app.proxyflow.core.testing.TestProfiles
import java.util.Collections
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LogsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val profiles = TestProfiles().repository
    private val repository = FakeLogRepository()

    @Test
    fun anEmptyLogIsNotLoadingAndEachShowingAppliesTheBounds() = runTest {
        val vm = viewModel()
        observe(vm)
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.entries.isEmpty())

        vm.onShown()
        assertEquals("entries past the bounds go when the screen is shown", 1, repository.purges)
        vm.onShown()
        assertEquals("and each time it is shown again (back from another tab)", 2, repository.purges)
    }

    @Test
    fun leavingTheScreenClosesTheDetailSheetAndTheClearConfirmation() = runTest {
        val vm = viewModel()
        observe(vm)
        val chosen = entry(1, 1_000)
        repository.state.value = listOf(chosen)
        vm.select(chosen)
        vm.requestClear()

        vm.onLeft()

        assertNull(vm.uiState.value.selected)
        assertFalse(vm.uiState.value.confirmingClear)
        assertEquals(1, vm.uiState.value.total)
    }

    @Test
    fun theSearchFieldsTextIsTheTypedTextAtOnce() = runTest {
        val vm = viewModel()
        vm.setQuery("auth")
        assertEquals("auth", vm.searchText)
        vm.setQuery("x".repeat(500))
        assertEquals(100, vm.searchText.length)
    }

    @Test
    fun aLogThatCannotBeReadShowsEmptyAndAClearThatFailsChangesNothing() = runTest {
        val broken = object : LogRepository by repository {
            override val entries: Flow<List<LogEntry>> = flow { throw IllegalStateException("disk I/O error") }

            override suspend fun clear() = throw IllegalStateException("database or disk is full")
        }
        val vm = LogsViewModel(broken, profiles, work = EmptyCoroutineContext)
        observe(vm)

        assertFalse(vm.uiState.value.loading)
        assertEquals(0, vm.uiState.value.total)
        vm.requestClear()
        vm.confirmClear()
        assertFalse(vm.uiState.value.confirmingClear)
    }

    /** As the app builds it: the log is read, sorted and filtered on [Dispatchers.Default], never where the screen collects it. */
    @Test
    fun theListIsReadAndFilteredOffTheMainThread() = runTest {
        val threads = Collections.synchronizedSet(mutableSetOf<String>())
        val watched = object : LogRepository by repository {
            override val entries: Flow<List<LogEntry>> = repository.state.onEach { threads += Thread.currentThread().name }
        }
        repository.state.value = listOf(entry(1, 1_000), entry(2, 2_000, level = LogEntry.Level.Error, code = "AUTH_FAILED"))
        val vm = LogsViewModel(watched, profiles)
        observe(vm) // collected here, on the test's thread
        vm.setFilter(LogFilter.Errors)

        val shown = withContext(Dispatchers.Default) {
            withTimeout(5_000) { vm.uiState.first { !it.loading && it.filter == LogFilter.Errors } }
        }

        assertEquals(listOf(2L), shown.entries.map { it.id })
        assertEquals(2, shown.total)
        assertTrue("read on $threads", threads.isNotEmpty() && threads.all { it.startsWith("DefaultDispatcher-worker") })
    }

    @Test
    fun newestEntriesComeFirst() = runTest {
        val vm = viewModel()
        observe(vm)

        repository.state.value = listOf(entry(1, 1_000), entry(3, 3_000, code = "AUTH_FAILED"), entry(2, 2_000))

        assertEquals(listOf(3L, 2L, 1L), vm.uiState.value.entries.map { it.id })
        assertEquals(3, vm.uiState.value.total)
    }

    /** Board F2's filters (docs/m1/03 J10). */
    @Test
    fun filtersShowErrorsConnectionOrBlockedEvents() = runTest {
        val vm = viewModel()
        observe(vm)
        repository.state.value = listOf(
            entry(1, 1_000, LogEvent.ConnectRequested),
            entry(2, 2_000, LogEvent.Connected),
            entry(3, 3_000, LogEvent.ReconnectStarted, LogEntry.Level.Warning, "PROXY_UNREACHABLE"),
            entry(4, 4_000, LogEvent.ReconnectAttemptFailed, LogEntry.Level.Warning, "PROXY_UNREACHABLE"),
            entry(5, 5_000, LogEvent.Blocked, LogEntry.Level.Error, "AUTH_FAILED"),
            entry(6, 6_000, LogEvent.SessionSummary),
            entry(7, 7_000, LogEvent.ConnectionProblems, LogEntry.Level.Warning, "TARGET_UNREACHABLE"),
        )

        fun shown(filter: LogFilter): List<Long> {
            vm.setFilter(filter)
            return vm.uiState.value.entries.map { it.id }.sorted()
        }

        assertEquals((1L..7L).toList(), shown(LogFilter.All))
        assertEquals(listOf(3L, 4L, 5L, 7L), shown(LogFilter.Errors))
        assertEquals(listOf(1L, 2L, 3L, 6L), shown(LogFilter.Connection))
        assertEquals(listOf(3L, 4L, 5L), shown(LogFilter.Blocked))
        assertEquals("the filter never changes what is kept", 7, vm.uiState.value.total)
    }

    @Test
    fun searchFindsCodesEventsStagesDetailsAndProfileNames() = runTest {
        val tokyo = profiles.save(null, ProxyProfileInput(name = "Tokyo residential", server = "proxy.example.com", port = "1080"))
        val vm = viewModel()
        observe(vm)
        repository.state.value = listOf(
            entry(1, 1_000, LogEvent.ConnectFailed, LogEntry.Level.Error, "AUTH_FAILED", stage = ConnectionStage.ProxyHandshake),
            entry(2, 2_000, LogEvent.NetworkChanged, details = mapOf(LogDetail.NETWORK to "wifi")),
            entry(3, 3_000, LogEvent.Connected, profileId = tokyo.id),
        )

        fun found(query: String): List<Long> {
            vm.setQuery(query)
            return vm.uiState.value.entries.map { it.id }.sorted()
        }

        assertEquals(listOf(1L), found("auth"))
        assertEquals(listOf(1L), found("proxy_handshake"))
        assertEquals("any case", listOf(2L), found("WIFI"))
        assertEquals(listOf(2L), found("network_changed"))
        assertEquals(listOf(3L), found("tokyo"))
        assertEquals(emptyList<Long>(), found("nothing like it"))
        assertEquals(listOf(1L, 2L, 3L), found("  "))
    }

    @Test
    fun theDetailSheetShowsTheSelectedEntryUntilItIsGone() = runTest {
        val vm = viewModel()
        observe(vm)
        val chosen = entry(2, 2_000, code = "AUTH_FAILED")
        repository.state.value = listOf(entry(1, 1_000), chosen)

        vm.select(chosen)
        assertEquals(chosen, vm.uiState.value.selected)
        vm.closeDetail()
        assertNull(vm.uiState.value.selected)

        vm.select(chosen)
        repository.state.value = listOf(entry(1, 1_000))
        assertNull("a purged entry's sheet closes", vm.uiState.value.selected)
    }

    @Test
    fun clearAsksFirstThenRemovesEveryEntry() = runTest {
        val vm = viewModel()
        observe(vm)
        repository.state.value = listOf(entry(1, 1_000), entry(2, 2_000))

        vm.requestClear()
        assertTrue(vm.uiState.value.confirmingClear)
        vm.dismissClear()
        assertFalse(vm.uiState.value.confirmingClear)
        assertEquals(2, vm.uiState.value.total)

        vm.requestClear()
        vm.confirmClear()
        assertFalse(vm.uiState.value.confirmingClear)
        assertEquals(0, vm.uiState.value.total)
        assertTrue(vm.uiState.value.entries.isEmpty())
    }

    /** Filtering where the test runs, so each change shows at once. */
    private fun viewModel() = LogsViewModel(repository, profiles, work = EmptyCoroutineContext)

    private fun TestScope.observe(vm: LogsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
    }

    private fun entry(
        id: Long,
        time: Long,
        event: LogEvent = LogEvent.Connected,
        level: LogEntry.Level = LogEntry.Level.Info,
        code: String? = null,
        stage: ConnectionStage? = null,
        profileId: String? = null,
        details: Map<String, String> = emptyMap(),
    ) = LogEntry(
        id = id,
        timeMillis = time,
        level = level,
        event = event,
        stage = stage,
        code = code,
        profileId = profileId,
        details = details,
    )
}
