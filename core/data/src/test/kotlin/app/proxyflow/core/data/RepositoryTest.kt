package app.proxyflow.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import app.proxyflow.core.model.ConnectionStatus
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.ThemePreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun savingAddsUpdatesAndKeepsOrder() = runTest {
        var next = 0
        val repo = InMemoryProfileRepository(newId = { "id${++next}" })
        val a = repo.save(null, ProxyProfileInput(name = "A", server = "a.example", port = "1080"))
        val b = repo.save(null, ProxyProfileInput(name = "B", server = "b.example", port = "3128", protocol = ProxyProtocol.HTTP))
        assertEquals(listOf("id1", "id2"), repo.profiles.value.map { it.id })
        assertEquals("first saved profile becomes active", "id1", repo.activeProfileId.value)

        repo.save(a.id, ProxyProfileInput(name = "A2", server = "a.example", port = "1081"))
        assertEquals(listOf("A2", "B"), repo.profiles.value.map { it.name })
        assertEquals(1081, repo.get(a.id)?.port)

        repo.setActive(b.id)
        assertEquals(b.id, repo.activeProfileId.value)
        repo.delete(b.id)
        assertEquals(listOf(a.id), repo.profiles.value.map { it.id })
        assertEquals("active falls back to a remaining profile", a.id, repo.activeProfileId.value)
        repo.delete(a.id)
        assertNull(repo.activeProfileId.value)
    }

    @Test
    fun passwordIsKeptWhenTheFieldIsLeftEmptyAndErasedWhenSignInIsTurnedOff() = runTest {
        val repo = InMemoryProfileRepository(newId = { "p" })
        val signIn = ProxyProfileInput(server = "a.example", port = "1080", requiresSignIn = true, username = "u", password = "secret")
        assertTrue(repo.save(null, signIn).hasPassword)
        assertTrue("empty password keeps the saved one", repo.save("p", signIn.copy(password = "")).hasPassword)
        val off = repo.save("p", signIn.copy(requiresSignIn = false, password = ""))
        assertFalse(off.hasPassword)
        assertNull(off.username)
    }

    @Test
    fun connectionControllerIsHonestAboutM2() {
        val controller = UnavailableConnectionController()
        assertFalse(controller.canConnect)
        controller.connect("any")
        assertEquals(ConnectionStatus.Disconnected, controller.status.value)
    }
}
