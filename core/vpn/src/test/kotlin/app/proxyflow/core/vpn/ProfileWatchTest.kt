package app.proxyflow.core.vpn

import app.proxyflow.core.model.ConnectionState
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProtocol
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch of the profile a connection runs on (J11, M9): what VpnRuntime tells the session when it is deleted or renamed. */
class ProfileWatchTest {
    private val office = ProxyProfile("office", "Office", ProxyProtocol.SOCKS5, "proxy.example.com", 1080)
    private val home = office.copy(id = "home", name = "Home")
    private val state = MutableStateFlow(ConnectionState())
    private val profiles = MutableStateFlow(listOf(office, home))
    private val calls = CallLog()
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aDeletedProfileEndsOnlyTheConnectionUsingIt() {
        watch()
        assertEquals("read only while a connection exists", 0, profiles.subscriptionCount.value)

        state.value = connectedOn(office)
        eventually("both watches read the profiles") { profiles.subscriptionCount.value == 2 }
        profiles.value = listOf(office.copy(name = "Office 2"))
        eventually("the list read") { "renamed office Office 2" in calls.all }
        assertEquals("another profile's deletion ends nothing", 0, calls.count("deleted"))
        profiles.value = emptyList()
        eventually("the connection's profile deleted") { calls.count("deleted") > 0 }
        assertEquals(listOf("deleted office"), calls.all.filter { it.startsWith("deleted") })

        state.value = ConnectionState()
        eventually("nothing read once the connection ends") { profiles.subscriptionCount.value == 0 }
    }

    @Test
    fun aRenameReachesTheConnectionUsingItAndNothingElseDoes() {
        watch()
        state.value = connectedOn(office)
        eventually("the name it has now") { calls.all == listOf("renamed office Office") }

        profiles.value = listOf(office, home.copy(name = "Elsewhere"))
        profiles.value = listOf(office.copy(updatedAtMillis = 5), home.copy(name = "Elsewhere"))
        profiles.value = listOf(office.copy(name = "Office 2"), home)
        eventually("the new name") { calls.count("renamed") == 2 }
        assertEquals(
            "another profile's rename and a change that keeps the name send nothing",
            listOf("renamed office Office", "renamed office Office 2"),
            calls.all,
        )

        // A switch to another profile watches that one.
        state.value = connectedOn(home)
        profiles.value = listOf(office.copy(name = "Office 3"), home.copy(name = "Home 2"))
        eventually("the switched-to profile's new name") { "renamed home Home 2" in calls.all }
        assertTrue("the earlier profile isn't watched any more", "renamed office Office 3" !in calls.all)
    }

    @Test
    fun aFailureReadingTheProfilesIsLoggedAndNeverEndsTheProcess() {
        watch(flow { throw IllegalStateException("disk") })
        state.value = connectedOn(office)

        val failed = "profiles can't be read (IllegalStateException); trying again"
        eventually("both watches logged it and tried again") { calls.count(failed) >= 4 }
        assertEquals(0, calls.count("deleted") + calls.count("renamed"))
        assertTrue("nothing escaped to the app's scope", job.isActive)
    }

    /** A read that fails once is tried again, so a deletion after Room recovers still ends the connection. */
    @Test
    fun aDeletionAfterAFailedReadStillEndsTheConnection() {
        val reads = AtomicInteger()
        watch(
            flow {
                if (reads.getAndIncrement() == 0) throw IllegalStateException("disk")
                emitAll(profiles)
            },
        )
        state.value = connectedOn(office)
        eventually("read again") { "renamed office Office" in calls.all }
        profiles.value = listOf(home)

        eventually("the connection's profile deleted") { "deleted office" in calls.all }
        assertEquals(1, calls.count("profiles can't be read"))
    }

    @Test
    fun triesAgainAfterASecondDoublingToHalfAMinute() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0L..6L).map(::profileReadRetryDelay))
        assertEquals(30_000L, profileReadRetryDelay(Long.MAX_VALUE))
    }

    private fun watch(from: Flow<List<ProxyProfile>> = profiles) {
        scope.watchConnectionProfile(
            state = state,
            profiles = from,
            deleted = { calls.add("deleted $it") },
            renamed = { id, name -> calls.add("renamed $id $name") },
            log = calls::add,
            retryDelayMillis = { 10 },
        )
    }

    private fun connectedOn(profile: ProxyProfile) =
        ConnectionState(ConnectionStatus.Connected, profile.id, profile.displayName, profile, connectedSinceMillis = 1)

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting until $what: ${calls.all}" }
            Thread.sleep(5)
        }
    }
}
