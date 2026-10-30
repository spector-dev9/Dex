package io.github.spectordev9.opencode.connection

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException

enum class ErrorAction {
    RETRY,
    REPAIR,
    SIGN_IN,
}

sealed class ConnectionError(
    val userMessage: String,
    val detail: String,
    val action: ErrorAction,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

class DnsFailure(detail: String, cause: Throwable? = null) : ConnectionError(
    userMessage = "The server address could not be resolved.",
    detail = detail,
    action = ErrorAction.RETRY,
    cause = cause,
)

class ConnectionRefused(detail: String, cause: Throwable? = null) : ConnectionError(
    userMessage = "The local OpenCode server is not accepting connections.",
    detail = detail,
    action = ErrorAction.REPAIR,
    cause = cause,
)

class Timeout(detail: String, cause: Throwable? = null) : ConnectionError(
    userMessage = "The OpenCode server took too long to respond.",
    detail = detail,
    action = ErrorAction.RETRY,
    cause = cause,
)

class CleartextBlocked(detail: String, cause: Throwable? = null) : ConnectionError(
    userMessage = "Cleartext access to the local server is blocked.",
    detail = detail,
    action = ErrorAction.REPAIR,
    cause = cause,
)

class Unauthorized(detail: String = "server rejected the launch credentials") : ConnectionError(
    userMessage = "The local server rejected the connection credentials.",
    detail = detail,
    action = ErrorAction.REPAIR,
)

class NotFound(detail: String) : ConnectionError(
    userMessage = "The requested OpenCode resource was not found.",
    detail = detail,
    action = ErrorAction.RETRY,
)

class ServerError(
    val statusCode: Int,
    detail: String,
) : ConnectionError(
    userMessage = "The OpenCode server returned an error.",
    detail = detail,
    action = ErrorAction.RETRY,
)

class ParseError(detail: String, cause: Throwable? = null) : ConnectionError(
    userMessage = "The OpenCode server returned an unreadable response.",
    detail = detail,
    action = ErrorAction.REPAIR,
    cause = cause,
)

class NoProviderConfigured(detail: String = "no providers are configured") : ConnectionError(
    userMessage = "No AI provider is configured.",
    detail = detail,
    action = ErrorAction.SIGN_IN,
)

class ServerNotReady(detail: String) : ConnectionError(
    userMessage = "The local OpenCode server is still starting.",
    detail = detail,
    action = ErrorAction.RETRY,
)

object ErrorMapping {
    fun fromThrowable(error: Throwable): ConnectionError = when (error) {
        is ConnectionError -> error
        is UnknownHostException -> DnsFailure(redact(error.message ?: "unknown host"), error)
        is ConnectException -> ConnectionRefused(redact(error.message ?: "connection refused"), error)
        is SocketTimeoutException -> Timeout(redact(error.message ?: "socket timeout"), error)
        is InterruptedIOException -> Timeout(redact(error.message ?: "request interrupted"), error)
        is SSLException -> CleartextBlocked(redact(error.message ?: "TLS failure"), error)
        is SerializationException -> ParseError(redact(error.message ?: "invalid JSON"), error)
        is IllegalArgumentException -> ParseError(redact(error.message ?: "invalid payload"), error)
        else -> {
            val message = error.message.orEmpty()
            if (message.contains("Cleartext", ignoreCase = true)) {
                CleartextBlocked(redact(message), error)
            } else {
                ConnectionRefused(redact(message.ifBlank { error::class.java.simpleName }), error)
            }
        }
    }

    fun fromStatus(statusCode: Int, body: String): ConnectionError = when (statusCode) {
        401, 403 -> Unauthorized("HTTP $statusCode")
        404 -> NotFound("HTTP 404")
        in 500..599 -> ServerError(statusCode, "HTTP $statusCode: ${redact(body.take(300))}")
        else -> ServerError(statusCode, "HTTP $statusCode: ${redact(body.take(300))}")
    }

    fun redact(value: String): String =
        value
            .replace(Regex("(?i)(authorization|password|token|secret|key)=([^&\\s]+)"), "$1=<redacted>")
            .replace(Regex("(?i)(Bearer|Basic)\\s+[A-Za-z0-9+/=_-]+"), "$1 <redacted>")
}
