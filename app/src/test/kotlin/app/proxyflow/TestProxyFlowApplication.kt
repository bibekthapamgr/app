package app.proxyflow

import app.proxyflow.core.testing.SoftwareSecretKeyStore

/**
 * The app for Robolectric tests (app/src/test/resources/robolectric.properties):
 * everything real (Room, DataStore, screens) except the credential keys, which
 * are software keys because the JVM has no Android Keystore. The Keystore
 * itself is tested on the emulator (core/data androidTest).
 */
class TestProxyFlowApplication : ProxyFlowApplication() {
    override fun createContainer(): AppContainer = AppContainer(this, credentialKeys = SoftwareSecretKeyStore())
}
