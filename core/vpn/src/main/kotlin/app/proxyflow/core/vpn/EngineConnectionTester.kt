package app.proxyflow.core.vpn

import app.proxyflow.core.data.ConnectionTester
import app.proxyflow.core.data.CredentialsRead
import app.proxyflow.core.data.ProfileRepository
import app.proxyflow.core.engine.EngineSecret
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ConnectionTestResult
import app.proxyflow.core.model.ProxyProfile
import app.proxyflow.core.model.ProxyProfileInput
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Test connection" on the engine (boards D5–D7, M9, ADR-0014): the very check connecting makes first (ADR-0010 §4) — the
 * proxy's sign-in and one tunnel to [target] opened and closed again, nothing sent through it — without the VPN, so the
 * phone's network is never changed. ProxyFlow's own connections are outside its VPN (`addDisallowedApplication`), so a
 * test made while connected reaches the tested proxy directly, never through the connected one. Credentials go only to
 * the proxy tested: typed ones, or the saved ones under the rule of saving, never to another server.
 *
 * @param guards one per test, so a test that is cancelled (the form was edited, the screen left) stops its connection to
 *   the proxy at once instead of running to its timeout; the app's shuts the test's sockets down ([ShutdownGuard]). Only
 *   a lookup of the proxy's name the engine is still making can't be cut short: it ends by itself.
 */
