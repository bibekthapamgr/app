package app.proxyflow

import app.proxyflow.core.testing.SoftwareSecretKeyStore
import kotlinx.coroutines.runBlocking

/**
 * The app for Robolectric tests (app/src/test/resources/robolectric.properties):
 * everything real (Room, DataStore, screens) except the credential keys, which
 * are software keys because the JVM has no Android Keystore. The Keystore
 * itself is tested on the emulator (core/data androidTest).
 *
 * The first-launch pages are marked as gone through before any activity starts, as for a user who has used the app
 * before, unless a test asks for a first launch ([firstLaunch], set before the test's app is created).
 */
class TestProxyFlowApplication : ProxyFlowApplication() {
    override fun createContainer(): AppContainer = AppContainer(this, credentialKeys = SoftwareSecretKeyStore())

    override fun onCreate() {
        super.onCreate()
        if (!firstLaunch) runBlocking { container.settingsRepository.setOnboardingCompleted() }
    }

    companion object {
        /** True while a test class wants the app as at its first launch (OnboardingFlowTest sets it in @BeforeClass). */
        @Volatile var firstLaunch = false
    }
}
