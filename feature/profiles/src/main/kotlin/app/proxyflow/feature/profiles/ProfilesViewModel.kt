package app.proxyflow.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.proxyflow.core.data.ConnectionController
import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProfileNames
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A list action that did not complete (storage or Android Keystore failure, a test that couldn't run); the screen says so once. */
enum class ProfileAction { Use, Duplicate, Delete, Test }

/**
 * @param connectedProfileId the profile a connection uses (connecting, connected, reconnecting or
 *   blocked); deleting it disconnects first (J11, "Disconnect and delete").
 * @param lastTests each profile's last connection test, by id (board C2): kept while its server and sign-in stay as
 *   tested; a profile never tested, or changed since, isn't in it.
 * @param testing the profiles a test started from this list runs for now.
 * @param connectionHoldsTraffic a switch now keeps apps' traffic held until the next proxy is checked
 *   (`ConnectionState.holdsTrafficOnSwitch`); during a first connect it only checks the other proxy instead. "Use this
 *   proxy" says which when it asks.
 */
data class ProfilesUiState(
    val loading: Boolean = true,
    val profiles: List<ProxyProfile> = emptyList(),
    val activeProfileId: String? = null,
    val failed: ProfileAction? = null,
    val connectedProfileId: String? = null,
    val lastTests: Map<String, ProfileTest> = emptyMap(),
    val testing: Set<String> = emptySet(),
    val connectionHoldsTraffic: Boolean = false,
)

class ProfilesViewModel(
    private val repository: ProfileRepository,
    private val connection: ConnectionController,
    private val tester: ConnectionTester,
) : ViewModel() {
    private val failed = MutableStateFlow<ProfileAction?>(null)
    private val testing = MutableStateFlow<Set<String>>(emptySet())
    private val tests = combine(repository.lastTests, testing) { kept, running -> Tests(kept, running) }

    val uiState: StateFlow<ProfilesUiState> =
        combine(repository.profiles, repository.activeProfileId, failed, connection.state, tests) { list, active, failure, current, tests ->
            ProfilesUiState(
                loading = false,
                profiles = list,
                activeProfileId = active,
                failed = failure,
                connectedProfileId = current.profileId?.takeIf { current.active },
                lastTests = tests.kept,
                testing = tests.running,
                connectionHoldsTraffic = current.holdsTrafficOnSwitch,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesUiState())

    /**
     * "Use this proxy": [id] becomes the active profile. While a connection runs on another profile it switches to [id],
     * as picking it on Home does (J11), never sending anything around a proxy. The screen asks first while
     * a connection runs on another saved profile, in the words for [ProfilesUiState.connectionHoldsTraffic]. The choice
     * is saved first, so a choice that couldn't be saved switches nothing and the screen says so; then the session is
     * asked to switch, and decides in order with what it was asked before. Both happen even if the screen closes in
     * between.
     */
    fun setActive(id: String) = perform(ProfileAction.Use) {
        withContext(NonCancellable) {
            repository.setActive(id)
            connection.switchIfConnected(id)
        }
    }

    /**
     * Deletes the profile. When a connection uses it, the connection ends first, as the
     * confirmation said: its credentials are never used again, and apps go back to the
     * normal network by the user's choice, never because a profile disappeared under them.
     */
    fun delete(id: String) = perform(ProfileAction.Delete) {
        val current = connection.state.value
        if (current.active && current.profileId == id) connection.disconnect()
        repository.delete(id)
    }

    /**
     * Board C2's Test (J3): tests [id] as saved, as connecting would, without the VPN; the tester keeps the result with
     * the profile, and its card shows it. A test that couldn't run (this phone's own failure: the Keystore, the engine)
     * isn't kept, and the screen says so instead.
     */
    fun test(id: String) {
        if (id in testing.value) return
        failed.value = null
        testing.update { it + id }
        viewModelScope.launch {
            try {
                val result = tester.testSaved(id)
                if (result is ConnectionTestResult.Failed && result.code == ConnectionError.INTERNAL) failed.value = ProfileAction.Test
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.value = ProfileAction.Test
            } finally {
                testing.update { it - id }
            }
        }
    }

    /** The screen has told the user about [ProfilesUiState.failed]. */
    fun onFailureShown() {
        failed.value = null
    }

    /**
     * Copies the profile, its username and password included. [copyName] builds the
     * name from the original's and a copy number ("Office (copy)", "Office (copy 2)").
     */
    fun duplicate(id: String, copyName: (base: String, number: Int) -> String = ProfileNames.englishCopyFormat) =
        perform(ProfileAction.Duplicate) {
            val profiles = repository.profiles.first()
            val original = profiles.firstOrNull { it.id == id } ?: return@perform
            repository.duplicate(id, ProfileNames.copyOf(original.displayName, profiles.map { it.name }, copyName))
        }

    /** Runs [block]; if storage or the Keystore fails, the list stays as it was and [action] is reported. */
    private fun perform(action: ProfileAction, block: suspend () -> Unit) {
        failed.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.value = action
            }
        }
    }

    private data class Tests(val kept: Map<String, ProfileTest>, val running: Set<String>)
}
