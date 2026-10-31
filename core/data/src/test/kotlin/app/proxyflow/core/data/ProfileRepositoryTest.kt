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
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /** The Keystore alias of the key [profileId]'s credentials are sealed under now. */
    private suspend fun keyAliasOf(profileId: String) = AesGcmCredentialCipher.aliasFor(profiles.store.get(profileId)!!.credentials!!.keyId)

    private suspend fun password(profileId: String) = String((repo.readCredentials(profileId) as CredentialsRead.Available).password)

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
        assertEquals("k1", sealed.keyId)
        assertEquals(setOf(AesGcmCredentialCipher.aliasFor("k1")), profiles.keys.aliases())

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
        val otherKeys = othersBefore.mapNotNull { it.credentials?.keyId }.map(AesGcmCredentialCipher::aliasFor)
        val keysBefore = otherKeys.associateWith { profiles.keys.get(it) }
        assertEquals(1, keysBefore.size)

        repo.save("p1", office.copy(name = "Office (moved)", server = "198.51.100.7", password = "changed"))
        repo.duplicate("p1", "Office copy")
        repo.delete("p4")

        assertEquals(othersBefore, profiles.store.records.value.filter { it.id != "p1" })
        assertEquals(keysBefore, otherKeys.associateWith { profiles.keys.get(it) })
        assertEquals("backup-pass", password("p3"))
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
    fun changingTheCredentialsMakesTheOldCiphertextUnreadable() = runTest {
        repo.save(null, office.copy(password = "old-pass"))
        val old = profiles.store.get("p1")!!.credentials!!

        repo.save("p1", office.copy(password = "new-pass"))
        val new = profiles.store.get("p1")!!.credentials!!
        assertNotEquals("sealed under a new key", old.keyId, new.keyId)
        assertEquals("only the new key is left", setOf(AesGcmCredentialCipher.aliasFor(new.keyId)), profiles.keys.aliases())
        // A copy of the old ciphertext (say, left in a free database page) can't be decrypted any more.
        assertTrue(runCatching { profiles.cipher.open("p1", old) }.exceptionOrNull() is CredentialUnreadableException)
        assertEquals("new-pass", password("p1"))

        repo.save("p1", office.copy(name = "Renamed", password = ""))
        assertEquals("kept for a rename: no new key", new, profiles.store.get("p1")!!.credentials)
    }

    @Test
    fun aKeyAndroidRejectsIsReplacedWhenTheSignInIsEnteredAgain() = runTest {
        repo.save(null, office)
        val broken = keyAliasOf("p1")
        // An invalidated Keystore key: it is still there, but the cipher refuses it.
        profiles.keys.put(broken, SecretKeySpec(ByteArray(5), "AES"))

        assertEquals(CredentialState.Unreadable, repo.loadForEditing("p1")!!.credentials)
        assertEquals(CredentialsRead.Unreadable, repo.readCredentials("p1"))

        repo.save("p1", office.copy(password = "typed-again"))
        assertEquals("typed-again", password("p1"))
        assertFalse("the rejected key is deleted", broken in profiles.keys.aliases())
        assertEquals(setOf(keyAliasOf("p1")), profiles.keys.aliases())
    }

    @Test
    fun aFailedWriteLeavesTheStoredCredentialsAndNoNewKey() = runTest {
        repo.save(null, office)
        val before = profiles.store.get("p1")!!.credentials
        val failing = object : ProfileStore by profiles.store {
            override suspend fun update(record: ProfileRecord): Boolean = throw IllegalStateException("disk full")
        }
        val repository = DefaultProfileRepository(failing, profiles.cipher, profiles.active, cryptoDispatcher = UnconfinedTestDispatcher())

        assertTrue(runCatching { repository.save("p1", office.copy(password = "new-pass")) }.isFailure)
        assertEquals(before, profiles.store.get("p1")!!.credentials)
        assertEquals("the new key is gone again", setOf(keyAliasOf("p1")), profiles.keys.aliases())
        assertEquals("office-pass", password("p1"))
    }

    @Test
    fun aTemporaryKeystoreFailureNeverCopiesOrDropsCredentials() = runTest {
        repo.save(null, office)
        val busy = object : CredentialCipher by profiles.cipher {
            override fun open(profileId: String, sealed: SealedCredentials): ByteArray =
                throw CredentialUnreadableException(permanent = false, "busy")
        }
        val repository =
            DefaultProfileRepository(profiles.store, busy, profiles.active, newId = {
                "copy"
            }, cryptoDispatcher = UnconfinedTestDispatcher())
        val keysBefore = profiles.keys.aliases()

        assertTrue(runCatching { repository.duplicate("p1", "Office (copy)") }.exceptionOrNull() is CredentialsUnavailableException)
        assertTrue(runCatching { repository.loadForEditing("p1") }.exceptionOrNull() is CredentialsUnavailableException)
        assertEquals(CredentialsRead.TemporarilyUnavailable, repository.readCredentials("p1"))
        // A rename can't tell whether the saved password may be kept: it fails instead of asking for the password.
        assertTrue(
            runCatching {
                repository.save("p1", office.copy(name = "Renamed", password = ""))
            }.exceptionOrNull() is CredentialsUnavailableException,
        )
        assertEquals(listOf("Office"), profiles.store.records.value.map { it.name })
        assertEquals(keysBefore, profiles.keys.aliases())

        // A newly typed password does not need the old one.
        repository.save("p1", office.copy(password = "typed-again"))
        assertEquals("typed-again", password("p1"))
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
    fun aCopyNameIsValidatedLikeATypedOne() = runTest {
        repo.save(null, office)
        val error = runCatching { repo.duplicate("p1", "Office\ncopy") }.exceptionOrNull() as InvalidProfileException
        assertEquals(ProfileError.ControlCharacters, error.errors[ProfileField.Name])
        val tooLong = repo.duplicate("p1", "x".repeat(39) + "\uD83D\uDE00")!!
        assertEquals("cut before the emoji, never inside it", "x".repeat(39), tooLong.name)
        assertEquals(2, profiles.store.records.value.size)
    }

    @Test
    fun lostKeysMakeCredentialsUnreadableNeverEmptyOrWrong() = runTest {
        repo.save(null, office)
        profiles.keys.delete(keyAliasOf("p1"))

        val editing = repo.loadForEditing("p1")!!
        assertEquals(CredentialState.Unreadable, editing.credentials)
        assertEquals("", editing.input.username)
        assertEquals(CredentialsRead.Unreadable, repo.readCredentials("p1"))

        val missingPassword = runCatching { repo.save("p1", office.copy(password = "")) }.exceptionOrNull() as InvalidProfileException
        assertEquals(ProfileError.PasswordRequired, missingPassword.errors[ProfileField.Password])
        repo.save("p1", office.copy(password = "typed-again"))
        assertEquals("typed-again", String((repo.readCredentials("p1") as CredentialsRead.Available).password))

        val copy = repo.duplicate("p1", "copy")!!
        profiles.keys.delete(keyAliasOf("p1"))
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
        assertEquals(
            "twice: reported as temporary, never as unreadable (H3)",
            CredentialsRead.TemporarilyUnavailable,
            repository.readCredentials("p1"),
        )
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
    fun onlyAnExplicitChoiceIsReportedAsChosen() = runTest {
        repo.save(null, office)
        repo.save(null, home)
        assertEquals("p1", repo.activeProfileId.first())
        assertNull("shown as active, but never chosen", repo.chosenProfileId.first())

        repo.setActive("p2")
        assertEquals("p2", repo.chosenProfileId.first())
        repo.delete("p2")
        assertNull("no fallback for a connection started without the user", repo.chosenProfileId.first())
        assertEquals("p1", repo.activeProfileId.first())
    }

    @Test
    fun keysNoCredentialsUseAreDeletedAtStart() = runTest {
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
        val used = AesGcmCredentialCipher.aliasFor(store.get("kept")!!.credentials!!.keyId)
        // Left behind by a delete or a password change interrupted before the old key was deleted.
        AesGcmCredentialCipher(keys).seal("deleted-profile", byteArrayOf(1))
        AesGcmCredentialCipher(keys).seal("kept", byteArrayOf(2))

        assertEquals(2, repository.deleteOrphanedKeys())
        assertEquals(setOf(used), keys.aliases())
        assertEquals(0, repository.deleteOrphanedKeys())
        assertEquals("office-pass", String((repository.readCredentials("kept") as CredentialsRead.Available).password))
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
