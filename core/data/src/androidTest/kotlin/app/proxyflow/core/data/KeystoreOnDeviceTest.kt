package app.proxyflow.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.crypto.AesGcmCredentialCipher
import app.proxyflow.core.data.crypto.AndroidKeystoreSecretKeyStore
import app.proxyflow.core.data.crypto.CredentialUnreadableException
import app.proxyflow.core.data.db.ProxyFlowDatabase
import app.proxyflow.core.model.CredentialState
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0006 on a real Android runtime (the CI emulator): Android Keystore keys,
 * the real SQLite, the app's own data directory.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "device-${UUID.randomUUID()}.db"
    private val username = "alice-${UUID.randomUUID()}"
    private val password = "pw-${UUID.randomUUID()}"
    private val keyIds = mutableListOf<String>()
    private val opened = mutableListOf<ProxyFlowDatabase>()
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private class MemoryActive : ActiveProfileStore {
        override val activeId = MutableStateFlow<String?>(null)

        override suspend fun set(id: String?) {
            activeId.value = id
        }
    }

    /** A fresh Keystore handle, database object and repository: what a new app process gets. */
    private fun startApp(): DefaultProfileRepository {
        val db = ProxyFlowDatabase.create(context, dbName).also { opened += it }
        return DefaultProfileRepository(
            RoomProfileStore(db.profileDao()),
            AesGcmCredentialCipher(AndroidKeystoreSecretKeyStore()),
            MemoryActive(),
        )
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
        val keys = AndroidKeystoreSecretKeyStore()
        keyIds.forEach { keys.delete(AesGcmCredentialCipher.aliasFor(it)) }
    }

    /** The key [profileId]'s credentials are sealed under now, as stored in the latest database object. */
    private suspend fun keyIdOf(profileId: String): String =
        RoomProfileStore(opened.last().profileDao()).get(profileId)!!.credentials!!.keyId.also { keyIds += it }

    @Test
    fun keysLiveInTheAndroidKeystoreAndCannotBeExported() {
        val id = "test-${UUID.randomUUID()}"
        val cipher = AesGcmCredentialCipher(AndroidKeystoreSecretKeyStore())
        val sealed = cipher.seal(id, "secret".encodeToByteArray())
        keyIds += sealed.keyId

        val alias = AesGcmCredentialCipher.aliasFor(sealed.keyId)
        assertTrue(keyStore.containsAlias(alias))
        assertNull("key material is not extractable", keyStore.getKey(alias, null).encoded)

        // A new Keystore handle (as after an app restart) opens it; presented as another profile's, the same bytes don't.
        assertArrayEquals("secret".encodeToByteArray(), AesGcmCredentialCipher(AndroidKeystoreSecretKeyStore()).open(id, sealed))
        assertTrue(assertThrows(CredentialUnreadableException::class.java) { cipher.open("other-$id", sealed) }.permanent)

        cipher.deleteKey(sealed.keyId)
        assertFalse(keyStore.containsAlias(alias))
        val gone = assertThrows(CredentialUnreadableException::class.java) { cipher.open(id, sealed) }
        assertTrue(gone.permanent)
    }

    @Test
    fun aKeyAndroidRejectsIsReplacedWhenTheSignInIsEnteredAgain() = runTest {
        val repository = startApp()
        val input =
            ProxyProfileInput(server = "proxy.example.com", port = "1080", requiresSignIn = true, username = username, password = password)
        val id = repository.save(null, input).id
        val alias = AesGcmCredentialCipher.aliasFor(keyIdOf(id))
        // Put a key the Keystore refuses for AES-GCM under the same alias, as an invalidated key would be refused.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build(),
            )
            generateKey()
        }

        assertEquals(CredentialsRead.Unreadable, repository.readCredentials(id))
        assertEquals(CredentialState.Unreadable, repository.loadForEditing(id)!!.credentials)

        repository.save(id, input.copy(password = "$password-again"))
        assertEquals("$password-again", String((repository.readCredentials(id) as CredentialsRead.Available).password))
        assertFalse("the refused key is deleted", keyStore.containsAlias(alias))
        assertTrue(keyStore.containsAlias(AesGcmCredentialCipher.aliasFor(keyIdOf(id))))
    }

    @Test
    fun profilesAndCredentialsSurviveARestartAndNoFileHoldsThemInClear() = runTest {
        val input = ProxyProfileInput(
            name = "Office",
            protocol = ProxyProtocol.HTTP,
            server = "203.0.113.10",
            port = "3128",
            requiresSignIn = true,
            username = username,
            password = password,
        )
        val ids = startApp().run {
            listOf(save(null, input).id, save(null, input.copy(name = "Second", username = "$username-2", password = "$password-2")).id)
        }
        ids.forEach { keyIdOf(it) }
        // Every file in the app's private data directory while the database is open (recent writes in its
        // write-ahead log), then after a restart: a new database object and Keystore handle in this process.
        assertNoFileHoldsTheCredentials()
        opened.forEach { it.close() }

        val restarted = startApp()
        assertEquals(listOf("Office", "Second"), restarted.profiles.first().map { it.name })
        val read = restarted.readCredentials(ids[0]) as CredentialsRead.Available
        assertEquals(username, String(read.username))
        assertEquals(password, String(read.password))
        opened.forEach { it.close() }
        assertNoFileHoldsTheCredentials()
    }

    private fun assertNoFileHoldsTheCredentials() {
        val dataDir = File(context.applicationInfo.dataDir)
        val files = dataDir.walkTopDown().filter { it.isFile && it.canRead() }.toList()
        assertTrue(files.any { it.name.startsWith(dbName) })
        assertTrue("the server is found, so the search works", files.any { it.readBytes().contains("203.0.113.10".toByteArray()) })
        for (file in files) {
            val bytes = file.readBytes()
            for (secret in listOf(username, password)) {
                for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                    assertFalse("$secret in ${file.path} ($charset)", bytes.contains(secret.toByteArray(charset)))
                }
            }
        }
    }

    @Test
    fun deletingAProfileDeletesItsKeystoreKey() = runTest {
        val repository = startApp()
        val id = repository.save(
            null,
            ProxyProfileInput(server = "proxy.example.com", port = "1080", requiresSignIn = true, username = username, password = password),
        ).id
        val alias = AesGcmCredentialCipher.aliasFor(keyIdOf(id))
        assertTrue(keyStore.containsAlias(alias))

        repository.delete(id)
        assertFalse(keyStore.containsAlias(alias))
        assertEquals(CredentialsRead.NoSuchProfile, repository.readCredentials(id))
    }

    private fun ByteArray.contains(slice: ByteArray): Boolean {
        if (slice.isEmpty() || slice.size > size) return false
        outer@ for (start in 0..size - slice.size) {
            for (i in slice.indices) if (this[start + i] != slice[i]) continue@outer
            return true
        }
        return false
    }
}
