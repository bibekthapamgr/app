package app.proxyflow.core.data

import app.proxyflow.core.data.crypto.AesGcmCredentialCipher
import app.proxyflow.core.data.crypto.CredentialCipher
import app.proxyflow.core.data.crypto.CredentialUnreadableException
import app.proxyflow.core.data.crypto.SealedCredentials
import app.proxyflow.core.model.CredentialState
import app.proxyflow.core.model.ProfileError
import app.proxyflow.core.model.ProfileField
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.TlsMode
import app.proxyflow.core.testing.InMemoryActiveProfileStore
import app.proxyflow.core.testing.InMemoryProfileStore
import app.proxyflow.core.testing.SoftwareSecretKeyStore
import app.proxyflow.core.testing.TestProfiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The profile rules shared by every store: validation, encryption, edits, copies, deletes, the active profile. */
class ProfileRepositoryTest {
    private val profiles = TestProfiles()
    private val repo = profiles.repository

    private val office = ProxyProfileInput(
        name = "Office",
        protocol = ProxyProtocol.HTTP,
        server = "203.0.113.10",
        port = "3128",
        requiresSignIn = true,
        username = "office-user",
        password = "office-pass",
    )
    private val home = ProxyProfileInput(name = "Home", server = "socks.example.net", port = "1080")

    @Test
    fun invalidFormsAreNeverSaved() = runTest {
        val broken = listOf(
            office.copy(server = "bad host"),
            office.copy(port = "70000"),
            office.copy(username = ""),
            office.copy(password = ""),
            office.copy(password = "line\nbreak"),
            office.copy(useTls = true, tlsServerName = "not a name"),
        )
        broken.forEach { input ->
            try {
                repo.save(null, input)
                fail("saved $input")
            } catch (e: InvalidProfileException) {
                assertTrue(e.errors.isNotEmpty())
            }
        }
        assertTrue(profiles.store.records.value.isEmpty())
        assertTrue("no key is made for a refused form", profiles.keys.aliases().isEmpty())
    }

    @Test
    fun savingStoresTheSettingsAndOnlyCiphertextForTheCredentials() = runTest {
        val saved = repo.save(null, office.copy(useTls = true, tlsServerName = "tls.example.com"))
        assertEquals("p1", saved.id)
        assertEquals(TlsMode.Tls, saved.tlsMode)
        assertEquals("tls.example.com", saved.tlsServerName)
        assertTrue(saved.requiresSignIn)
        assertEquals(1_000L, saved.createdAtMillis)
        assertEquals(1_000L, saved.updatedAtMillis)

        val record = profiles.store.get("p1")!!
        val sealed = record.credentials!!
        val stored = sealed.ciphertext.decodeToString(throwOnInvalidSequence = false)
        assertFalse("office-user" in stored)
        assertFalse("office-pass" in stored)
        assertEquals(setOf(AesGcmCredentialCipher.aliasFor("p1")), profiles.keys.aliases())

        val read = repo.readCredentials("p1") as CredentialsRead.Available
        assertEquals("office-user", String(read.username))
        assertEquals("office-pass", String(read.password))
        read.wipe()
        assertTrue(read.password.all { it == '\u0000' })
    }

    @Test
    fun theEditFormGetsTheUsernameButNeverThePassword() = runTest {
        repo.save(null, office)
        val editing = repo.loadForEditing("p1")!!
        assertEquals(CredentialState.Saved, editing.credentials)
        assertEquals("office-user", editing.input.username)
        assertEquals("", editing.input.password)

        repo.save(null, home)
        assertEquals(CredentialState.NotRequired, repo.loadForEditing("p2")!!.credentials)
        assertNull(repo.loadForEditing("gone"))
    }

