package app.proxyflow.core.testing

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Adds a description of the screen to the message of a failed assertion, so a
 * UI test that fails in CI can be understood from the test report alone.
 *
 * Declare it after (inside) the Compose rule, so the screen still exists when
 * it is described:
 *
 * ```
 * @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
 * @get:Rule(order = 1) val dump = DumpOnFailureRule { compose.onAllNodes(isRoot()).printToString() }
 * ```
 */
class DumpOnFailureRule(private val describeScreen: () -> String) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                base.evaluate()
            } catch (failure: AssertionError) {
                val screen = runCatching(describeScreen).getOrElse { "(could not describe the screen: $it)" }
                throw AssertionError("${failure.message}\n--- screen ---\n$screen", failure)
            }
        }
    }
}
