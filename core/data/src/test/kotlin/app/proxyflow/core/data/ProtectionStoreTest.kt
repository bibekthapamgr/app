package app.proxyflow.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.SystemKillSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What outlives a process about the protection (M8, ADR-0013), in its DataStore file: the kill switch as Android last
 * reported it, the connected marker (taken once), and an interruption until it is cleared — each read back by a new
 * store on the same file, as the next process does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val file by lazy { tmp.newFile("protection.preferences_pb").also { it.delete() } }

    private fun store(scope: CoroutineScope) = DataStoreProtectionStore(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test
    fun nothingIsKnownAtFirst() = runTest(UnconfinedTestDispatcher()) {
        val store = store(backgroundScope)
        assertNull(store.killSwitch.first())
        assertNull(store.interruption.first())
        assertNull(store.takeConnected())
    }

    @Test
    fun theKillSwitchIsKeptWithItsTime() = runTest(UnconfinedTestDispatcher()) {
        val store = store(backgroundScope)
        store.recordKillSwitch(SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = 1_000))
        assertEquals(KillSwitchStatus.On, store.killSwitch.first()?.status)

        store.recordKillSwitch(SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = false, checkedAtMillis = 2_000))
        assertEquals(SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = false, checkedAtMillis = 2_000), store.killSwitch.first())
        assertEquals(KillSwitchStatus.AlwaysOnOnly, store.killSwitch.first()?.status)
    }

    @Test
    fun theMarkerIsTakenOnceAndAStopRemovesIt() = runTest(UnconfinedTestDispatcher()) {
        val store = store(backgroundScope)
        val marker = ConnectedMarker(profileId = "p1", sinceMillis = 5_000, pid = 4242, bootCount = 7)
        store.markConnected(marker)
        assertEquals(marker, store.takeConnected())
        assertNull("taken", store.takeConnected())

        store.markConnected(marker)
        store.clearConnected()
        assertNull(store.takeConnected())

        // A newer connection replaces the older one's marker; a missing boot count stays missing.
        store.markConnected(marker)
        store.markConnected(ConnectedMarker(profileId = null, sinceMillis = 6_000, pid = 1, bootCount = null))
        assertEquals(ConnectedMarker(null, 6_000, 1, null), store.takeConnected())
    }

    @Test
    fun theInterruptionStaysUntilItIsCleared() = runTest(UnconfinedTestDispatcher()) {
        val store = store(backgroundScope)
        val interruption = Interruption(
            profileId = "p1",
            connectedSinceMillis = 1_000,
            endedAtMillis = 9_000,
            reason = ExitReason.LowMemory,
            detectedAtMillis = 10_000,
        )
        store.setInterruption(interruption)
        assertEquals(interruption, store.interruption.first())

        // Replaced whole: an undated one has no end time left from the one before.
        val undated = interruption.copy(endedAtMillis = null, reason = ExitReason.Unknown)
        store.setInterruption(undated)
        assertEquals(undated, store.interruption.first())

        store.setInterruption(null)
        assertNull(store.interruption.first())
    }

    @Test
    fun theNextProcessReadsWhatThisOneLeft() = runTest(UnconfinedTestDispatcher()) {
        // One DataStore per file at a time: the first "process" ends before the next opens the file.
        val firstProcess = Job(backgroundScope.coroutineContext[Job])
        val writer = store(CoroutineScope(backgroundScope.coroutineContext + firstProcess))
        writer.recordKillSwitch(SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = 3_000))
        writer.markConnected(ConnectedMarker("p2", 4_000, 99, 3))
        firstProcess.cancelAndJoin()

        val reader = store(CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])))
        assertEquals(KillSwitchStatus.On, reader.killSwitch.first()?.status)
        assertEquals(ConnectedMarker("p2", 4_000, 99, 3), reader.takeConnected())
    }

    @Test
    fun anUnknownReasonReadsAsUnknown() {
        assertEquals(ExitReason.Unknown, ExitReason.of("from_a_later_version"))
        assertEquals(ExitReason.LowMemory, ExitReason.of("low_memory"))
    }
}
