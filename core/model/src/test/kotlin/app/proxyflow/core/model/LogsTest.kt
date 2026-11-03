package app.proxyflow.core.model

import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the diagnostic log may keep and how it reads (ADR-0012). */
class LogsTest {
    @Test
    fun addressesNamesUrlsAndTokensNeverSurviveTheSanitizer() {
        val leaks = mapOf(
            "dial tcp 203.0.113.7:1080: connect: connection refused" to "203.0.113.7",
            "proxy at [2001:db8::1]:443 closed" to "2001:db8::1",
            "fd99::1 did not answer" to "fd99::1",
            "proxy at 2001:db8:0:0:0:0:0:1 closed" to "2001:db8:0:0:0:0:0:1",
            "fe80::1%wlan0 unreachable" to "fe80::1",
            "via ::ffff:192.0.2.1" to "192.0.2.1",
            "router 3c:22:fb:01:9a:7e answered" to "3c:22:fb:01:9a:7e",
            "addr:2001:db8::1 refused" to "2001:db8::1",
            "cannot reach 203.0.113.5." to "203.0.113.5",
            "lookup my_proxy.example.com failed" to "my_proxy.example.com",
            "certificate for *.provider.net" to "provider.net",
            "dial myproxy:8080 refused" to "myproxy",
            "lookup пример.рф failed" to "пример",
            "header Basic dXNlcjpwYXNzd29yZA== sent" to "dXNlcjpwYXNzd29yZA",
            "url user:secret@" to "secret",
            "no route\u2028FAKE 10:00 entry" to "\u2028",
            "lookup proxy.example.com failed" to "proxy.example.com",
            "sign in to my-proxy.provider.net:3128 refused" to "my-proxy.provider.net",
            "see https://example.org/help?x=1" to "example.org",
            "socks5://alice:secret@10.0.0.1:1080" to "secret",
            "user alice:hunter2@host" to "hunter2",
            "token dGhpcyBpcyBhIHNlY3JldCB0b2tlbiB2YWx1ZQ== leaked" to "dGhpcyBpcyBhIHNlY3JldCB0b2tlbiB2YWx1ZQ",
            // Short, letters only, or looking like a word: still a credential after Basic or Bearer.
            "Basic cHVnOndscXpj" to "cHVnOndscXpj",
            "Basic YTpi" to "YTpi",
            "Bearer abcdefghijklmnop" to "abcdefghijklmnop",
            "Digest username=\"alice\", realm=\"proxy\", nonce=\"dcd98b7102dd2f0e\"" to "alice",
            "Digest username=\"alice\", realm=\"proxy\", nonce=\"dcd98b7102dd2f0e\"" to "dcd98b7102dd2f0e",
            "alice:hunter 2@host" to "hunter",
            "ip_203.0.113.7" to "203.0.113.7",
            "203.0.113.7ms" to "203.0.113.7",
            "--proxy.example.com" to "proxy.example",
            // A value followed by "[" is redacted whole, its last characters too.
            "Basic YWxpY2U6aHVudGVyMg==[x]" to "g==",
        )
        for ((raw, secret) in leaks) {
            val clean = LogSanitizer.text(raw)
            assertFalse("\"$raw\" kept \"$secret\": $clean", clean.contains(secret))
        }
    }

    @Test
    fun theEnginesFixedPhrasesStayReadable() {
        val phrases = listOf(
            "the proxy refused the connection (nothing listening on that port?)",
            "proxy rejected the username or password (HTTP 407)",
            "the proxy did not complete the handshake in time",
            "proxy offers only Digest authentication; V1 supports Basic",
            "reconnecting stopped after 8 attempts",
            "Exported 2026-11-03 10:16:30.250 +09:00",
            "session lasted 00:12:47",
            "proxy offers only Digest authentication; V1 supports Basic",
            "SOCKS5 reply 0x04 host unreachable",
            "the proxy did not reach the site in time",
            "username or password not valid for HTTP Basic authentication",
            "HTTP Basic usernames cannot contain ':' (RFC 7617)",
            "proxy offers only bearer authentication; V1 supports Basic",
            "V1 supports Basic only",
            "HTTP Basic sign-in refused",
        )
        for (phrase in phrases) assertEquals(phrase, LogSanitizer.text(phrase))
    }

    /** A sign-in's parameters lose their values and keep their names, after a scheme too: no rule cuts another's match short. */
    @Test
    fun aSignInsParametersLoseTheirValuesAndKeepTheirNames() {
        assertEquals("Digest username=[redacted], realm=[redacted]", LogSanitizer.text("Digest username=\"alice\", realm=\"proxy\""))
        assertEquals("Basic realm=[redacted]", LogSanitizer.text("Basic realm=\"Squid proxy\""))
        assertEquals("Basic username=[redacted]", LogSanitizer.text("Basic username=alice"))
        assertEquals("Bearer token=[redacted]", LogSanitizer.text("Bearer token=abc"))
    }

