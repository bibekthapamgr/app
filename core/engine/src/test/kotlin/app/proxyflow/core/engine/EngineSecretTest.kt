package app.proxyflow.core.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineSecretTest {
    @Test
    fun `encodes the layout the engine parses`() {
        // Version 1, then each field as a 2-byte big-endian length and UTF-8 (adapters.ParseCredentials).
        val secret = EngineSecret.of("ålice".toCharArray(), "pw".toCharArray())
        val expected = byteArrayOf(1, 0, 6) + "ålice".toByteArray(Charsets.UTF_8) + byteArrayOf(0, 2) + "pw".toByteArray()
        assertArrayEquals(expected, secret.take())
    }

    @Test
    fun `an empty username is encoded as no sign-in`() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0), EngineSecret.of(CharArray(0), CharArray(0)).take())
    }

    @Test
    fun `is handed over once and zeroed on close`() {
        val secret = EngineSecret.of("u".toCharArray(), "p".toCharArray())
        val first = secret.take()
        assertEquals(7, first.size)
        assertEquals(0, secret.take().size) // a second use signs in with nothing
        val other = EngineSecret.of("u".toCharArray(), "secret".toCharArray())
        other.close()
        assertEquals(0, other.take().size)
    }

    @Test
    fun `never shows its contents`() {
        val secret = EngineSecret.of("alice".toCharArray(), "hunter2".toCharArray())
        assertFalse(secret.toString().contains("alice"))
        assertFalse(secret.toString().contains("hunter2"))
        assertFalse("$secret".contains("hunter2"))
    }

    @Test
    fun `rejects broken text`() {
        assertThrows(IllegalArgumentException::class.java) { EngineSecret.of(charArrayOf('a', '\uD83D'), CharArray(0)) }
    }
}
