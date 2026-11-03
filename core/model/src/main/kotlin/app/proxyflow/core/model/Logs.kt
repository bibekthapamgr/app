package app.proxyflow.core.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One entry of the local diagnostic log (M7, ADR-0012): what happened ([event]), at which [stage],
 * the catalogue [code] when something failed, and a few [details] — counts, durations, attempt
 * numbers, the protocol, the engine's fixed phrase for a failure — all passed through
 * [LogSanitizer]. It never holds a credential, a server name, a site's name or address, or
 * anything apps sent. [profileId] is the profile's random id, never its name.
 */
data class LogEntry(
    val id: Long = 0,
    val timeMillis: Long,
    val level: Level,
    val event: LogEvent,
    val stage: ConnectionStage? = null,
    val code: String? = null,
    val profileId: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    enum class Level(val key: String) {
        Info("info"),
        Warning("warning"),
        Error("error"),
        ;

        companion object {
            fun of(key: String): Level = entries.firstOrNull { it.key == key } ?: Info
        }
    }
}

/** What a log entry records. [key] is stored and exported, so it never changes. */
enum class LogEvent(val key: String) {
    /** A connection was asked for (or a switch to another profile, or a restart). */
    ConnectRequested("connect_requested"),

    /** An attempt reached a stage (starting, VPN permission, proxy handshake, authenticated). */
    StageReached("stage_reached"),
    Connected("connected"),

    /** An attempt failed before connecting: nothing was changed on the phone. */
    ConnectFailed("connect_failed"),

    /** Traffic is held after a failure while connected. */
    Blocked("blocked"),
    ReconnectStarted("reconnect_started"),
    ReconnectAttemptFailed("reconnect_attempt_failed"),
    Reconnected("reconnected"),
    ReconnectGaveUp("reconnect_gave_up"),
    Offline("offline"),
    NetworkChanged("network_changed"),
    Stopped("stopped"),
    Revoked("revoked"),
    SessionSummary("session_summary"),

    /** Connections to sites that failed while the proxy itself worked, counted per minute. */
    ConnectionProblems("connection_problems"),
    ;

    companion object {
        fun of(key: String): LogEvent? = entries.firstOrNull { it.key == key }
    }
}

/** The Logs screen's filters (board F2, docs/m1/03 J10). */
enum class LogFilter {
    All,
    Errors,
    Connection,
    Blocked,
    ;

    fun matches(entry: LogEntry): Boolean = when (this) {
        All -> true
        Errors -> entry.level != LogEntry.Level.Info
        Connection -> entry.event in CONNECTION
        Blocked -> entry.event in BLOCKED
    }

    private companion object {
        val CONNECTION = setOf(
            LogEvent.ConnectRequested, LogEvent.StageReached, LogEvent.Connected, LogEvent.ConnectFailed, LogEvent.ReconnectStarted,
            LogEvent.Reconnected, LogEvent.NetworkChanged, LogEvent.Stopped, LogEvent.Revoked, LogEvent.SessionSummary,
        )
        val BLOCKED =
            setOf(LogEvent.Blocked, LogEvent.ReconnectStarted, LogEvent.ReconnectAttemptFailed, LogEvent.ReconnectGaveUp, LogEvent.Offline)
    }
}

/** The keys a log entry's details may have; anything else is dropped (ADR-0012). */
object LogDetail {
    const val PROTOCOL = "protocol"
    const val TLS = "tls_to_proxy"
    const val SIGN_IN = "sign_in"
    const val SWITCH = "switch"
    const val SETUP_MS = "setup_ms"
    const val ATTEMPT = "attempt"
    const val MAX_ATTEMPTS = "max_attempts"
    const val NEXT_TRY_MS = "next_try_ms"
    const val REASON = "reason"
    const val COUNT = "count"
    const val WINDOW_S = "window_s"
    const val DURATION_MS = "duration_ms"
    const val BYTES_UP = "bytes_up"
    const val BYTES_DOWN = "bytes_down"
    const val OPENED = "connections_opened"
    const val FAILED = "connections_failed"
    const val UDP_BLOCKED = "udp_blocked"
    const val PINGS_DROPPED = "pings_dropped"
    const val RECONNECTS = "reconnects"
    const val NETWORK = "network"
    const val RESET = "connections_reset"
    const val CONSECUTIVE = "consecutive_failures"
    const val OUTAGE_MS = "outage_ms"
    const val BY = "by"
    const val DETAIL = "detail"

    val ALLOWED = setOf(
        PROTOCOL, TLS, SIGN_IN, SWITCH, SETUP_MS, ATTEMPT, MAX_ATTEMPTS, NEXT_TRY_MS, REASON, COUNT, WINDOW_S, DURATION_MS, BYTES_UP,
        BYTES_DOWN, OPENED, FAILED, UDP_BLOCKED, PINGS_DROPPED, RECONNECTS, NETWORK, RESET, CONSECUTIVE, OUTAGE_MS, BY, DETAIL,
    )
}

