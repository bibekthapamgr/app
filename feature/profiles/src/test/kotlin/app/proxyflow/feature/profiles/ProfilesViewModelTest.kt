package app.proxyflow.feature.profiles

import app.proxyflow.core.data.InMemoryProfileRepository
import app.proxyflow.core.model.ProxyProfileInput
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

class ProfilesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private var nextId = 0
    private val repository = InMemoryProfileRepository(newId = { "p${++nextId}" })

    @Test
    fun listsProfilesInSaveOrderAndMarksTheActiveOne() = runTest {
        val vm = ProfilesViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.profiles.isEmpty())

        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))
        repository.save(null, ProxyProfileInput(server = "b.example.com", port = "1080"))

        assertEquals(listOf("a.example.com", "b.example.com"), vm.uiState.value.profiles.map { it.host })
        assertEquals("p1", vm.uiState.value.activeProfileId)
    }

    @Test
    fun useAndDeleteChangeTheList() = runTest {
        val vm = ProfilesViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        repository.save(null, ProxyProfileInput(server = "a.example.com", port = "1080"))
        repository.save(null, ProxyProfileInput(server = "b.example.com", port = "1080"))

        vm.setActive("p2")
        assertEquals("p2", vm.uiState.value.activeProfileId)

        vm.delete("p2")
        assertEquals(listOf("a.example.com"), vm.uiState.value.profiles.map { it.host })
        assertEquals("deleting the active profile activates the next one", "p1", vm.uiState.value.activeProfileId)
    }
}
