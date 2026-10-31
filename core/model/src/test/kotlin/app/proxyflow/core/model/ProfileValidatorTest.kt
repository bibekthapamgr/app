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
            // Digits from other scripts: Kotlin calls them digits, but they are not an address.
            "\u0661\u0669\u0662.\u0661\u0666\u0668.\u0660.\u0661", "::\u0661", "\uFF10\uFF11.0.0.1",
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
        listOf("", "0", "65536", "-1", "80a", "123456", " ", "\u0661\u0660\u0668\u0660").forEach { port ->
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
        val profile = input.toProfile(id = "p1", createdAtMillis = 1, updatedAtMillis = 2)
        assertEquals("2001:db8::1", profile.host)
        assertEquals("an empty name is stored empty, not as the server", "", profile.name)
        assertEquals("2001:db8::1", profile.displayName)
        assertEquals("[2001:db8::1]:3128", profile.endpoint)
        assertEquals(3128, profile.port)
        assertTrue(profile.requiresSignIn)
        assertEquals(TlsMode.None, profile.tlsMode)
        assertEquals("a server name only matters with TLS", null, profile.tlsServerName)
        assertEquals(1L, profile.createdAtMillis)
        assertEquals(2L, profile.updatedAtMillis)
    }

    @Test
    fun formRoundTripCarriesTheUsernameButNeverThePassword() {
        val profile = ProxyProfile(
            id = "p1", name = "Office", protocol = ProxyProtocol.SOCKS5, host = "203.0.113.10", port = 1080,
            tlsMode = TlsMode.Tls, tlsServerName = "proxy.example.com", requiresSignIn = true,
            createdAtMillis = 10, updatedAtMillis = 20,
        )
        val input = ProxyProfileInput.from(profile, username = "user")
        assertEquals("user", input.username)
        assertEquals("", input.password)
        assertTrue(input.requiresSignIn)
        assertTrue(input.useTls)
        assertEquals(profile, input.toProfile(id = "p1", createdAtMillis = 10, updatedAtMillis = 20))
        assertEquals("no username without sign-in", "", ProxyProfileInput.from(profile.copy(requiresSignIn = false), "user").username)
    }

    @Test
    fun controlCharactersAreRefusedInTheNameAndTheCredentials() {
        val signIn = valid.copy(requiresSignIn = true, username = "user", password = "secret")
        assertEquals(ProfileError.ControlCharacters, ProfileValidator.validate(valid.copy(name = "Office\n"))[ProfileField.Name])
        assertEquals(ProfileError.ControlCharacters, ProfileValidator.validate(signIn.copy(username = "us\ter"))[ProfileField.Username])
        assertEquals(ProfileError.ControlCharacters, ProfileValidator.validate(signIn.copy(password = "secret\r\n"))[ProfileField.Password])
        assertEquals(ProfileError.ControlCharacters, ProfileValidator.validate(signIn.copy(password = "a\u2028b"))[ProfileField.Password])
        assertTrue("spaces and symbols are fine", ProfileValidator.validate(signIn.copy(password = "p a$\$ wörd!")).isEmpty())
        // Half of an emoji (an unpaired surrogate) is broken text no storage or proxy keeps intact.
        assertEquals(ProfileError.ControlCharacters, ProfileValidator.validate(valid.copy(name = "Office \uD83D"))[ProfileField.Name])
        assertEquals(ProfileError.ControlCharacters, ProfileValidator.validate(signIn.copy(password = "pw\uDE00"))[ProfileField.Password])
        assertTrue(
            "whole emoji are fine",
            ProfileValidator.validate(signIn.copy(name = "Office \uD83D\uDE00", password = "pw\uD83D\uDE00")).isEmpty(),
        )
        assertEquals(ProfileError.ControlCharacters, ProfileValidator.nameError("a\u0007b"))
        assertEquals(null, ProfileValidator.nameError("Office (copy)"))
    }

    @Test
    fun httpCredentialsAreLimitedTo1024Bytes() {
        val http = valid.copy(protocol = ProxyProtocol.HTTP, requiresSignIn = true, username = "user", password = "secret")
        val limit = ProfileValidator.MAX_HTTP_CREDENTIAL_BYTES
        assertTrue(ProfileValidator.validate(http.copy(username = "u".repeat(limit), password = "p".repeat(limit))).isEmpty())
        assertEquals(
            ProfileError.UsernameTooLong,
            ProfileValidator.validate(http.copy(username = "u".repeat(limit + 1)))[ProfileField.Username],
        )
        assertEquals(
            ProfileError.PasswordTooLong,
            ProfileValidator.validate(http.copy(password = "p".repeat(limit + 1)))[ProfileField.Password],
        )
        assertEquals(
            "bytes, not characters",
            ProfileError.PasswordTooLong,
            ProfileValidator.validate(http.copy(password = "ö".repeat(limit / 2 + 1)))[ProfileField.Password],
        )
    }

    @Test
    fun copyNamesStayUniqueAndShortEnough() {
        assertEquals("Office (copy)", ProfileNames.copyOf("Office", taken = listOf("Office")))
        assertEquals("Office (copy 2)", ProfileNames.copyOf("Office", taken = listOf("Office", "Office (copy)")))
        val long = "x".repeat(ProfileValidator.MAX_NAME_LENGTH)
        val copy = ProfileNames.copyOf(long, taken = listOf(long))
        assertTrue(copy, copy.length <= ProfileValidator.MAX_NAME_LENGTH)
        assertTrue(copy, copy.endsWith(" (copy)"))
        val custom = ProfileNames.copyOf("Office", taken = emptyList()) { base, n -> "$base – kopi $n" }
        assertEquals("Office – kopi 1", custom)

        // Shortening never cuts an emoji in half.
        val emoji = "x".repeat(32) + "\uD83D\uDE00" + "yyyyyy"
        val emojiCopy = ProfileNames.copyOf(emoji, taken = listOf(emoji))
        assertEquals(null, ProfileValidator.nameError(emojiCopy))
        assertEquals("x".repeat(32) + " (copy)", emojiCopy)

        // A translated pattern that ignores the number still ends, with plain numbering.
        val stuck = ProfileNames.copyOf("Office", taken = listOf("Office", "Office copy")) { base, _ -> "$base copy" }
        assertEquals("Office (1)", stuck)

        assertEquals("x".repeat(39), ProfileNames.truncate("x".repeat(39) + "\uD83D\uDE00", 40))
        assertEquals("ab", ProfileNames.truncate("ab", 40))
    }

    @Test
    fun signInNeedsAPasswordUnlessTheSavedOneIsKept() {
        val signIn = valid.copy(requiresSignIn = true, username = "user")
        assertEquals(ProfileError.PasswordRequired, ProfileValidator.validate(signIn)[ProfileField.Password])
        assertTrue(ProfileValidator.validate(signIn, savedPasswordKept = true).isEmpty())
        assertTrue(ProfileValidator.validate(signIn.copy(password = "secret")).isEmpty())
    }

    @Test
    fun aSavedPasswordOnlyFollowsTheSameProxyAccount() {
        val saved = valid.copy(requiresSignIn = true, username = "user", useTls = true, tlsServerName = "tls.example.com")
        val sameAccount = saved.copy(server = " PROXY.example.com ", port = " 1080 ", tlsServerName = "TLS.example.com")
        assertTrue(sameAccount.sameCredentialTargetAs(saved))
        listOf(
            saved.copy(server = "other.example.com"),
            saved.copy(port = "1081"),
            saved.copy(protocol = ProxyProtocol.HTTP),
            saved.copy(username = "someone-else"),
            saved.copy(useTls = false),
            saved.copy(tlsServerName = "other.example.com"),
            saved.copy(requiresSignIn = false),
        ).forEach { changed -> assertFalse("$changed", changed.sameCredentialTargetAs(saved)) }
    }

    @Test
    fun credentialsNeverAppearInToString() {
        val input = valid.copy(requiresSignIn = true, username = "alice", password = "s3cret-pw")
        val text = input.toString() + " " + input.toProfile(id = "p1", createdAtMillis = 0, updatedAtMillis = 0).toString()
        assertFalse(text, "alice" in text)
        assertFalse(text, "s3cret-pw" in text)
        assertTrue(text, "<redacted>" in text)
    }
}
