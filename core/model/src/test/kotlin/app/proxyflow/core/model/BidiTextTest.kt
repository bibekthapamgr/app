package app.proxyflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BidiTextTest {
    /** A first-strong isolate around the text, without directional formatting of its own (M10-94). */
    @Test
    fun theTextIsIsolatedWithoutItsOwnFormatting() {
        assertEquals("\u2068Office\u2069", isolatedText("Office"))
        assertEquals("\u2068Office!\u2069", isolatedText("Office\u2069\u202E!"))
        assertEquals("\u2068a\u200Fb\u2069", isolatedText("a\u200Fb"))
    }

    /** A name of whitespace and invisible format characters alone would show as nothing: the server is shown (M10-123). */
    @Test
    fun aNameThatWouldShowAsNothingShowsTheServer() {
        fun named(name: String) =
            ProxyProfile(id = "p", name = name, protocol = ProxyProtocol.SOCKS5, host = "proxy.example.com", port = 1080)
        assertEquals("proxy.example.com", named("").displayName)
        assertEquals("proxy.example.com", named("  ").displayName)
        assertEquals("proxy.example.com", named("\u2067\u202E \u2069").displayName)
        // Marks, zero-width spaces and joiners, the word joiner and the byte-order mark are invisible too (M10-136).
        assertEquals("proxy.example.com", named("\u200E\u200F\u061C\u200B\u200D\u2060\uFEFF").displayName)
        assertEquals("Office", named("Office").displayName)
        assertEquals("\u202BOffice", named("\u202BOffice").displayName)
    }
}
