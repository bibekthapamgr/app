package app.proxyflow.core.data.crypto

import app.proxyflow.core.testing.SoftwareSecretKeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0006 evidence: AES-256-GCM under a new key for every set of credentials,
 * the profile id as associated data, tamper detection. Runs on the JVM with
 * software keys; the same code with Android Keystore keys runs in the emulator
 * tests (androidTest).
 */
class CredentialCipherTest {
    private val keys = SoftwareSecretKeyStore()
    private var nextKey = 0
    private val cipher = AesGcmCredentialCipher(keys, newKeyId = { "k${++nextKey}" })
    private val secret = "user:s3cret".encodeToByteArray()

    @Test
    fun sealedCredentialsOpenOnlyForTheirOwnProfile() {
        val sealed = cipher.seal("p1", secret)
        assertArrayEquals(secret, cipher.open("p1", sealed))
        assertEquals(12, sealed.iv.size)
        assertEquals("ciphertext plus a 16-byte tag", secret.size + 16, sealed.ciphertext.size)
        assertFalse("never stored in clear", sealed.ciphertext.containsSlice(secret))

        // The same key and bytes, presented as another profile's: only the associated data differs, and it is refused.
        val moved = assertThrows(CredentialUnreadableException::class.java) { cipher.open("p2", sealed) }
        assertTrue("copied to another profile: permanent", moved.permanent)
        assertArrayEquals("the key itself is fine", secret, cipher.open("p1", sealed))
    }

    @Test
    fun everySealHasItsOwnKeyAndIv() {
        val first = cipher.seal("p1", secret)
        val second = cipher.seal("p1", secret)
        assertEquals("k1", first.keyId)
        assertEquals("k2", second.keyId)
        assertFalse("same plaintext, different IV", first.iv.contentEquals(second.iv))
        assertFalse(first.ciphertext.contentEquals(second.ciphertext))
        assertEquals(setOf("k1", "k2"), cipher.keyIds())
        assertNotEquals(keys.get(AesGcmCredentialCipher.aliasFor("k1")), keys.get(AesGcmCredentialCipher.aliasFor("k2")))
    }

    @Test
    fun anyChangedByteIsDetected() {
        val sealed = cipher.seal("p1", secret)
        for (i in sealed.ciphertext.indices) {
            val changed = sealed.ciphertext.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val e = assertThrows(CredentialUnreadableException::class.java) {
                cipher.open("p1", SealedCredentials(sealed.formatVersion, sealed.keyId, sealed.iv, changed))
            }
            assertTrue(e.permanent)
        }
        val otherIv = sealed.iv.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertThrows(CredentialUnreadableException::class.java) {
            cipher.open("p1", SealedCredentials(1, sealed.keyId, otherIv, sealed.ciphertext))
        }
        assertThrows(CredentialUnreadableException::class.java) {
            cipher.open("p1", SealedCredentials(2, sealed.keyId, sealed.iv, sealed.ciphertext))
        }
    }

    @Test
    fun deletingTheKeyMakesEveryOldCiphertextUnreadable() {
        val sealed = cipher.seal("p1", secret)
        cipher.deleteKey(sealed.keyId)
        assertTrue(cipher.keyIds().isEmpty())
        val e = assertThrows(CredentialUnreadableException::class.java) { cipher.open("p1", sealed) }
        assertTrue(e.permanent)

        val again = cipher.seal("p1", secret)
        assertArrayEquals("new credentials get a new key", secret, cipher.open("p1", again))
        assertThrows(CredentialUnreadableException::class.java) { cipher.open("p1", sealed) }
    }

    @Test
    fun aKeyTheCipherRejectsIsPermanentlyUnreadable() {
        val sealed = cipher.seal("p1", secret)
        // As Android reports an invalidated key: InvalidKeyException when the cipher starts.
        keys.put(AesGcmCredentialCipher.aliasFor(sealed.keyId), SecretKeySpec(ByteArray(5), "AES"))
        val e = assertThrows(CredentialUnreadableException::class.java) { cipher.open("p1", sealed) }
        assertTrue(e.permanent)

        // A key the Keystore can no longer load.
        val unrecoverable = object : SecretKeyStore by keys {
            override fun get(alias: String): SecretKey? = throw UnrecoverableKeyException("key blob damaged")
        }
        assertTrue(
            assertThrows(CredentialUnreadableException::class.java) {
                AesGcmCredentialCipher(unrecoverable).open("p1", sealed)
            }.permanent,
        )
    }

    @Test
    fun aSealThatFailsLeavesNoKeyBehind() {
        val broken = object : SecretKeyStore by keys {
            override fun create(alias: String): SecretKey = SecretKeySpec(ByteArray(5), "AES").also { keys.put(alias, it) }
        }
        assertThrows(Exception::class.java) { AesGcmCredentialCipher(broken).seal("p1", secret) }
        assertTrue(keys.aliases().isEmpty())
    }

    @Test
    fun aKeystoreHiccupIsReportedAsTemporary() {
        val flaky = object : SecretKeyStore by keys {
            override fun get(alias: String): SecretKey? = throw ProviderException("keystore busy")
        }
        val sealed = cipher.seal("p1", secret)
        val e = assertThrows(CredentialUnreadableException::class.java) { AesGcmCredentialCipher(flaky).open("p1", sealed) }
        assertFalse(e.permanent)
    }

    @Test
    fun sealedCredentialsNeverPrintTheirBytes() {
        val text = cipher.seal("p1", secret).toString()
        assertEquals("SealedCredentials(v1, ${secret.size + 16} bytes)", text)
    }

    private fun ByteArray.containsSlice(slice: ByteArray): Boolean =
        (0..size - slice.size).any { start -> slice.indices.all { this[start + it] == slice[it] } }
}