    /** Every fixed phrase the engine's errors and the session's own carry, read from their sources, stays as it is. */
    @Test
    fun everyPhraseTheEngineAndTheSessionWriteStaysReadable() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val goCall =
            Regex(
                """(?:newErr\(\s*\w+\s*,\s*|newTimeoutErr\(\s*|errors\.New\(\s*|fmt\.Errorf\(\s*|fmt\.Sprintf\(\s*|Detail:\s*)"((?:[^"\\]|\\.)*)"""",
            )
        val engine = File(root, "engine").walkTopDown()
            .filter { it.extension == "go" && !it.name.endsWith("_test.go") && "proxytest" !in it.path }
            .flatMap { file -> goCall.findAll(file.readText()).map { it.groupValues[1].replace("\\\"", "\"") } }
            .flatMap { phrase ->
                val filled = phrase.replace(Regex("%0?2x"), "0x04").replace("%d", "3").replace("%q", "\"x\"")
                // What %s and %v stand for: the sign-in schemes a proxy offers, or an error's text.
                val fills = if ("authentication" in filled) listOf("basic", "Bearer", "digest,ntlm", "NTLM") else listOf("failure")
                fills.map { filled.replace(Regex("%[svw]"), it) }
            }
        val kotlinCall = Regex("""(?:ConnectionError\(\s*ConnectionError\.\w+\s*,\s*|LogDetail\.DETAIL to )"((?:[^"\\$]|\\.)*)"""")
        val session = File(root, "core").walkTopDown()
            .filter { it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }
            .flatMap { file -> kotlinCall.findAll(file.readText()).map { it.groupValues[1] } }
        val phrases = (engine + session).toSortedSet()
        assertTrue("${phrases.size} phrases", phrases.size > 100)
        for (phrase in phrases) assertEquals(phrase, LogSanitizer.text(phrase))
    }

