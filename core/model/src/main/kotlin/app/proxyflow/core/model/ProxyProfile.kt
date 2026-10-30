package app.proxyflow.core.model

/** The two proxy protocols ProxyFlow speaks (traffic matrix §1). */
enum class ProxyProtocol { SOCKS5, HTTP }

/**
 * A saved proxy. The password is deliberately not part of this type: it lives
 * only in the credential store (in memory in M2, Keystore-encrypted from M3)
 * and is handed to the VPN layer when connecting (ADR-0006).
 */
data class ProxyProfile(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val username: String? = null,
    val hasPassword: Boolean = false,
    val useTls: Boolean = false,
    val tlsServerName: String? = null,
) {
    /** What the UI shows: the name, or the server when no name was given. */
    val displayName: String get() = name.ifBlank { host }

    /** "host:port", with brackets around IPv6 literals. */
    val endpoint: String get() = if (':' in host) "[$host]:$port" else "$host:$port"

    val requiresSignIn: Boolean get() = !username.isNullOrEmpty()
}
