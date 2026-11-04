package app.proxyflow.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.proxyflow.core.designsystem.R
import app.proxyflow.core.model.ConnectionError
import app.proxyflow.core.model.ProxyProfile

/**
 * The error catalogue's title for a check of [profile] that ended with [error] (docs/m1/04 §4): Home's when connecting and
 * the proxy form's when testing (M9), so both name a failure the same way. A code no proxy check ends with is this phone's
 * own failure, `INTERNAL`.
 */
@Composable
fun proxyCheckErrorTitle(error: ConnectionError, profile: ProxyProfile): String = when (error.code) {
    "PROXY_UNREACHABLE" -> stringResource(R.string.proxy_error_unreachable_title)
    "PROXY_DNS_FAILED" -> stringResource(R.string.proxy_error_dns_title)
    "AUTH_REQUIRED" -> stringResource(R.string.proxy_error_auth_required_title)
    "AUTH_FAILED" -> stringResource(R.string.proxy_error_auth_failed_title)
    "TLS_UNTRUSTED" -> stringResource(R.string.proxy_error_tls_untrusted_title)
    "TLS_HOSTNAME" -> stringResource(R.string.proxy_error_tls_hostname_title)
    "TLS_EXPIRED" -> stringResource(R.string.proxy_error_tls_expired_title)
    "TLS_HANDSHAKE" -> stringResource(R.string.proxy_error_tls_handshake_title)
    "PROTOCOL_ERROR" -> stringResource(R.string.proxy_error_protocol_title, profile.protocol.name)
    "HANDSHAKE_TIMEOUT" -> stringResource(R.string.proxy_error_timeout_title)
    "TARGET_NOT_ALLOWED" -> stringResource(R.string.proxy_error_refused_title)
    "TARGET_UNREACHABLE", "TARGET_REFUSED" -> stringResource(R.string.proxy_error_unreachable_target_title)
    "PROXY_GENERAL_FAILURE" -> stringResource(R.string.proxy_error_proxy_failure_title)
    "TARGET_UNSUPPORTED" -> stringResource(R.string.proxy_error_unsupported_title)
    ConnectionError.CREDENTIAL_UNREADABLE -> stringResource(R.string.proxy_error_credentials_title)
    else -> stringResource(R.string.proxy_error_internal_title)
}

/** The catalogue's message for [error], filled in with [profile]'s server, as [proxyCheckErrorTitle]. */
@Composable
fun proxyCheckErrorMessage(error: ConnectionError, profile: ProxyProfile): String = when (error.code) {
    "PROXY_UNREACHABLE" -> stringResource(R.string.proxy_error_unreachable_message, profile.endpoint)
    "PROXY_DNS_FAILED" -> stringResource(R.string.proxy_error_dns_message, profile.host)
    "AUTH_REQUIRED" -> stringResource(R.string.proxy_error_auth_required_message)
    "AUTH_FAILED" -> stringResource(
        if (error.unsupportedSignIn) R.string.proxy_error_auth_unsupported_message else R.string.proxy_error_auth_failed_message,
    )
    "TLS_UNTRUSTED" -> stringResource(R.string.proxy_error_tls_untrusted_message)
    "TLS_HOSTNAME" -> stringResource(R.string.proxy_error_tls_hostname_message, profile.tlsServerName ?: profile.host)
    "TLS_EXPIRED" -> stringResource(R.string.proxy_error_tls_expired_message)
    "TLS_HANDSHAKE" -> stringResource(R.string.proxy_error_tls_handshake_message)
    "PROTOCOL_ERROR" -> stringResource(
        if (error.noConnect) R.string.proxy_error_no_connect_message else R.string.proxy_error_protocol_message,
    )
    "HANDSHAKE_TIMEOUT" -> stringResource(R.string.proxy_error_timeout_message)
    "TARGET_NOT_ALLOWED" -> stringResource(R.string.proxy_error_refused_message, profile.endpoint)
    "TARGET_UNREACHABLE", "TARGET_REFUSED" -> stringResource(R.string.proxy_error_unreachable_target_message, profile.endpoint)
    "PROXY_GENERAL_FAILURE" -> stringResource(R.string.proxy_error_proxy_failure_message, profile.endpoint)
    "TARGET_UNSUPPORTED" -> stringResource(R.string.proxy_error_unsupported_message)
    ConnectionError.CREDENTIAL_UNREADABLE -> stringResource(R.string.proxy_error_credentials_message)
    else -> stringResource(R.string.proxy_error_internal_message)
}
