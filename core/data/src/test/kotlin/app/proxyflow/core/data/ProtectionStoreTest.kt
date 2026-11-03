package app.proxyflow.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.proxyflow.core.model.ExitReason
import app.proxyflow.core.model.Interruption
import app.proxyflow.core.model.KillSwitchStatus
import app.proxyflow.core.model.SystemKillSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What outlives a process about the protection (M8, ADR-0013), in its DataStore file: the kill switch as Android last
 * reported it, the connected marker (until the interruption it leads to is written, in the same write), and an
 * interruption until it is cleared — each read back by a new store on the same file, as the next process does.
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
        assertNull(store.connectedMarker())
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
    fun theMarkerStaysUntilItsInterruptionIsWrittenAndAStopRemovesIt() = runTest(UnconfinedTestDispatcher()) {
        val store = store(backgroundScope)
        val marker = ConnectedMarker(profileId = "p1", sinceMillis = 5_000, pid = 4242, bootCount = 7)
        store.markConnected(marker)
        assertEquals(marker, store.connectedMarker())
        assertEquals("reading leaves it", marker, store.connectedMarker())

        // The interruption it leads to and the marker's removal are one write: neither is ever there without the other.
        val interruption = Interruption("p1", 5_000, 9_000, ExitReason.Killed, 10_000, KillSwitchStatus.AlwaysOnOnly)
        store.interrupted(interruption)
        assertNull(store.connectedMarker())
        assertEquals(interruption, store.interruption.first())

        store.markConnected(marker)
        store.clearConnected()
        assertNull(store.connectedMarker())
        assertEquals("a stop leaves the unseen interruption", interruption, store.interruption.first())

        // A newer connection replaces the older one's marker; a missing boot count stays missing.
        store.markConnected(marker)
        store.markConnected(ConnectedMarker(profileId = null, sinceMillis = 6_000, pid = 1, bootCount = null))
        assertEquals(ConnectedMarker(null, 6_000, 1, null), store.connectedMarker())
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
            killSwitch = KillSwitchStatus.On,
        )
        store.setInterruption(interruption)
        assertEquals(interruption, store.interruption.first())

        // Replaced whole: an undated one has no end time or kill switch left from the one before.
        val undated = interruption.copy(endedAtMillis = null, reason = ExitReason.Unknown, killSwitch = null)
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
        assertEquals(ConnectedMarker("p2", 4_000, 99, 3), reader.connectedMarker())
    }

    /** A file that can't be read reads as nothing known, so Home and Settings never fail on it (writes throw, and are logged). */
    @Test
    fun aFileThatCantBeReadReadsAsNothingKnown() = runTest(UnconfinedTestDispatcher()) {
        file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x01)) // not a preferences file
        val store = DataStoreProtectionStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertNull(store.killSwitch.first())
        assertNull(store.interruption.first())
        assertNull(store.connectedMarker())
    }

    /** The app's store replaces such a file (AppContainer), so the next connection's marker is written again. */
    @Test
    fun aDamagedFileIsReplacedAndWrittenAgain() = runTest(UnconfinedTestDispatcher()) {
        file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x01))
        val store = DataStoreProtectionStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = backgroundScope,
            ) { file },
        )
        assertNull(store.connectedMarker())
        store.markConnected(ConnectedMarker("p1", 1_000, 7, null))
        assertEquals(ConnectedMarker("p1", 1_000, 7, null), store.connectedMarker())
    }

    /**
     * DataStore's flow can leave out a write for a collector that started while the write was on its way; here a
     * DataStore that never tells a collector of a later write: what the store writes reaches its collectors anyway,
     * as Home's does (M8).
     */
    @Test
    fun aWriteReachesCollectorsThatDataStoreLeftOut() = runTest(UnconfinedTestDispatcher()) {
        val store = DataStoreProtectionStore(SnapshotDataStore())
        val interruptions = mutableListOf<Interruption?>()
        val killSwitches = mutableListOf<SystemKillSwitch?>()
        backgroundScope.launch { store.interruption.collect { interruptions += it } }
        backgroundScope.launch { store.killSwitch.collect { killSwitches += it } }

        val interruption = Interruption("p1", 1_000, 2_000, ExitReason.Killed, 3_000, KillSwitchStatus.Off)
        store.interrupted(interruption)
        store.recordKillSwitch(SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = 4_000))
        store.setInterruption(null)
        advanceUntilIdle()

        assertEquals(listOf(null, interruption, null), interruptions)
        assertEquals(listOf(null, SystemKillSwitch(alwaysOn = true, blocksWithoutVpn = true, checkedAtMillis = 4_000)), killSwitches)
    }

    @Test
    fun anUnknownReasonReadsAsUnknown() {
        assertEquals(ExitReason.Unknown, ExitReason.of("from_a_later_version"))
        assertEquals(ExitReason.LowMemory, ExitReason.of("low_memory"))
    }

    /** Gives each collector what it holds when the collector starts, and nothing after. */
    private class SnapshotDataStore : DataStore<Preferences> {
        @Volatile private var held: Preferences = emptyPreferences()

        override val data: Flow<Preferences> = flow {
            emit(held)
            awaitCancellation()
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            held = transform(held)
            return held
        }
    }
}
