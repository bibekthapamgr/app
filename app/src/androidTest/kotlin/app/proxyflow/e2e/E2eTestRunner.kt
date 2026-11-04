package app.proxyflow.e2e

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import app.proxyflow.AppContainer
import app.proxyflow.ProxyFlowApplication
import app.proxyflow.core.engine.AndroidEnginePlatform
import app.proxyflow.core.vpn.RetryPolicy
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking

/**
 * The app's instrumentation runner (app/build.gradle.kts). The app under test is the app as
 * installed, except that [TestProxyFlowApplication] builds its container with [TestTrust], so a
 * test can have certificates from the lab's CA trusted for TLS proxies (ADR-0011), and with
 * [TestRetry], so a test can reconnect with waits of its own (ADR-0012). Until a test sets them, and
 * in the app as installed always, the platform's trust manager and the default retry policy decide.
 */
class E2eTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestProxyFlowApplication::class.java.name, context)
}

class TestProxyFlowApplication : ProxyFlowApplication() {
    override fun createContainer(): AppContainer = AppContainer(this, proxyTrust = TestTrust::trustManager, retry = { TestRetry.policy })

    /** The end-to-end tests start at Home, as for a user who has used the app before: the first-launch pages are done. */
    override fun onCreate() {
        super.onCreate()
        runBlocking { container.settingsRepository.setOnboardingCompleted() }
    }
}

/** How the app reconnects on its own during a test (ADR-0012). */
object TestRetry {
    /** Read each time a reconnection starts; the default is the app's own policy. */
    @Volatile var policy: RetryPolicy = RetryPolicy()
}

/** Which certificates the app trusts for TLS proxies during a test (ADR-0011). */
object TestTrust {
    /** The lab's CA: when set, TLS proxies are checked against it alone; null (the default) is the platform as shipped. */
    @Volatile var labCa: X509Certificate? = null

    /**
     * Asked by the engine once per proxy check and per engine start. With [labCa], still the platform's own
     * trust manager (the same implementation, the same checks), only with the lab's CA as its one anchor.
     */
    fun trustManager(): X509TrustManager = labCa?.let(::trusting) ?: AndroidEnginePlatform.systemTrustManager()

    private fun trusting(ca: X509Certificate): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("proxyflow-lab-ca", ca)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