    @Test
    fun anEmptyPasswordKeepsTheSavedOneOnlyForTheSameProxyAccount() = runTest {
        repo.save(null, office)
        val before = profiles.store.get("p1")!!.credentials

        repo.save("p1", office.copy(name = "Office 2", password = ""))
        assertEquals("renamed: same ciphertext", before, profiles.store.get("p1")!!.credentials)
        assertEquals("office-pass", String((repo.readCredentials("p1") as CredentialsRead.Available).password))

        listOf(office.copy(port = "3129"), office.copy(username = "someone"), office.copy(protocol = ProxyProtocol.SOCKS5)).forEach {
            val error = runCatching { repo.save("p1", it.copy(password = "")) }.exceptionOrNull() as InvalidProfileException
            assertEquals(ProfileError.PasswordRequired, error.errors[ProfileField.Password])
        }
        repo.save("p1", office.copy(port = "3129", password = "new-pass"))
        assertEquals("new-pass", String((repo.readCredentials("p1") as CredentialsRead.Available).password))
    }

    @Test
    fun editingOneProfileLeavesTheOthersUntouched() = runTest {
        repo.save(null, office)
        repo.save(null, home)
        repo.save(null, office.copy(name = "Backup", username = "backup-user", password = "backup-pass"))
        val othersBefore = profiles.store.records.value.filter { it.id != "p1" }
        val keysBefore = listOf("p2", "p3").associateWith { profiles.keys.get(AesGcmCredentialCipher.aliasFor(it)) }

        repo.save("p1", office.copy(name = "Office (moved)", server = "198.51.100.7", password = "changed"))
        repo.duplicate("p1", "Office copy")
        repo.delete("p4")

        assertEquals(othersBefore, profiles.store.records.value.filter { it.id != "p1" })
        assertEquals(keysBefore, listOf("p2", "p3").associateWith { profiles.keys.get(AesGcmCredentialCipher.aliasFor(it)) })
        assertEquals("backup-pass", String((repo.readCredentials("p3") as CredentialsRead.Available).password))
    }

    @Test
    fun deletingAProfileDeletesItsCredentialsAndItsKey() = runTest {
        repo.save(null, office)
        val sealed = profiles.store.get("p1")!!.credentials!!
        repo.delete("p1")

        assertNull(profiles.store.get("p1"))
        assertTrue(profiles.keys.aliases().isEmpty())
        // Even a copy of the old ciphertext (say, left in the database file) can no longer be decrypted.
        val leftover = runCatching { AesGcmCredentialCipher(profiles.keys).open("p1", sealed) }.exceptionOrNull()
        assertTrue(leftover is CredentialUnreadableException)
        assertEquals(CredentialsRead.NoSuchProfile, repo.readCredentials("p1"))
    }

    @Test
    fun turningSignInOffDeletesTheKey() = runTest {
        repo.save(null, office)
        val off = repo.save("p1", office.copy(requiresSignIn = false, username = "", password = ""))
        assertFalse(off.requiresSignIn)
        assertNull(profiles.store.get("p1")!!.credentials)
        assertTrue(profiles.keys.aliases().isEmpty())
        assertEquals(CredentialsRead.NotRequired, repo.readCredentials("p1"))
    }

    @Test
    fun aDuplicateHasItsOwnIdKeyAndCiphertext() = runTest {
        repo.save(null, office)
        val copy = repo.duplicate("p1", "Office (copy)")!!
        assertEquals("p2", copy.id)
        assertEquals("Office (copy)", copy.name)
        assertEquals(listOf("p1", "p2"), repo.profiles.first().map { it.id })

        val original = profiles.store.get("p1")!!.credentials!!
        val duplicate = profiles.store.get("p2")!!.credentials!!
        assertFalse(original.ciphertext.contentEquals(duplicate.ciphertext))
        assertEquals(2, profiles.keys.aliases().size)
        assertEquals("office-pass", String((repo.readCredentials("p2") as CredentialsRead.Available).password))

        repo.delete("p1")
        assertEquals("the copy does not depend on the original's key", "office-user", repo.loadForEditing("p2")!!.input.username)
        assertNull(repo.duplicate("p1", "x"))
    }