/**
 * The last line of defence for what reaches the log, the export and the screen (ADR-0012): every
 * value is cut to [MAX_LENGTH] characters with control characters removed, and anything that looks
 * like an address, a host or domain name, a URL, an e-mail address or `user:password@`, or a long
 * token is replaced. What the app writes is already counts, keys and fixed phrases; this covers a
 * phrase that came from the engine or the proxy.
 */
object LogSanitizer {
    const val MAX_LENGTH = 160

    private val CODE = Regex("[A-Z][A-Z0-9_]{1,47}")
    private val RULES = listOf(
        Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S*""") to "[url]",
        Regex("""\S+@\S+""") to "[redacted]",
        Regex("""(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])(?::\d{1,5})?""") to "[address]",
        Regex("""\[?(?<![\w:])(?:[0-9A-Fa-f]{0,4}:){2,}[0-9A-Fa-f.]*(?:%\w+)?\]?(?::\d{1,5})?""") to "[address]",
        Regex("""(?i)(?<![\w.-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{1,62}(?![\w-])(?::\d{1,5})?""") to "[host]",
        Regex("""[A-Za-z0-9+/_=-]{24,}""") to "[redacted]",
    )

    /** [raw] made safe to keep: see the class comment. */
    fun text(raw: String): String {
        var clean = raw.map { if (it.isISOControl()) ' ' else it }.joinToString("")
        for ((pattern, replacement) in RULES) clean = pattern.replace(clean, replacement)
        return clean.trim().take(MAX_LENGTH)
    }

    /** A catalogue code as the engine and the app write them (`AUTH_FAILED`); anything else becomes `INTERNAL`. */
    fun code(raw: String?): String? = raw?.let { if (CODE.matches(it)) it else ConnectionError.INTERNAL }

    /** Only [LogDetail.ALLOWED] keys, each value through [text]; empty values are left out. */
    fun details(raw: Map<String, String?>): Map<String, String> = buildMap {
        for ((key, value) in raw) {
            if (key !in LogDetail.ALLOWED || value == null) continue
            val clean = text(value)
            if (clean.isNotEmpty()) put(key, clean)
        }
    }
}

/**
 * The text "Export" and "Copy" hand over (board F2): the entries oldest first, one per line, with
 * stable keys a support person can read, each value sanitized once more, and [header] lines
 * first. Profiles appear as the first 8 characters of their random id.
 */
object LogExport {
    /** Each entry's time, with the phone's offset from UTC, in the export and the detail sheet. */
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)

    /**
     * What Copy and Export hand over at most: Android passes the text to the other app in one
     * transaction, which fails beyond about a megabyte (two bytes a character, and room to spare).
     */
    const val MAX_CHARS = 200_000

    fun text(entries: List<LogEntry>, header: List<String>, zone: ZoneId): String = buildString {
        header.forEach { appendLine(LogSanitizer.text(it)) }
        appendLine()
        for (entry in entries.sortedWith(compareBy<LogEntry> { it.timeMillis }.thenBy { it.id })) appendLine(line(entry, zone))
    }

    /** The newest of [entries] whose lines fit in [maxChars] together, leaving [reserve] for the header. */
    fun newestFitting(entries: List<LogEntry>, zone: ZoneId, maxChars: Int = MAX_CHARS, reserve: Int = 2_000): List<LogEntry> {
        var left = maxChars - reserve
        return entries.sortedWith(compareByDescending<LogEntry> { it.timeMillis }.thenByDescending { it.id })
            .takeWhile { entry ->
                left -= line(entry, zone).length + 1
                left >= 0
            }
    }

    fun line(entry: LogEntry, zone: ZoneId): String = buildList {
        add(TIME.format(Instant.ofEpochMilli(entry.timeMillis).atZone(zone)))
        add(entry.level.key.uppercase(Locale.ROOT).padEnd(LEVEL_WIDTH))
        add(entry.event.key)
        entry.stage?.let { add("stage=${it.key}") }
        LogSanitizer.code(entry.code)?.let { add("code=$it") }
        // The app's own random id, so not sanitized as text (a UUID looks like a token): only its letters and digits are kept.
        entry.profileId?.let { id -> add("profile=${id.filter { it.isLetterOrDigit() }.take(PROFILE_ID_CHARS)}") }
        LogSanitizer.details(entry.details).toSortedMap().forEach { (key, value) -> add("$key=${quoteIfSpaced(value)}") }
    }.joinToString("  ")

    private fun quoteIfSpaced(value: String) = if (value.any { it.isWhitespace() }) "\"${value.replace("\"", "'")}\"" else value

    private const val LEVEL_WIDTH = 7
    private const val PROFILE_ID_CHARS = 8
}
