package io.github.spectordev9.opencode.core.common

enum class ErrorCode(val retryable: Boolean) {
    E_RUNTIME_DOWN(true),
    E_AUTH(false),
    E_PATH_DENIED(false),
    E_CONFLICT(false),
    E_TOO_LARGE(false),
    E_TIMEOUT(true),
    E_UNSUPPORTED_VERSION(false),
    E_UPSTREAM(true),
    E_CANCELLED(false),
}

data class ApiError(
    val code: ErrorCode,
    val message: String? = null,
    val upstreamStatus: Int? = null,
) {
    val retryable: Boolean
        get() = code.retryable
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    data class Failure(val error: ApiError) : ApiResult<Nothing>

    val isSuccess: Boolean
        get() = this is Success<*>

    val isFailure: Boolean
        get() = this is Failure
}
