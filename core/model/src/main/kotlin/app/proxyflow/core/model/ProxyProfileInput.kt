package app.proxyflow.core.model

/** The Add/Edit form as typed, before validation (all text, as the user entered it). */
data class ProxyProfileInput(
    val name: String = "",
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val server: String = "",
    val port: String = "",
    val requiresSignIn: Boolean = false,
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = false,
    val tlsServerName: String = "",
) {
    /** Builds the profile to save. Call only when [ProfileValidator.validate] returned no errors. */
    fun toProfile(id: String, hasPassword: Boolean): ProxyProfile {
        val host = ProfileValidator.normalizeHost(server)
        return ProxyProfile(
            id = id,
            // Stored as typed. An empty name stays empty; the UI shows the server instead (ProxyProfile.displayName).
            name = name.trim(),
            protocol = protocol,
            host = host,
            port = port.trim().toInt(),
            username = username.takeIf { requiresSignIn && it.isNotEmpty() },
            hasPassword = requiresSignIn && hasPassword,
            useTls = useTls,
            tlsServerName = tlsServerName.trim().takeIf { useTls && it.isNotEmpty() },
        )
    }

    /**
     * Whether a password saved for [saved] may be reused with this form: the same
     * protocol, server, port, username and TLS settings. A password belongs to one
     * proxy account reached one way, so any change to where or how it would be sent
     * means the user has to enter it again (ADR-0006).
     */
    fun sameCredentialTargetAs(saved: ProxyProfileInput): Boolean = requiresSignIn &&
        saved.requiresSignIn &&
        protocol == saved.protocol &&
        hostKey(server) == hostKey(saved.server) &&
        port.trim().toIntOrNull() == saved.port.trim().toIntOrNull() &&
        username == saved.username &&
        useTls == saved.useTls &&
        (!useTls || tlsServerName.trim().lowercase() == saved.tlsServerName.trim().lowercase())

    /** Keeps the username and password out of logs, crash reports and test failure messages. */
    override fun toString(): String = "ProxyProfileInput(name=$name, protocol=$protocol, server=$server, port=$port, " +
        "requiresSignIn=$requiresSignIn, username=${redacted(username)}, password=${redacted(password)}, " +
        "useTls=$useTls, tlsServerName=$tlsServerName)"

    companion object {
        /** The form pre-filled from a saved profile. The password is never read back. */
        fun from(profile: ProxyProfile) = ProxyProfileInput(
            name = profile.name,
            protocol = profile.protocol,
            server = profile.host,
            port = profile.port.toString(),
            requiresSignIn = profile.requiresSignIn,
            username = profile.username.orEmpty(),
            password = "",
            useTls = profile.useTls,
            tlsServerName = profile.tlsServerName.orEmpty(),
        )

        private fun hostKey(server: String) = ProfileValidator.normalizeHost(server).removeSuffix(".").lowercase()
    }
}

/** How credentials appear in [toString] output: whether one is present, never its value. */
internal fun redacted(secret: String?): String = if (secret.isNullOrEmpty()) "<none>" else "<redacted>"
