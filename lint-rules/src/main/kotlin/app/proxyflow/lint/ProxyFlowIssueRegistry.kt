package app.proxyflow.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** ProxyFlow's own Lint checks, applied to the app and every module it uses. */
class ProxyFlowIssueRegistry : IssueRegistry() {
    override val issues: List<Issue> = listOf(LogSecretDetector.ISSUE, LogSecretDetector.EXCEPTION_ISSUE)

    override val api: Int = CURRENT_API

    override val minApi: Int = MIN_API

    override val vendor: Vendor = Vendor(vendorName = "ProxyFlow", identifier = "app.proxyflow.lint")

    private companion object {
        /** The oldest Lint API these checks run on. */
        const val MIN_API = 10
    }
}
