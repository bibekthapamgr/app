package app.proxyflow.core.model

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatsTest {
    @Test
    fun bytesReadAsAndroidsSettingsShowThem() {
        val shown = listOf(0L, 999, 1_000, 1_234, 99_949, 99_950, 999_949, 999_950, 34_500_000, 1_200_000_000, 5_000_000_000_000)
            .map { Formats.bytes(it, Locale.US) }
        assertEquals(
            listOf("0 B", "999 B", "1.0 kB", "1.2 kB", "99.9 kB", "100 kB", "1000 kB", "1.0 MB", "34.5 MB", "1.2 GB", "5.0 TB"),
            shown,
        )
        assertEquals("1,2 kB", Formats.bytes(1_234, Locale.GERMANY))
        assertEquals("0 B", Formats.bytes(-5, Locale.US))
    }

    @Test
    fun timesReadAsAClockOrSeconds() {
        assertEquals("00:00:00", Formats.clock(-1))
        assertEquals("01:02:03", Formats.clock(3_723_999))
        assertEquals("27:00:00", Formats.clock(27 * 3_600_000L))
        assertEquals("1.4 s", Formats.seconds(1_449, Locale.US))
        assertEquals("0.0 s", Formats.seconds(-3, Locale.US))
    }

    /** The two catalogue variants are told apart by the engine's own phrases: they must still be the engine's. */
    @Test
    fun theEnginePhrasesTheScreensRecogniseAreTheEngines() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val source = File(root, "engine/adapters/httpconnect.go").readText()
        assertTrue(source.contains("\"${ConnectionError.DETAIL_UNSUPPORTED_SIGN_IN}%s authentication; V1 supports Basic\""))
        assertTrue(source.contains("\"${ConnectionError.DETAIL_NO_CONNECT} (HTTP %d)\""))

        assertTrue(ConnectionError("AUTH_FAILED", "proxy offers only digest authentication; V1 supports Basic").unsupportedSignIn)
        assertFalse(ConnectionError("AUTH_FAILED", "proxy rejected the username or password (HTTP 407)").unsupportedSignIn)
        assertTrue(ConnectionError("PROTOCOL_ERROR", "proxy does not support CONNECT (HTTP 405)").noConnect)
        assertFalse(ConnectionError("PROTOCOL_ERROR", "unexpected HTTP 400 to CONNECT").noConnect)
        assertFalse(ConnectionError("TLS_HANDSHAKE", "proxy does not support CONNECT (HTTP 405)").noConnect)
    }
}