class EngineConnectionTester(
    private val profiles: ProfileRepository,
    private val engine: TunnelEngine,
    private val clock: () -> Long = System::currentTimeMillis,
    private val target: String = VpnSession.PREFLIGHT_TARGET,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val guards: () -> SocketGuard = ::ShutdownGuard,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConnectionTester {
    override suspend fun test(profileId: String?, input: ProxyProfileInput): ConnectionTestResult {
        val profile = try {
            input.toProfile(profileId ?: UNSAVED_ID, createdAtMillis = 0, updatedAtMillis = 0)
        } catch (e: NumberFormatException) {
            // The form checks the port before testing; a form that skipped it tested nothing.
            return failed(ConnectionError.INTERNAL, "the form's port is not a number")
        }
        // The saved profile this test describes when it tests it as saved — its server and sign-in, its saved password —
        // so the result is kept with it (board C2). Read before its credentials: a save in between isn't kept (recordTest).
        val saved = profileId?.let { profiles.get(it) }?.takeIf { it.sameServerAs(profile) }
        val secret = when {
            !input.requiresSignIn -> null
            input.password.isNotEmpty() -> secretOf(input.username.toCharArray(), input.password.toCharArray())
                ?: return failed(ConnectionError.CREDENTIAL_UNREADABLE)
            profileId == null -> return failed(AUTH_REQUIRED)
            else -> when (val read = profiles.readCredentials(profileId, profile)) {
                is CredentialsRead.Available -> {
                    // The rule of saving: a saved password goes only with its own username, to the server it was saved for.
                    if (!read.username.holds(input.username)) {
                        read.wipe()
                        return failed(AUTH_REQUIRED)
                    }
                    secretOf(read.username, read.password) ?: return keepIf(saved, failed(ConnectionError.CREDENTIAL_UNREADABLE))
                }
                CredentialsRead.Unreadable -> return keepIf(saved, failed(ConnectionError.CREDENTIAL_UNREADABLE))
                CredentialsRead.TemporarilyUnavailable -> return failed(ConnectionError.INTERNAL, KEYSTORE_UNAVAILABLE)
                // Another server or sign-in than the saved one, or no saved profile: its password can't be used here.
                CredentialsRead.ProfileChanged, CredentialsRead.NoSuchProfile, CredentialsRead.NotRequired -> return failed(AUTH_REQUIRED)
            }
        }
        val result = probe(profile, secret)
        // A password typed into the form was tested, not the saved one: kept only once it is saved (the form does that).
        return if (input.requiresSignIn && input.password.isNotEmpty()) result else keepIf(saved, result)
    }

    override suspend fun testSaved(profileId: String): ConnectionTestResult? {
        val profile = profiles.get(profileId) ?: return null
        val secret = when (val read = profiles.readCredentials(profileId, profile)) {
            CredentialsRead.NotRequired -> null
            is CredentialsRead.Available -> secretOf(read.username, read.password)
                ?: return keep(profile, failed(ConnectionError.CREDENTIAL_UNREADABLE))
            CredentialsRead.Unreadable -> return keep(profile, failed(ConnectionError.CREDENTIAL_UNREADABLE))
            CredentialsRead.TemporarilyUnavailable -> return failed(ConnectionError.INTERNAL, KEYSTORE_UNAVAILABLE)
            // Saved again or deleted since it was read: nothing tested, and nothing kept.
            CredentialsRead.ProfileChanged, CredentialsRead.NoSuchProfile -> return null
        }
        return keep(profile, probe(profile, secret))
    }

    /** [keep] when the test was of the saved [profile]; otherwise only [result]. */
    private suspend fun keepIf(profile: ProxyProfile?, result: ConnectionTestResult): ConnectionTestResult =
        if (profile == null) result else keep(profile, result)

    /** Keeps [result] on [profile] while it is as tested; a failure of this phone's own (the Keystore, the engine) isn't kept. */
    private suspend fun keep(profile: ProxyProfile, result: ConnectionTestResult): ConnectionTestResult {
        if (result is ConnectionTestResult.Failed && result.code == ConnectionError.INTERNAL) return result
        try {
            profiles.recordTest(profile.id, profile, result.asProfileTest(clock()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage failed: the result is still shown, only not kept.
        }
        return result
    }

    /**
     * The probe, off the caller's thread. Cancelled, the test's sockets are shut down so the engine returns as soon as it
     * is using one (as a cancelled pre-flight check does, VpnSession) — a lookup of the proxy's name it is still making
     * ends by itself first; the secret is consumed or closed either way.
     */
    private suspend fun probe(profile: ProxyProfile, secret: EngineSecret?): ConnectionTestResult {
        val guard = guards()
        try {
            val answer = coroutineScope {
                val test = coroutineContext.job
                val aborter = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (test.isCancelled) guard.abort()
                    }
                }
                try {
                    withContext(dispatcher) {
                        try {
                            engine.probe(profile, secret, target, timeoutMillis) { fd ->
                                guard.track(fd)
                                true // ProxyFlow's own sockets are outside its VPN: nothing to protect them from
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            PreflightAnswer(ok = false, code = ConnectionError.INTERNAL, detail = "unexpected ${e.javaClass.simpleName}")
                        } catch (e: LinkageError) {
                            PreflightAnswer(ok = false, code = ConnectionError.INTERNAL, detail = "the packet engine could not be loaded")
                        }
                    }
                } finally {
                    aborter.cancel()
                }
            }
            return if (answer.passes) {
                ConnectionTestResult.Passed(
                    answer.latencyMillis.coerceAtLeast(1),
                )
            } else {
                ConnectionTestResult.Failed(answer.error)
            }
        } finally {
            secret?.close() // the probe consumed it already; this covers a probe that never ran
            guard.close()
        }
    }

    /** The engine's encoding of a sign-in; null for one it can't carry (broken text, an over-long field). Wipes both arrays. */
    private fun secretOf(username: CharArray, password: CharArray): EngineSecret? = try {
        EngineSecret.of(username, password)
    } catch (e: IllegalArgumentException) {
        null
    } finally {
        username.fill('\u0000')
        password.fill('\u0000')
    }

    /** A failure found before the engine was asked ([ConnectionTestResult.Failed.fromEngine] false). */
    private fun failed(code: String, detail: String? = null) =
        ConnectionTestResult.Failed(ConnectionError(code, detail), fromEngine = false)

    /** Whether [this] decrypted array holds exactly [text], compared without copying it into a string that can't be wiped. */
    private fun CharArray.holds(text: String): Boolean = size == text.length && indices.all { this[it] == text[it] }

    companion object {
        /** The whole test, as the pre-flight check: TCP, TLS, the sign-in and the tunnel (VpnSession.Timing). */
        const val TIMEOUT_MILLIS = 15_000L

        /** The id of a profile tested before it was ever saved; no stored profile has it. */
        const val UNSAVED_ID = "unsaved"

        private const val AUTH_REQUIRED = "AUTH_REQUIRED"
        private const val KEYSTORE_UNAVAILABLE = "the Android Keystore is not available right now"
    }
}
