package app.proxyflow.core.model

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecretTextTest {
    @Test
    fun encodesEveryScriptToExactlyTheBytesOfUtf8() {
        for (text in listOf("", "user", "pässwörd", "пароль", "密码", "€uro", "🔑 key 🗝", "a\u0000b")) {
            val bytes = SecretText.utf8(text.toCharArray())
            assertArrayEquals(text, text.toByteArray(Charsets.UTF_8), bytes)
            assertEquals(text, String(SecretText.chars(bytes, 0, bytes.size)))
        }
    }

    @Test
    fun randomTextRoundTrips() {
        val random = Random(4)
        repeat(500) {
            val text = buildString {
                repeat(random.nextInt(0, 40)) {
                    var cp: Int
                    do {
                        cp = random.nextInt(0, 0x110000)
                    } while (cp in 0xD800..0xDFFF)
                    appendCodePoint(cp)
                }
            }
            val bytes = SecretText.utf8(text.toCharArray())
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), bytes)
            assertEquals(text, String(SecretText.chars(bytes, 0, bytes.size)))
            assertEquals(bytes.size, SecretText.utf8Length(text))
        }
    }

    @Test
    fun lengthIsCountedLikeTheEncoder() {
        for (text in listOf("", "user", "pässwörd", "密码", "🔑 key 🗝")) {
            assertEquals(text, text.toByteArray(Charsets.UTF_8).size, SecretText.utf8Length(text))
        }
        assertEquals("an unpaired surrogate counts as U+FFFD", 3, SecretText.utf8Length("\uD83D"))
    }

    @Test
    fun brokenTextIsRejected() {
        val high = '\uD83D'
        val low = '\uDD11'
        for (broken in listOf(charArrayOf(high), charArrayOf(high, 'a'), charArrayOf(low), charArrayOf('a', low, high))) {
            assertThrows(IllegalArgumentException::class.java) { SecretText.utf8(broken) }
        }
    }

    @Test
    fun bytesThatAreNotUtf8AreRejected() {
        val invalid = listOf(
            byteArrayOf(0xC3.toByte()), // truncated sequence
            byteArrayOf(0xFF.toByte()),
            byteArrayOf(0xC0.toByte(), 0x80.toByte()), // overlong NUL
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), // an encoded surrogate
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), // beyond U+10FFFF
        )
        for (bytes in invalid) {
            assertThrows(IllegalArgumentException::class.java) { SecretText.chars(bytes, 0, bytes.size) }
        }
    }

    @Test
    fun decodesOnlyTheGivenRange() {
        val bytes = byteArrayOf(9, 'a'.code.toByte(), 0xC3.toByte(), 0xA9.toByte(), 9)
        assertEquals("aé", String(SecretText.chars(bytes, 1, 3)))
    }
}
