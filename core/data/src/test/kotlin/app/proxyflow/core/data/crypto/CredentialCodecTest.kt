package app.proxyflow.core.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialCodecTest {
    @Test
    fun usernameAndPasswordRoundTripInAnyScript() {
        listOf("user" to "s3cret", "ユーザー" to "pässwörd 🔑", "" to "x", "a".repeat(255) to "b".repeat(1000)).forEach { (user, pass) ->
            val (decodedUser, decodedPass) = CredentialCodec.decode(CredentialCodec.encode(user.toCharArray(), pass.toCharArray()))
            assertEquals(user, String(decodedUser))
            assertEquals(pass, String(decodedPass))
        }
    }

    @Test
    fun theLayoutIsVersionThenTwoLengthPrefixedFields() {
        val bytes = CredentialCodec.encode("ab".toCharArray(), "é".toCharArray())
        assertArrayEquals(byteArrayOf(1, 0, 2, 'a'.code.toByte(), 'b'.code.toByte(), 0, 2, 0xC3.toByte(), 0xA9.toByte()), bytes)
    }

    @Test
    fun anythingElseIsRejected() {
        val good = CredentialCodec.encode("user".toCharArray(), "pw".toCharArray())
        listOf(
            byteArrayOf(),
            byteArrayOf(2) + good.copyOfRange(1, good.size),
            good.copyOf(good.size - 1),
            good + byteArrayOf(0),
        ).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { CredentialCodec.decode(bad) } }
    }
}
