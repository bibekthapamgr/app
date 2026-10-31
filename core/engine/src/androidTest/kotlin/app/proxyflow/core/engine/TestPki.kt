package app.proxyflow.core.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A throwaway PKI made on the device for each test run: a CA and server
 * certificates signed by it (valid, expired, issued for another name). Android
 * has no public certificate builder, so this writes the X.509 DER itself
 * (ECDSA P-256, SHA-256); CertificateFactory parsing the result checks it.
 */
object TestPki {
    private const val DAY = 24 * 60 * 60 * 1000L
    private val serial = AtomicLong(1)
    private val password = CharArray(0)

    class Issued(val keys: KeyPair, val certificate: X509Certificate, val chain: List<X509Certificate>, val subject: ByteArray)

    val ca: Issued by lazy { issue("ProxyFlow Device Test CA", isCa = true, issuer = null) }

    /** For proxy.test and 127.0.0.1. */
    val valid: Issued by lazy { issue("proxy.test", dnsNames = listOf("proxy.test"), ips = listOf("127.0.0.1"), issuer = ca) }

    /** Expired a month ago. */
    val expired: Issued by lazy {
        val now = System.currentTimeMillis()
        issue(
            "proxy.test",
            dnsNames = listOf("proxy.test"),
            ips = listOf("127.0.0.1"),
            issuer = ca,
            notBefore = now - 60 * DAY,
            notAfter =
            now - 30 * DAY,
        )
    }

    /** Valid, but only for other.test. */
    val otherName: Issued by lazy { issue("other.test", dnsNames = listOf("other.test"), issuer = ca) }

    /** A TLS server context presenting [issued] and its CA. */
    fun serverContext(issued: Issued): SSLContext {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        store.setKeyEntry("server", issued.keys.private, password, issued.chain.toTypedArray())
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    }

    /** A platform trust manager that trusts the test CA only (a stand-in for a public CA). */
    fun trustManager(): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        store.setCertificateEntry("ca", ca.certificate)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun issue(
        commonName: String,
        isCa: Boolean = false,
        dnsNames: List<String> = emptyList(),
        ips: List<String> = emptyList(),
        issuer: Issued?,
        notBefore: Long = System.currentTimeMillis() - DAY,
        notAfter: Long = System.currentTimeMillis() + 30 * DAY,
    ): Issued {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val extensions = mutableListOf<ByteArray>()
        if (isCa) {
            extensions += extension(OID_BASIC_CONSTRAINTS, critical = true, seq(der(0x01, byteArrayOf(0xFF.toByte()))))
            extensions += extension(OID_KEY_USAGE, critical = true, der(0x03, byteArrayOf(0x01, 0x06))) // keyCertSign, cRLSign
        } else {
            extensions += extension(OID_KEY_USAGE, critical = true, der(0x03, byteArrayOf(0x07, 0x80.toByte()))) // digitalSignature
            extensions += extension(OID_EXT_KEY_USAGE, critical = false, seq(OID_SERVER_AUTH))
            val names = dnsNames.map { der(0x82, it.toByteArray(Charsets.US_ASCII)) } +
                ips.map { der(0x87, InetAddress.getByName(it).address) }
            if (names.isNotEmpty()) extensions += extension(OID_SUBJECT_ALT_NAME, critical = false, seq(*names.toTypedArray()))
        }
        val subject = name(commonName)
        val tbs = seq(
            der(0xA0, der(0x02, byteArrayOf(2))), // v3
            der(0x02, BigInteger.valueOf(serial.getAndIncrement()).toByteArray()),
            seq(OID_ECDSA_SHA256),
            issuer?.subject ?: subject, // the issuer's subject, byte for byte
            seq(time(notBefore), time(notAfter)),
            subject,
            keys.public.encoded, // SubjectPublicKeyInfo
            der(0xA3, seq(*extensions.toTypedArray())),
        )
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign((issuer?.keys ?: keys).private)
            update(tbs)
        }
        val certificate = seq(tbs, seq(OID_ECDSA_SHA256), der(0x03, byteArrayOf(0) + signer.sign()))
        val x509 = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        x509.verify((issuer?.keys ?: keys).public)
        return Issued(keys, x509, listOf(x509) + (issuer?.let { listOf(it.certificate) } ?: emptyList()), subject)
    }

    private fun name(commonName: String) = seq(der(0x31, seq(OID_COMMON_NAME, der(0x0C, commonName.toByteArray()))))

    private fun time(millis: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(0x17, format.format(Date(millis)).toByteArray(Charsets.US_ASCII)) // UTCTime
    }

    private fun extension(oid: ByteArray, critical: Boolean, value: ByteArray): ByteArray = if (critical) {
        seq(oid, der(0x01, byteArrayOf(0xFF.toByte())), der(0x04, value))
    } else {
        seq(oid, der(0x04, value))
    }

    private fun seq(vararg parts: ByteArray): ByteArray = der(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> {
                out.write(0x81)
                out.write(n)
            }
            else -> {
                out.write(0x82)
                out.write(n ushr 8)
                out.write(n and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun oid(vararg bytes: Int) = byteArrayOf(0x06, bytes.size.toByte()) + bytes.map { it.toByte() }.toByteArray()

    private val OID_ECDSA_SHA256 = oid(0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02) // 1.2.840.10045.4.3.2
    private val OID_COMMON_NAME = oid(0x55, 0x04, 0x03) // 2.5.4.3
    private val OID_BASIC_CONSTRAINTS = oid(0x55, 0x1D, 0x13) // 2.5.29.19
    private val OID_KEY_USAGE = oid(0x55, 0x1D, 0x0F) // 2.5.29.15
    private val OID_EXT_KEY_USAGE = oid(0x55, 0x1D, 0x25) // 2.5.29.37
    private val OID_SUBJECT_ALT_NAME = oid(0x55, 0x1D, 0x11) // 2.5.29.17
    private val OID_SERVER_AUTH = oid(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01) // 1.3.6.1.5.5.7.3.1
}
