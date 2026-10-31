package app.proxyflow.core.model

/** Form fields that can carry an error message. */
enum class ProfileField { Name, Server, Port, Username, Password, TlsServerName }

/** Validation outcomes; the UI maps each to a string resource (docs/m1/04 §5). */
enum class ProfileError {
    NameTooLong,

    /** Line breaks, tabs or other control characters, usually pasted by accident, or broken text (half of an emoji). */
    ControlCharacters,
    ServerInvalid,
    PortOutOfRange,
    UsernameRequired,
    UsernameHasColon,
    UsernameTooLong,
    PasswordWithoutUsername,
    PasswordRequired,
    PasswordTooLong,
    TlsServerNameInvalid,
}

/**
 * Validation rules for the Add/Edit form (docs/m1/04-ux-screens-and-error-states.md §5).
 * Pure functions: no DNS lookups or other I/O, so they are safe on any thread.
 */
object ProfileValidator {
    const val MAX_NAME_LENGTH = 40
    const val MAX_HOSTNAME_LENGTH = 253
    const val MAX_LABEL_LENGTH = 63

    /** RFC 1929 caps SOCKS5 username and password at 255 bytes each. */
    const val MAX_SOCKS5_CREDENTIAL_BYTES = 255

    /**
     * HTTP proxies receive both in one Proxy-Authorization header, which proxies
     * cap at a few kilobytes; 1 024 bytes each keeps it under 3 KB.
     */
    const val MAX_HTTP_CREDENTIAL_BYTES = 1024

    /**
     * @param savedPasswordKept a password saved earlier for exactly this protocol, server, port, username
     *   and TLS setting will be kept if the password field is left empty (see [ProxyProfileInput.sameCredentialTargetAs]).
     */
    fun validate(input: ProxyProfileInput, savedPasswordKept: Boolean = false): Map<ProfileField, ProfileError> {
        val errors = linkedMapOf<ProfileField, ProfileError>()

        nameError(input.name)?.let { errors[ProfileField.Name] = it }

        if (!isValidHost(normalizeHost(input.server))) errors[ProfileField.Server] = ProfileError.ServerInvalid

        val port = input.port.trim().takeIf { it.isNotEmpty() && it.all(::isAsciiDigit) && it.length <= 5 }?.toInt()
        if (port == null || port !in 1..65535) errors[ProfileField.Port] = ProfileError.PortOutOfRange

        if (input.requiresSignIn) {
            val limit = if (input.protocol == ProxyProtocol.SOCKS5) MAX_SOCKS5_CREDENTIAL_BYTES else MAX_HTTP_CREDENTIAL_BYTES
            when {
                input.username.isEmpty() && input.password.isNotEmpty() ->
                    errors[ProfileField.Password] = ProfileError.PasswordWithoutUsername
                input.username.isEmpty() ->
                    errors[ProfileField.Username] = ProfileError.UsernameRequired
                input.username.hasInvalidCharacters() ->
                    errors[ProfileField.Username] = ProfileError.ControlCharacters
                input.protocol == ProxyProtocol.HTTP && ':' in input.username ->
                    errors[ProfileField.Username] = ProfileError.UsernameHasColon
                input.username.utf8Length() > limit ->
                    errors[ProfileField.Username] = ProfileError.UsernameTooLong
            }
            if (input.password.hasInvalidCharacters()) {
                errors.getOrPut(ProfileField.Password) { ProfileError.ControlCharacters }
            } else if (input.password.utf8Length() > limit) {
                errors.getOrPut(ProfileField.Password) { ProfileError.PasswordTooLong }
            }
            // A sign-in without a password would fail at the proxy; RFC 1929 also needs at least one byte.
            if (input.username.isNotEmpty() && input.password.isEmpty() && !savedPasswordKept) {
                errors.getOrPut(ProfileField.Password) { ProfileError.PasswordRequired }
            }
        }

        if (input.useTls && input.tlsServerName.isNotBlank() && !isValidHostname(input.tlsServerName.trim())) {
            errors[ProfileField.TlsServerName] = ProfileError.TlsServerNameInvalid
        }
        return errors
    }

