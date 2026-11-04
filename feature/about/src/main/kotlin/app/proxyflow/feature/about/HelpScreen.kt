package app.proxyflow.feature.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** Help's topics as links name them: "Learn more" on Home opens [CERTIFICATES] (docs/m1/04 §4, `TLS_UNTRUSTED`). */
object HelpTopics {
    const val START = "start"
    const val ERRORS = "errors"
    const val CERTIFICATES = "certificates"
    const val KILL_SWITCH = "kill_switch"
    const val FEEDBACK = "feedback"
}

/**
 * Help (M9, ADR-0014): setting up, what the errors mean, certificates, what goes through the proxy, the kill switches,
 * logs, what happens when Android stops ProxyFlow, and how to get help without any server to report to. Opened at
 * [topic] when a link names one.
 */
@Composable
fun HelpScreen(onBack: () -> Unit, modifier: Modifier = Modifier, topic: String? = null, onOpenKillSwitchGuide: () -> Unit = {}) {
    DocumentScreen(
        title = stringResource(R.string.help_title),
        sections = HELP_SECTIONS,
        onBack = onBack,
        modifier = modifier,
        startSection = topic,
        onAction = { id -> if (id == HelpTopics.KILL_SWITCH) onOpenKillSwitchGuide() },
    )
}

internal val HELP_SECTIONS = listOf(
    DocumentSection(
        id = HelpTopics.START,
        title = R.string.help_start_title,
        paragraphs = listOf(R.string.help_start_need, R.string.help_start_add, R.string.help_start_connect),
    ),
    DocumentSection(
        id = HelpTopics.ERRORS,
        title = R.string.help_errors_title,
        paragraphs = listOf(
            R.string.help_errors_home,
            R.string.help_errors_reach,
            R.string.help_errors_network_sign_in,
            R.string.help_errors_sign_in,
            R.string.help_errors_protocol,
            R.string.help_errors_blocked,
        ),
    ),
    DocumentSection(
        id = HelpTopics.CERTIFICATES,
        title = R.string.help_certificates_title,
        paragraphs = listOf(R.string.help_certificates_what, R.string.help_certificates_trusted, R.string.help_certificates_fix),
    ),
    DocumentSection(
        id = "carry",
        title = R.string.help_carry_title,
        paragraphs = listOf(R.string.help_carry_tcp, R.string.help_carry_blocked, R.string.help_carry_outside),
    ),
    DocumentSection(
        id = HelpTopics.KILL_SWITCH,
        title = R.string.help_kill_switch_title,
        paragraphs = listOf(R.string.help_kill_switch_app, R.string.help_kill_switch_system),
        actionLabel = R.string.help_kill_switch_open,
    ),
    DocumentSection(id = "logs", title = R.string.help_logs_title, paragraphs = listOf(R.string.help_logs_body)),
    DocumentSection(id = "background", title = R.string.help_background_title, paragraphs = listOf(R.string.help_background_body)),
    DocumentSection(id = HelpTopics.FEEDBACK, title = R.string.help_feedback_title, paragraphs = listOf(R.string.help_feedback_body)),
)
