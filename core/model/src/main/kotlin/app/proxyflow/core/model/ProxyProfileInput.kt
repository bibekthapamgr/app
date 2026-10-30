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
            name = name.trim().ifEmpty { host },
            protocol = protocol,
            host = host,
            port = port.trim().toInt(),
            username = username.takeIf { requiresSignIn && it.isNotEmpty() },
            hasPassword = requiresSignIn && hasPassword,
            useTls = useTls,
            tlsServerName = tlsServerName.trim().takeIf { useTls && it.isNotEmpty() },
        )
    }

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
    }
}
