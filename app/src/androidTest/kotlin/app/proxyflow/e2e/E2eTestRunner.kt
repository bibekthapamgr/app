package app.proxyflow.e2e

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import app.proxyflow.AppContainer
import app.proxyflow.ProxyFlowApplication
import app.proxyflow.core.engine.AndroidEnginePlatform
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The app's instrumentation runner (app/build.gradle.kts). The app under test is the app as
 * installed, except that [TestProxyFlowApplication] builds its container with [TestTrust], so a
 * test can have certificates from the lab's CA trusted for TLS proxies (ADR-0011). Until a test
 * sets [TestTrust.labCa], and in the app as installed always, the platform's trust manager decides.
 */
class E2eTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestProxyFlowApplication::class.java.name, context)
}

class TestProxyFlowApplication : ProxyFlowApplication() {
    override fun createContainer(): AppContainer = AppContainer(this, proxyTrust = TestTrust::trustManager)
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