    @Test
    fun lostKeysMakeCredentialsUnreadableNeverEmptyOrWrong() = runTest {
        repo.save(null, office)
        profiles.keys.delete(AesGcmCredentialCipher.aliasFor("p1"))

        val editing = repo.loadForEditing("p1")!!
        assertEquals(CredentialState.Unreadable, editing.credentials)
        assertEquals("", editing.input.username)
        assertEquals(CredentialsRead.Unreadable, repo.readCredentials("p1"))

        val missingPassword = runCatching { repo.save("p1", office.copy(password = "")) }.exceptionOrNull() as InvalidProfileException
        assertEquals(ProfileError.PasswordRequired, missingPassword.errors[ProfileField.Password])
        repo.save("p1", office.copy(password = "typed-again"))
        assertEquals("typed-again", String((repo.readCredentials("p1") as CredentialsRead.Available).password))

        val copy = repo.duplicate("p1", "copy")!!
        profiles.keys.delete(AesGcmCredentialCipher.aliasFor("p1"))
        val copyOfUnreadable = repo.duplicate("p1", "copy 2")!!
        assertTrue(copyOfUnreadable.requiresSignIn)
        assertEquals(CredentialState.Unreadable, repo.loadForEditing(copyOfUnreadable.id)!!.credentials)
        assertEquals(CredentialState.Saved, repo.loadForEditing(copy.id)!!.credentials)
    }

    @Test
    fun aKeystoreHiccupIsTriedOnceMore() = runTest {
        repo.save(null, office)
        var failures = 1
        val flaky = object : CredentialCipher by AesGcmCredentialCipher(profiles.keys) {
            override fun open(profileId: String, sealed: SealedCredentials): ByteArray {
                if (failures-- > 0) throw CredentialUnreadableException(permanent = false, "busy")
                return AesGcmCredentialCipher(profiles.keys).open(profileId, sealed)
            }
        }
        val repository = DefaultProfileRepository(profiles.store, flaky, profiles.active, cryptoDispatcher = UnconfinedTestDispatcher())
        assertTrue(repository.readCredentials("p1") is CredentialsRead.Available)

        failures = 2
        assertEquals("then gives up: H3", CredentialsRead.Unreadable, repository.readCredentials("p1"))
    }

    @Test
    fun theActiveProfileIsTheChosenOneOrTheOldest() = runTest {
        assertNull(repo.activeProfileId.first())
        repo.save(null, office)
        repo.save(null, home)
        assertEquals("p1", repo.activeProfileId.first())

        repo.setActive("p2")
        assertEquals("p2", repo.activeProfileId.first())
        repo.setActive("gone")
        assertEquals("p2", repo.activeProfileId.first())

        repo.delete("p2")
        assertEquals("p1", repo.activeProfileId.first())
        assertNull("the stored choice is cleared", profiles.active.state.value)
        repo.delete("p1")
        assertNull(repo.activeProfileId.first())
    }

    @Test
    fun keysWithoutAProfileAreDeletedAtStart() = runTest {
        val keys = SoftwareSecretKeyStore()
        val store = InMemoryProfileStore()
        val repository = DefaultProfileRepository(
            store,
            AesGcmCredentialCipher(keys),
            InMemoryActiveProfileStore(),
            newId = { "kept" },
            cryptoDispatcher = UnconfinedTestDispatcher(),
        )
        repository.save(null, office)
        AesGcmCredentialCipher(keys).seal("deleted-profile", byteArrayOf(1))

        assertEquals(1, repository.deleteOrphanedKeys())
        assertEquals(setOf(AesGcmCredentialCipher.aliasFor("kept")), keys.aliases())
        assertEquals(0, repository.deleteOrphanedKeys())
    }

    @Test
    fun timestampsRecordCreationAndTheLastEdit() = runTest {
        val created = repo.save(null, home)
        val edited = repo.save("p1", home.copy(name = "Home 2"))
        assertEquals(created.createdAtMillis, edited.createdAtMillis)
        assertTrue(edited.updatedAtMillis > created.updatedAtMillis)
    }

    @Test
    fun savingUnderAnIdThatIsGoneCreatesANewProfile() = runTest {
        val saved = repo.save("deleted-meanwhile", home)
        assertEquals("p1", saved.id)
        assertEquals(listOf("p1"), repo.profiles.first().map { it.id })
    }
}
