package app.proxyflow.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import app.proxyflow.core.model.LogRetention
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

    /** M9 (board E1, J1): how long the log keeps entries, and whether the first-launch pages were gone through. */
    @Test
    fun logRetentionAndOnboardingDefaultAndSurviveANewRepositoryInstance() = runTest(UnconfinedTestDispatcher()) {
        val file = tmp.newFile("settings.preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val repo = DataStoreSettingsRepository(store)

        val first = repo.settings.first()
        assertEquals(LogRetention.Day, first.logRetention)
        assertFalse(first.onboardingCompleted)

        repo.setLogRetention(LogRetention.Week)
        repo.setOnboardingCompleted()

        val again = DataStoreSettingsRepository(store).settings.first()
        assertEquals(LogRetention.Week, again.logRetention)
        assertTrue(again.onboardingCompleted)
        assertEquals("the theme is left alone", ThemePreference.System, again.theme)
    }

    @Test
    fun aRetentionThisVersionDoesntKnowReadsAsTheDefault() = runTest(UnconfinedTestDispatcher()) {
        val file = tmp.newFile("settings.preferences_pb").also { it.delete() }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        store.edit { it[stringPreferencesKey("log_retention")] = "30d" } // as a later version might write it
        assertEquals(LogRetention.Day, DataStoreSettingsRepository(store).settings.first().logRetention)
    }
}
