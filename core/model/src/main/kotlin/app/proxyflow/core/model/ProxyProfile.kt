package app.proxyflow.core.model

/** The two proxy protocols ProxyFlow speaks (traffic matrix §1). */
enum class ProxyProtocol { SOCKS5, HTTP }

/** How the connection from the phone to the proxy itself is protected (ADR-0005). */
enum class TlsMode {
    /** Plain TCP: the proxy password and the names of the sites opened can be read on the network (board D4). */
    None,

    /** TLS 1.2 or newer to the proxy, its certificate and host name checked against the phone's trusted CAs. */
    Tls,
}

/**
 * A saved proxy profile: every setting except the username and password. Those
 * are stored encrypted (ADR-0006) and only decrypted for the edit screen
 * (the username) or for connecting.
 */
data class ProxyProfile(
    val id: String,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val tlsMode: TlsMode = TlsMode.None,
    /** The server name TLS sends and checks, when it differs from [host]; null uses [host]. */
    val tlsServerName: String? = null,
    /** The proxy asks for a username and password. */
    val requiresSignIn: Boolean = false,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
) {
    val useTls: Boolean get() = tlsMode == TlsMode.Tls

    /** What the UI shows: the name, or the server when no name was given. */
    val displayName: String get() = name.ifBlank { host }

    /** "host:port", with brackets around IPv6 literals. */
    val endpoint: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
}

/** Whether a profile's saved username and password can be used (ADR-0006). */
enum class CredentialState {
    /** The proxy needs no sign-in. */
    NotRequired,

    /** Saved, and decrypted with this phone's Keystore key. */
    Saved,

    /**
     * Saved, but they can't be decrypted on this phone any more (the Keystore key
     * was lost or reset, or the stored data is damaged). They are never used; the
     * user enters them again (`CREDENTIAL_UNREADABLE`, board H3).
     */
    Unreadable,
}
