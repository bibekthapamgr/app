package app.proxyflow

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * What the app may and may not claim (docs/m1/02-traffic-support-matrix.md §5,
 * docs/m6/routing-and-limitations.md): every user-facing string of every module, in every
 * language, is checked for claims of universal traffic coverage, anonymity or blanket
 * security, and the limitations the user must be told are checked to be there.
 * A claim that is true only in a negated form ("doesn't make you anonymous") is allowed
 * only in the strings listed in [NEGATED].
 */
class CopyClaimsTest {
    private val strings: Map<String, List<Pair<String, String>>> = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter {
                it.name == "strings.xml" && it.parentFile.name.startsWith("values") && "/src/main/res/" in it.invariantSeparatorsPath
            }
            .associate { file -> file.relativeTo(root).invariantSeparatorsPath to read(file) }
    }

    private fun read(file: File): List<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Pair<String, String>>()
        val nodes = document.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as? Element ?: continue
            val name = element.getAttribute("name")
            when (element.tagName) {
                "string" -> out += name to element.textContent
                "plurals", "string-array" -> {
                    val items = element.getElementsByTagName("item")
                    for (j in 0 until items.length) out += name to items.item(j).textContent
                }
            }
        }
        return out.map { (name, text) -> name to normalized(text.replace("\\'", "'").replace("\\\"", "\"")) }
    }

    private val all get() = strings.flatMap { (file, list) -> list.map { Triple(file, it.first, it.second) } }

    @Test
    fun everyModuleWithStringsIsRead() {
        val modules = strings.keys.map { it.substringBefore("/src/") }.toSet()
        assertTrue("found $modules", modules.containsAll(listOf("app", "feature/home", "feature/profiles", "feature/about", "core/vpn")))
    }

    @Test
    fun noStringClaimsUniversalCoverageAnonymityOrBlanketSecurity() {
        val offending = all.flatMap { (file, name, text) ->
            FORBIDDEN.filter { (_, pattern) -> pattern.containsMatchIn(text) }
                .filterNot { (claim, _) -> NEGATED[name] == claim }
                .map { (claim, _) -> "$file: $name says \"$text\" ($claim)" }
        }
        assertEquals("forbidden claims:\n" + offending.joinToString("\n"), emptyList<String>(), offending)
    }

    @Test
    fun negatedClaimsStayNegated() {
        for ((name, claim) in NEGATED) {
            val texts = all.filter { it.second == name }.map { it.third }
            assertTrue("$name ($claim) is missing", texts.isNotEmpty())
            texts.forEach { assertTrue("$name must deny it: \"$it\"", NEGATION.containsMatchIn(it)) }
        }
    }

    /** TLS to the proxy is never called plain "encrypted": that reads as if sites' HTTPS depended on it (ADR-0011). */
    @Test
    fun tlsToTheProxyIsNeverJustEncryption() {
        val vague = all.filter { (_, _, text) -> text.trim().matches(Regex("(?i)(not )?encrypted|encrypt connection.*|tls")) }
        assertEquals("ambiguous TLS wording: $vague", emptyList<Any>(), vague)
    }

    /** What the user must be told, where they read it (docs/m6/routing-and-limitations.md §5). */
    @Test
    fun theLimitationsAreStated() {
        fun text(name: String) = all.firstOrNull { it.second == name }?.third ?: throw AssertionError("$name is missing")
        val required = mapOf(
            "about_summary" to listOf("anonymous", "proxy provider sees"),
            "about_carry_blocked" to listOf("UDP is blocked", "ping"),
            "about_carry_ipv6" to listOf("IPv6"),
            "about_carry_outside" to listOf("don't use the VPN", "work profile", "another user", "tethered"),
            "about_carry_tls" to listOf("HTTPS", "end to end", "never decrypts"),
            "home_https_note" to listOf("HTTPS", "end to end", "never decrypts"),
            "home_connected_note" to listOf("TCP"),
            "home_diag_unsupported_note" to listOf("TCP only", "UDP is refused", "pings get no reply"),
            "edit_plaintext_body" to listOf("HTTPS pages stay encrypted end to end"),
            "edit_tls_hint" to listOf("HTTPS sites stay encrypted end to end"),
            "vpn_notification_connected_text" to listOf("TCP", "UDP and ping blocked"),
        )
        val missing = required.flatMap { (name, needles) -> needles.filterNot { it in text(name) }.map { "$name lacks \"$it\"" } }
        assertEquals(missing.joinToString("\n"), emptyList<String>(), missing)
    }

    /** Every way of saying one of the claims ProxyFlow must not make is caught, whatever spaces or apostrophes it uses. */
    @Test
    fun forbiddenWordingsAreRecognised() {
        val claims = listOf(
            "ProxyFlow hides your IP address",
            "Hiding your real location",
            "Your traffic is protected",
            "It protects your privacy",
            "Every application goes through your proxy",
            "All of your device traffic is tunnelled",
            "Covers all traffic",
            "Untrackable browsing",
            "Untraceable and incognito",
            "Secure every connection",
            "Works with games and video calls",
            "A system-wide VPN",
            "100\u00a0% private",
            "all\u00a0traffic",
            "Full VPN protection",
            "Military-grade encryption",
            "Encrypts all your traffic",
            "No-logs policy",
        )
        val missed = claims.filter { claim -> FORBIDDEN.none { (_, pattern) -> pattern.containsMatchIn(normalized(claim)) } }
        assertEquals("not recognised: $missed", emptyList<String>(), missed)
    }

    private companion object {
        /** No-break and other special spaces read as spaces, typographic apostrophes as plain ones. */
        fun normalized(text: String): String = text.replace(Regex("[\\u00a0\\u2007\\u202f\\u2009]"), " ").replace('\u2019', '\'')

        /** Claims ProxyFlow must never make (traffic matrix §5 "Must not say"). */
        val FORBIDDEN: List<Pair<String, Regex>> = listOf(
            "all traffic" to
                Regex(
                    "(?i)\\ball\\s+(of\\s+)?(your\\s+|the\\s+|this\\s+)?" +
                        "(device's\\s+|device\\s+|phone's\\s+|phone\\s+|apps'\\s+|internet\\s+|network\\s+)?traffic",
                ),
            "every app" to Regex("(?i)\\b(all|every)\\s+(of\\s+)?(your\\s+|the\\s+)?(apps?|applications?)\\b"),
            "full VPN" to
                Regex("(?i)\\b(full|complete|whole)[ -](device\\s+)?(vpn|tunnel|protection|coverage)|\\b(system|device)[ -]wide\\b"),
            "anonymity" to Regex("(?i)\\banonym|\\buntrace|\\buntrack|\\bincognito|\\binvisible online"),
            "hiding identity" to Regex("(?i)\\bhid(e|es|ing)\\s+(your\\s+)?(real\\s+)?(ip|identity|location|activity)"),
            "blanket security" to
                Regex(
                    "(?i)\\b(military|bank)[ -](grade|level)|\\b100\\s?%|\\bcompletely\\s+(private|secure|safe)" +
                        "|\\bfully\\s+(private|secure|encrypted)|\\bsecures?\\s+(every|all)\\b",
                ),
            "encrypts everything" to Regex("(?i)\\bencrypt(s|ed)?\\s+(all|everything|your\\s+traffic|all\\s+your)"),
            "protection" to
                Regex("(?i)\\bprotection\\b|\\bprotect(s|ed|ing)?\\s+(you|your)\\b|\\b(is|are|stays?|keeps?\\s+\\w+)\\s+protected\\b"),
            "no logs" to Regex("(?i)\\bno[- ]logs?\\b"),
            "games and calls" to
                Regex(
                    "(?i)\\bsupports?\\s+(games|calls|video calls|quic|udp)" +
                        "|\\bworks?\\s+(with|for)\\s+(games|calls|video calls|voip|quic|udp)",
                ),
        )

        /** Strings that may name a forbidden claim only to deny it. */
        val NEGATED = mapOf("about_summary" to "anonymity")
        val NEGATION = Regex("(?i)\\b(doesn't|does not|don't|do not|isn't|is not|never|not)\\b")
    }
}