    @Test
    fun longValuesAreLookedAtQuicklyAndNoHalfOfANameEscapes() {
        val started = System.nanoTime()
        assertEquals(LogSanitizer.MAX_LENGTH, LogSanitizer.text("word ".repeat(20_000)).length)
        assertEquals("one long token", "[redacted]", LogSanitizer.text("x".repeat(80_000)))
        assertEquals("one word too long to look at", "[redacted]", LogSanitizer.text("a.".repeat(40_000) + "com"))
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000} ms", System.nanoTime() - started < 1_000_000_000)
        // A name cut where the looked-at part ends must not leave its first half in the clear.
        val cut = "word ".repeat(199) + "proxy.example.com"
        assertFalse(LogSanitizer.text(cut).contains("proxy.exa"))
    }

    @Test
    fun valuesAreBoundedAndControlCharactersRemoved() {
        assertEquals(LogSanitizer.MAX_LENGTH, LogSanitizer.text("a".repeat(500).chunked(10).joinToString(" ")).length)
        assertEquals("one two", LogSanitizer.text("one\ntwo"))
        assertEquals("a b", LogSanitizer.text("a\u0000b"))
    }

    @Test
    fun onlyKnownDetailKeysAreKept() {
        val details = LogSanitizer.details(
            mapOf(
                LogDetail.ATTEMPT to "2",
                "password" to "hunter2",
                "destination" to "example.com",
                LogDetail.DETAIL to null,
                LogDetail.REASON to " ",
            ),
        )
        assertEquals(mapOf(LogDetail.ATTEMPT to "2"), details)
    }

    @Test
    fun codesMustLookLikeCatalogueCodes() {
        assertEquals("AUTH_FAILED", LogSanitizer.code("AUTH_FAILED"))
        assertEquals(ConnectionError.INTERNAL, LogSanitizer.code("auth failed for alice"))
        assertEquals(null, LogSanitizer.code(null))
    }

    @Test
    fun theExportListsEntriesOldestFirstWithStableKeysAndNoSecrets() {
        val entries = listOf(
            LogEntry(
                id = 2,
                timeMillis = 61_000,
                level = LogEntry.Level.Error,
                event = LogEvent.ConnectFailed,
                stage = ConnectionStage.ProxyHandshake,
                code = "AUTH_FAILED",
                profileId = "5f25d4ff-5b6a-4e36-861e-9f4804cf4380",
                details = mapOf(LogDetail.DETAIL to "proxy at 203.0.113.7 rejected alice:hunter2@x"),
            ),
            LogEntry(
                id = 1,
                timeMillis = 1_000,
                level = LogEntry.Level.Info,
                event = LogEvent.ConnectRequested,
                details = mapOf(LogDetail.PROTOCOL to "SOCKS5"),
            ),
        )

        val text = LogExport.text(entries, header = listOf("ProxyFlow log"), zone = ZoneOffset.UTC)
        val lines = text.lines()

        assertEquals("ProxyFlow log", lines[0])
        assertEquals("", lines[1])
        assertEquals("1970-01-01 00:00:01.000 +00:00  INFO     connect_requested  protocol=SOCKS5", lines[2])
        assertTrue(
            lines[3],
            lines[3].startsWith(
                "1970-01-01 00:01:01.000 +00:00  ERROR    connect_failed  stage=proxy_handshake  code=AUTH_FAILED  profile=5f25d4ff",
            ),
        )
        for (secret in listOf("203.0.113.7", "hunter2", "alice", "9f4804cf4380")) assertFalse(text, text.contains(secret))
    }

    @Test
    fun filtersPickTheirEntries() {
        fun entry(event: LogEvent, level: LogEntry.Level = LogEntry.Level.Info) = LogEntry(timeMillis = 0, level = level, event = event)
        val connected = entry(LogEvent.Connected)
        val gaveUp = entry(LogEvent.ReconnectGaveUp, LogEntry.Level.Error)
        val problems = entry(LogEvent.ConnectionProblems, LogEntry.Level.Warning)
        val offline = entry(LogEvent.Offline, LogEntry.Level.Warning)

        assertEquals(
            listOf(connected, gaveUp, problems, offline),
            listOf(connected, gaveUp, problems, offline).filter(LogFilter.All::matches),
        )
        assertEquals(listOf(gaveUp, problems, offline), listOf(connected, gaveUp, problems, offline).filter(LogFilter.Errors::matches))
        assertEquals(listOf(connected), listOf(connected, gaveUp, problems, offline).filter(LogFilter.Connection::matches))
        assertEquals(listOf(gaveUp, offline), listOf(connected, gaveUp, problems, offline).filter(LogFilter.Blocked::matches))
    }

    @Test
    fun everyStateHasTheStageTheBriefNames() {
        fun stage(status: ConnectionStatus, step: ConnectStep? = null, error: ConnectionError? = null, reconnect: ReconnectInfo? = null) =
            ConnectionState(status = status, step = step, error = error, reconnect = reconnect).stage
        val retry = ReconnectInfo(ReconnectReason.ProxyFailures, attempt = 1, maxAttempts = 8, nextAttemptAtMillis = null, lastError = null)

        assertEquals(ConnectionStage.Starting, stage(ConnectionStatus.Connecting, ConnectStep.Starting))
        assertEquals(ConnectionStage.VpnPermission, stage(ConnectionStatus.Connecting, ConnectStep.VpnPermission))
        assertEquals(ConnectionStage.ProxyHandshake, stage(ConnectionStatus.Connecting, ConnectStep.ProxyHandshake))
        assertEquals(ConnectionStage.Authenticated, stage(ConnectionStatus.Connecting, ConnectStep.Authenticated))
        assertEquals(ConnectionStage.Connected, stage(ConnectionStatus.Connected))
        assertEquals(
            "a switch shows its steps",
            ConnectionStage.ProxyHandshake,
            stage(ConnectionStatus.Reconnecting, ConnectStep.ProxyHandshake),
        )
        assertEquals(ConnectionStage.Reconnecting, stage(ConnectionStatus.Reconnecting, ConnectStep.ProxyHandshake, reconnect = retry))
        assertEquals(ConnectionStage.Reconnecting, stage(ConnectionStatus.Offline))
        assertEquals(ConnectionStage.Failed, stage(ConnectionStatus.Blocked, error = ConnectionError("AUTH_FAILED")))
        assertEquals(ConnectionStage.Failed, stage(ConnectionStatus.Disconnected, error = ConnectionError("PROXY_UNREACHABLE")))
        assertEquals(ConnectionStage.Stopped, stage(ConnectionStatus.Disconnected))
        for (stage in ConnectionStage.entries) assertEquals(stage, ConnectionStage.of(stage.key))
        for (event in LogEvent.entries) assertEquals(event, LogEvent.of(event.key))
    }

    /** Copy and Export hand over one transaction's worth: the newest entries that fit, never a cut line. */
    @Test
    fun anExportTooLargeForOneTransactionKeepsTheNewestEntries() {
        val zone = ZoneId.of("UTC")
        val entries = (1..2_000L).map { i ->
            LogEntry(
                id = i,
                timeMillis = 1_000_000L + i,
                level = LogEntry.Level.Info,
                event = LogEvent.Connected,
                details = mapOf(LogDetail.SETUP_MS to "1234"),
            )
        }
        val lineLength = LogExport.line(entries.first(), zone).length + 1

        val kept = LogExport.newestFitting(entries.shuffled(), zone, maxChars = 50 * lineLength + 1_000, reserve = 1_000)

        assertEquals((2_000L downTo 1_951L).toList(), kept.map { it.id })
        val day = entries.take(1_000) // a busy day's log fits whole
        assertEquals(day, LogExport.newestFitting(day, zone).sortedBy { it.id })
        assertTrue(LogExport.text(LogExport.newestFitting(entries, zone), listOf("h"), zone).length <= LogExport.MAX_CHARS)
    }
}
