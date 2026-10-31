package app.proxyflow.core.engine

import android.net.Network
import android.net.http.X509TrustManagerExtensions
import app.proxyflow.engine.go.pfengine.Platform
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The engine's callbacks into Android (pfengine.Platform). The engine calls
 * them from its own threads, concurrently.
 *
 * @param protector keeps a socket out of the VPN (VpnService::protect once the
 *   VPN runs; before that nothing needs protecting).
 * @param network the network the VPN runs over, for resolving the proxy's
 *   hostname outside the tunnel; null uses the default network.
 * @param trustManager decides whether a TLS proxy's certificate is trusted:
 *   by default the platform's, which honours the system CAs, Android's
 *   distrust rules and the app's network security config (ADR-0005).
 */
class AndroidEnginePlatform(
    private val protector: (Int) -> Boolean = { true },
    private val network: Network? = null,
    trustManager: X509TrustManager = systemTrustManager(),
    private val onEvent: (EngineEvent) -> Unit = {},
) : Platform {
    private val trust = X509TrustManagerExtensions(trustManager)

    override fun protect(fd: Long): Boolean = protector(fd.toInt())

    override fun resolveProxyHost(host: String): String {
        val addresses = network?.getAllByName(host) ?: InetAddress.getAllByName(host)
        return addresses.mapNotNull { it.hostAddress }.joinToString(",")
    }

    override fun verifyChain(derChain: ByteArray, host: String): String {
        val chain = try {
            CertificateChains.parse(derChain)
        } catch (e: CertificateException) {
            return "TLS_UNTRUSTED: unreadable certificate"
        }
        if (chain.isEmpty()) return "TLS_UNTRUSTED: no certificate"
        return try {
            // The engine has checked the hostname already; the host given here lets the
            // platform apply its per-domain rules (network security config, pinning).
            trust.checkServerTrusted(chain.toTypedArray(), "GENERIC", host)
            ""
        } catch (e: CertificateException) {
            CertificateChains.verdict(e)
        }
    }

    override fun onEvent(json: String) {
        EngineJson.event(json)?.let(onEvent)
    }

    companion object {
        /** The platform's default trust manager (system CAs, network security config). */
        fun systemTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/** The certificate chain format of pfengine.Platform.VerifyChain, and verdicts. */
object CertificateChains {
    /** Each certificate as a 4-byte big-endian length then DER, leaf first. */
    fun parse(derChain: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val out = mutableListOf<X509Certificate>()
        var at = 0
        while (at < derChain.size) {
            if (at + 4 > derChain.size) throw CertificateException("truncated chain")
            val length = ((derChain[at].toInt() and 0xFF) shl 24) or ((derChain[at + 1].toInt() and 0xFF) shl 16) or
                ((derChain[at + 2].toInt() and 0xFF) shl 8) or (derChain[at + 3].toInt() and 0xFF)
            at += 4
            if (length < 0 || at + length > derChain.size) throw CertificateException("truncated chain")
            out += factory.generateCertificate(ByteArrayInputStream(derChain, at, length)) as X509Certificate
            at += length
        }
        return out
    }

    /** Maps a trust failure to the engine's codes: TLS_EXPIRED for validity dates, else TLS_UNTRUSTED. */
    fun verdict(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is CertificateExpiredException || cause is CertificateNotYetValidException) return "TLS_EXPIRED"
            if (cause is CertPathValidatorException &&
                (
                    cause.reason == CertPathValidatorException.BasicReason.EXPIRED ||
                        cause.reason == CertPathValidatorException.BasicReason.NOT_YET_VALID
                    )
            ) {
                return "TLS_EXPIRED"
            }
            cause = cause.cause
        }
        return "TLS_UNTRUSTED"
    }
}
