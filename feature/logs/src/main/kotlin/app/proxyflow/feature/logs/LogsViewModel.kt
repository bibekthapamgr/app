package app.proxyflow.feature.logs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.LogRepository
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.LogEntry
import app.proxyflow.core.model.LogFilter
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Boards F1–F3 (ADR-0012).
 *
 * @param entries the entries [filter] and [query] let through, newest first: what the list shows and Export hands over.
 * @param total every entry kept, whatever the filter.
 * @param profileNames the saved profiles' names by id, for the detail sheet; a deleted profile's entries show its short id.
 * @param selected the entry the detail sheet (F3) shows.
 * @param confirmingClear the Clear confirmation is open.
 */
data class LogsUiState(
    val loading: Boolean = true,
    val entries: List<LogEntry> = emptyList(),
    val total: Int = 0,
    val filter: LogFilter = LogFilter.All,
    val query: String = "",
    val profileNames: Map<String, String> = emptyMap(),
    val selected: LogEntry? = null,
    val confirmingClear: Boolean = false,
)

/**
 * The diagnostic log on screen: newest first, filtered (All, Errors, Connection, Blocked) and
 * searched by code, event, stage, detail or profile name. Each time the screen is shown the log's
 * bounds are applied ([onShown]); the log itself never hands over an entry past its retention.
 * Clear removes every entry. Sorting, filtering and searching thousands of entries happen on
 * [work], off the main thread; a log that can't be read shows as empty rather than taking the app down.
 */
class LogsViewModel(private val logs: LogRepository, profiles: ProfileRepository, work: CoroutineContext = Dispatchers.Default) :
    ViewModel() {
    private val filter = MutableStateFlow(LogFilter.All)
    private val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<Long?>(null)
    private val confirmingClear = MutableStateFlow(false)

    /**
     * The search field's text, read by the field itself: typing never waits for the list to be filtered,
     * so no character is dropped and an input method's composition is never interrupted.
     */
    var searchText by mutableStateOf("")
        private set

    private val choices = combine(filter, query, selectedId, confirmingClear) { filter, query, selected, clear ->
        Choices(filter, query, selected, clear)
    }

    val uiState: StateFlow<LogsUiState> =
        combine(
            logs.entries.catch {
                emit(emptyList())
            },
            profiles.profiles.catch { emit(emptyList()) },
            choices,
        ) { entries, profileList, choices ->
            val names = profileList.associate { it.id to it.displayName }
            val newestFirst = entries.sortedWith(compareByDescending<LogEntry> { it.timeMillis }.thenByDescending { it.id })
            LogsUiState(
                loading = false,
                entries = newestFirst.filter { choices.filter.matches(it) && it.matches(choices.query, names) },
                total = entries.size,
                filter = choices.filter,
                query = choices.query,
                profileNames = names,
                // An entry cleared or purged while its sheet is open closes the sheet.
                selected = choices.selectedId?.let { id -> entries.firstOrNull { it.id == id } },
                confirmingClear = choices.confirmingClear,
            )
        }.flowOn(work).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LogsUiState())

    /** The screen is shown (each time, also back from another tab): the log's bounds are applied now. */
    fun onShown() {
        viewModelScope.launch { runCatching { logs.purge() } }
    }

    /** The screen is left: an open detail sheet or Clear confirmation does not come back with it. */
    fun onLeft() {
        selectedId.value = null
        confirmingClear.value = false
    }

    fun setFilter(value: LogFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        val text = value.take(MAX_QUERY_LENGTH)
        searchText = text
        query.value = text
    }

    /** Opens the detail sheet (F3) for [entry]. */
    fun select(entry: LogEntry) {
        selectedId.value = entry.id
    }

    fun closeDetail() {
        selectedId.value = null
    }

    fun requestClear() {
        confirmingClear.value = true
    }

    fun dismissClear() {
        confirmingClear.value = false
    }

    /** Removes every entry kept on the phone; a log that can't be written (the disk full) stays as it is. */
    fun confirmClear() {
        confirmingClear.value = false
        selectedId.value = null
        viewModelScope.launch { runCatching { logs.clear() } }
    }

    private data class Choices(val filter: LogFilter, val query: String, val selectedId: Long?, val confirmingClear: Boolean)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_QUERY_LENGTH = 100
    }
}

/** Whether [query] (any case) is in the entry's event, stage, code, details or profile's name; a blank query matches all. */
internal fun LogEntry.matches(query: String, profileNames: Map<String, String>): Boolean {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return true
    val haystack = buildList {
        add(event.key)
        stage?.let { add(it.key) }
        code?.let { add(it) }
        addAll(details.values)
        profileId?.let { id -> profileNames[id]?.let { add(it) } }
    }
    return haystack.any { it.lowercase(Locale.ROOT).contains(needle) }
}