    /** The error for a profile name, or null. Used for names typed in the form and for the names Duplicate makes. */
    fun nameError(name: String): ProfileError? = when {
        name.trim().length > MAX_NAME_LENGTH -> ProfileError.NameTooLong
        name.hasInvalidCharacters() -> ProfileError.ControlCharacters
        else -> null
    }

    /** Trims spaces and the brackets users often type around IPv6 literals. */
    fun normalizeHost(raw: String): String {
        val s = raw.trim()
        return if (s.length > 2 && s.startsWith('[') && s.endsWith(']')) s.substring(1, s.length - 1) else s
    }

    /** A hostname, an IPv4 literal or an IPv6 literal. */
    fun isValidHost(host: String): Boolean = isIpv4Literal(host) || isIpv6Literal(host) || isValidHostname(host)

    /**
     * Letters, digits and hyphens (LDH) per label; labels 1–63 characters, not
     * starting or ending with a hyphen; 253 characters in total. A name made only
     * of digits and dots is not a hostname (it is a malformed IPv4 address).
     */
    fun isValidHostname(host: String): Boolean {
        val name = host.removeSuffix(".")
        if (name.isEmpty() || name.length > MAX_HOSTNAME_LENGTH) return false
        if (name.all { isAsciiDigit(it) || it == '.' }) return false
        return name.split('.').all { label ->
            label.length in 1..MAX_LABEL_LENGTH &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }

    /** Dotted-quad IPv4 without leading zeros. */
    fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all(::isAsciiDigit) && (p.length == 1 || p[0] != '0') && p.toInt() <= 255
        }
    }

    /** RFC 4291 text form, with optional "::" and an embedded IPv4 tail; no zone IDs. */
    fun isIpv6Literal(host: String): Boolean {
        if (host.isEmpty() || '%' in host || host.count { it == ':' } < 2) return false
        val doubleColons = host.windowed(2).count { it == "::" }
        if (doubleColons > 1 || ":::" in host) return false
        var groups = host
        var maxGroups = 8
        val lastColon = host.lastIndexOf(':')
        val tail = host.substring(lastColon + 1)
        if ('.' in tail) {
            if (!isIpv4Literal(tail)) return false
            groups = host.substring(0, lastColon + 1).let { if (it.endsWith("::")) it else it.removeSuffix(":") }
            maxGroups = 6
        }
        fun hexGroup(g: String) = g.length in 1..4 && g.all { isAsciiDigit(it) || it in 'a'..'f' || it in 'A'..'F' }
        return if (doubleColons == 1) {
            val (left, right) = groups.split("::", limit = 2).let { it[0] to it[1] }
            val l = if (left.isEmpty()) emptyList() else left.split(':')
            val r = if (right.isEmpty()) emptyList() else right.split(':')
            l.all(::hexGroup) && r.all(::hexGroup) && l.size + r.size < maxGroups
        } else {
            val g = groups.split(':')
            g.size == maxGroups && g.all(::hexGroup)
        }
    }

    private fun String.utf8Length(): Int = encodeToByteArray().size

    /**
     * Only '0'–'9': Kotlin's isDigit() also accepts other scripts' digits, which
     * would pass here but fail later as a host name.
     */
    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    /**
     * Characters a phone keyboard cannot type (control characters, the Unicode line
     * and paragraph separators) and broken text: half of a character that needs two
     * UTF-16 units, such as most emoji, which no storage or proxy keeps intact.
     */
    private fun String.hasInvalidCharacters(): Boolean {
        var i = 0
        while (i < length) {
            val c = this[i]
            when {
                c.isISOControl() || c == '\u2028' || c == '\u2029' -> return true
                c.isHighSurrogate() -> if (i + 1 < length && this[i + 1].isLowSurrogate()) i++ else return true
                c.isLowSurrogate() -> return true
            }
            i++
        }
        return false
    }
}
