package app.proxyflow.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    private val valid = ProxyProfileInput(server = "proxy.example.com", port = "1080")

    @Test
    fun minimalSocksProfileIsValid() {
        assertEquals(emptyMap<ProfileField, ProfileError>(), ProfileValidator.validate(valid))
    }

    @Test
    fun acceptsHostnamesAndAddressLiterals() {
        listOf(
            "proxy.example.com", "proxy", "a-b.example", "example.com.", "xn--bcher-kva.example",
            "203.0.113.10", "0.0.0.0", "255.255.255.255",
            "::1", "::", "2001:db8::1", "[2001:db8::1]", "2001:0db8:0000:0000:0000:0000:0000:0001",
            "1::2:3:4:5:6:7", "1:2:3:4:5:6:7::", "::ffff:192.0.2.1", "1:2:3:4:5:6:1.2.3.4",
        ).forEach { host ->
            assertTrue("$host should be accepted", ProfileValidator.isValidHost(ProfileValidator.normalizeHost(host)))
        }
    }

    @Test
    fun rejectsMalformedServers() {
        listOf(
            "", " ", "proxy example.com", "proxy.example.com:1080", "-bad.example", "bad-.example", "a..b",
            "under_score.example", "999.1.1.1", "1.2.3", "01.2.3.4", "1.2.3.4.5",
            "1:2", "1::2::3", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "fe80::1%eth0", "12345::1", "::g",
            "a".repeat(64) + ".example", ("a".repeat(63) + ".").repeat(4) + "a",
        ).forEach { host ->
            assertFalse("'$host' should be rejected", ProfileValidator.isValidHost(ProfileValidator.normalizeHost(host)))
        }
    }

    @Test
    fun serverErrorUsesOneMessage() {
        assertEquals(
            mapOf(ProfileField.Server to ProfileError.ServerInvalid),
            ProfileValidator.validate(valid.copy(server = "not a host")),
        )
    }

    @Test
    fun portMustBeBetween1And65535() {
        listOf("", "0", "65536", "-1", "80a", "123456", " ").forEach { port ->
            assertEquals(
                "port '$port'",
                ProfileError.PortOutOfRange,
                ProfileValidator.validate(valid.copy(port = port))[ProfileField.Port],
            )
        }
        listOf("1", "80", "1080", " 3128 ", "65535").forEach { port ->
            assertFalse("port '$port'", ProfileField.Port in ProfileValidator.validate(valid.copy(port = port)))
        }
    }

    @Test
    fun signInNeedsAUsernameAndPasswordNeedsUsername() {
        val signIn = valid.copy(requiresSignIn = true)
        assertEquals(ProfileError.UsernameRequired, ProfileValidator.validate(signIn)[ProfileField.Username])
        assertEquals(
            ProfileError.PasswordWithoutUsername,
            ProfileValidator.validate(signIn.copy(password = "secret"))[ProfileField.Password],
        )
        assertTrue(ProfileValidator.validate(signIn.copy(username = "user", password = "secret")).isEmpty())
    }

    @Test
    fun credentialsAreIgnoredWhenSignInIsOff() {
        assertTrue(ProfileValidator.validate(valid.copy(username = "a:b", password = "x".repeat(300))).isEmpty())
    }

    @Test
    fun httpUsernamesCannotContainColon() {
        val http = valid.copy(protocol = ProxyProtocol.HTTP, requiresSignIn = true, username = "a:b", password = "p")
        assertEquals(ProfileError.UsernameHasColon, ProfileValidator.validate(http)[ProfileField.Username])
        val socks = http.copy(protocol = ProxyProtocol.SOCKS5)
        assertFalse(ProfileField.Username in ProfileValidator.validate(socks))
    }

    @Test
    fun socksCredentialsAreLimitedTo255Bytes() {
        val ok = valid.copy(requiresSignIn = true, username = "u".repeat(255), password = "p".repeat(255))
        assertTrue(ProfileValidator.validate(ok).isEmpty())
        // "é" is two bytes in UTF-8: 128 of them are 256 bytes.
        val long = ok.copy(username = "é".repeat(128), password = "é".repeat(128))
        val errors = ProfileValidator.validate(long)
        assertEquals(ProfileError.UsernameTooLong, errors[ProfileField.Username])
        assertEquals(ProfileError.PasswordTooLong, errors[ProfileField.Password])
        // HTTP Basic has no such limit.
        assertTrue(ProfileValidator.validate(long.copy(protocol = ProxyProtocol.HTTP)).isEmpty())
    }

    @Test
    fun tlsServerNameIsOptionalButMustBeAHostname() {
        val tls = valid.copy(useTls = true)
        assertTrue(ProfileValidator.validate(tls).isEmpty())
        assertTrue(ProfileValidator.validate(tls.copy(tlsServerName = "proxy.example.net")).isEmpty())
        assertEquals(
            ProfileError.TlsServerNameInvalid,
            ProfileValidator.validate(tls.copy(tlsServerName = "203.0.113.10"))[ProfileField.TlsServerName],
        )
        // Ignored while TLS is off.
        assertTrue(ProfileValidator.validate(valid.copy(tlsServerName = "not valid")).isEmpty())
    }

    @Test
    fun nameIsOptionalAndAtMost40Characters() {
        assertTrue(ProfileValidator.validate(valid.copy(name = "x".repeat(40))).isEmpty())
        assertEquals(
            ProfileError.NameTooLong,
            ProfileValidator.validate(valid.copy(name = "x".repeat(41)))[ProfileField.Name],
        )
    }

    @Test
    fun toProfileNormalisesTheInput() {
        val input = ProxyProfileInput(
            name = "  ",
            protocol = ProxyProtocol.HTTP,
            server = " [2001:db8::1] ",
            port = " 3128 ",
            requiresSignIn = true,
            username = "user",
            password = "secret",
            useTls = false,
            tlsServerName = "ignored.example",
        )
        val profile = input.toProfile(id = "p1", hasPassword = true)
        assertEquals("2001:db8::1", profile.host)
        assertEquals("2001:db8::1", profile.displayName)
        assertEquals("[2001:db8::1]:3128", profile.endpoint)
        assertEquals(3128, profile.port)
        assertEquals("user", profile.username)
        assertTrue(profile.hasPassword)
        assertEquals(null, profile.tlsServerName)
    }

    @Test
    fun formRoundTripNeverCarriesThePassword() {
        val profile = ProxyProfile(
            id = "p1", name = "Office", protocol = ProxyProtocol.SOCKS5, host = "203.0.113.10", port = 1080,
            username = "user", hasPassword = true, useTls = true, tlsServerName = "proxy.example.com",
        )
        val input = ProxyProfileInput.from(profile)
        assertEquals("", input.password)
        assertTrue(input.requiresSignIn)
        assertEquals(profile, input.toProfile(id = "p1", hasPassword = true))
    }
}
