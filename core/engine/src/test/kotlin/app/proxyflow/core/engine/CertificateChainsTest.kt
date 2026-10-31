package app.proxyflow.core.engine

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CertificateChainsTest {
    // A public test chain (no private keys): CN=chain.test issued by "ProxyFlow Unit Test CA".
    private val leaf =
        "MIIBhDCCASqgAwIBAgIBAjAKBggqhkjOPQQDAjAhMR8wHQYDVQQDExZQcm94eUZsb3cgVW5pdCBUZXN0IENBMB4XDTI2MDEwMTAw" +
            "MDAwMFoXDTQ2MDEwMTAwMDAwMFowFTETMBEGA1UEAxMKY2hhaW4udGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABI6zGVk+" +
            "7PImviAvAvKy0YjbHNZ0kLtAYLN2bm39hJ/1RrLPKAN1J0XL8sw6wSJ+FzqsUbS7DrEpK0JjyddLmpKjXzBdMA4GA1UdDwEB/wQE" +
            "AwIHgDATBgNVHSUEDDAKBggrBgEFBQcDATAfBgNVHSMEGDAWgBRxkRI9XrhQK+H0dNYvclwP/5T7vjAVBgNVHREEDjAMggpjaGFp" +
            "bi50ZXN0MAoGCCqGSM49BAMCA0gAMEUCIAu5ZchB5AFh3Sh5OeCC1KgmlyccvWi6JC5kyJZUpWkeAiEAmskBgk8VtxTT82f8NzKV" +
            "tV2m4rXTMgyFarjvDUXelMo="

    private val ca =
        "MIIBdDCCARmgAwIBAgIBATAKBggqhkjOPQQDAjAhMR8wHQYDVQQDExZQcm94eUZsb3cgVW5pdCBUZXN0IENBMB4XDTI2MDEwMTAw" +
            "MDAwMFoXDTQ2MDEwMTAwMDAwMFowITEfMB0GA1UEAxMWUHJveHlGbG93IFVuaXQgVGVzdCBDQTBZMBMGByqGSM49AgEGCCqGSM49" +
            "AwEHA0IABETGDJW6iR0M2L0Nl3IYTr2lJGjdG8jzx4U86Nh4q4HeszbdoswTEjOnHklCUAMRsZ6RdQ7PdGRomS60ODabX/GjQjBA" +
            "MA4GA1UdDwEB/wQEAwICBDAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBRxkRI9XrhQK+H0dNYvclwP/5T7vjAKBggqhkjOPQQD" +
            "AgNJADBGAiEAnDXeME5DEL7uhKn5X+BE7JKMPFYJIe7PsmRGYa1pIjUCIQDLphX2DAMWTQDQ+A7Jui1xqIIdrDLN7JyXsqbCHlWA" +
            "Hg=="

    private fun der(b64: String) = Base64.getDecoder().decode(b64)

    private fun frame(vararg certs: ByteArray): ByteArray = certs.fold(ByteArray(0)) { acc, c ->
        acc + byteArrayOf((c.size ushr 24).toByte(), (c.size ushr 16).toByte(), (c.size ushr 8).toByte(), c.size.toByte()) + c
    }

    @Test
    fun `parses the engine's length-prefixed chain, leaf first`() {
        val chain = CertificateChains.parse(frame(der(leaf), der(ca)))
        assertEquals(2, chain.size)
        assertEquals("CN=chain.test", chain[0].subjectX500Principal.name)
        assertEquals("CN=ProxyFlow Unit Test CA", chain[1].subjectX500Principal.name)
        assertEquals(0, CertificateChains.parse(ByteArray(0)).size)
    }

    @Test
    fun `rejects truncated or damaged chains`() {
        val good = frame(der(leaf))
        assertThrows(CertificateException::class.java) { CertificateChains.parse(good.copyOf(good.size - 1)) }
        assertThrows(CertificateException::class.java) { CertificateChains.parse(byteArrayOf(0, 0, 0)) }
        assertThrows(CertificateException::class.java) { CertificateChains.parse(byteArrayOf(0x7f, 0, 0, 0, 1)) }
        assertThrows(CertificateException::class.java) { CertificateChains.parse(frame(byteArrayOf(1, 2, 3))) }
    }

    @Test
    fun `validity problems are TLS_EXPIRED, everything else TLS_UNTRUSTED`() {
        assertEquals("TLS_EXPIRED", CertificateChains.verdict(CertificateException(CertificateExpiredException("x"))))
        assertEquals("TLS_EXPIRED", CertificateChains.verdict(CertificateNotYetValidException("x")))
        val expiredPath = CertPathValidatorException("x", null, null, -1, CertPathValidatorException.BasicReason.EXPIRED)
        assertEquals("TLS_EXPIRED", CertificateChains.verdict(CertificateException(expiredPath)))
        val unknownCa = CertPathValidatorException("Trust anchor for certification path not found.")
        assertEquals("TLS_UNTRUSTED", CertificateChains.verdict(CertificateException(unknownCa)))
        assertEquals("TLS_UNTRUSTED", CertificateChains.verdict(CertificateException("other")))
    }
}
