package io.github.spectordev9.opencode.core.common

enum class TimeoutClass {
    HEALTH,
    LIST_GET,
    FILE_READ,
    PROMPT_ASYNC,
    ABORT,
    SSE_IDLE,
    GIT_NETWORK,
    INTERACTIVE,
}

data class TimeoutPolicy(
    val timeoutClass: TimeoutClass,
    val timeoutMillis: Long?,
)

object OperationTimeouts {
    const val HEALTH_MILLIS: Long = 2_000
    const val LIST_GET_MILLIS: Long = 10_000
    const val FILE_READ_MILLIS: Long = 15_000
    const val PROMPT_ASYNC_MILLIS: Long = 10_000
    const val ABORT_MILLIS: Long = 5_000
    const val SSE_IDLE_MILLIS: Long = 60_000
    const val GIT_NETWORK_MILLIS: Long = 120_000

    fun defaultPolicy(timeoutClass: TimeoutClass): TimeoutPolicy = when (timeoutClass) {
        TimeoutClass.HEALTH -> TimeoutPolicy(timeoutClass, HEALTH_MILLIS)
        TimeoutClass.LIST_GET -> TimeoutPolicy(timeoutClass, LIST_GET_MILLIS)
        TimeoutClass.FILE_READ -> TimeoutPolicy(timeoutClass, FILE_READ_MILLIS)
        TimeoutClass.PROMPT_ASYNC -> TimeoutPolicy(timeoutClass, PROMPT_ASYNC_MILLIS)
        TimeoutClass.ABORT -> TimeoutPolicy(timeoutClass, ABORT_MILLIS)
        TimeoutClass.SSE_IDLE -> TimeoutPolicy(timeoutClass, SSE_IDLE_MILLIS)
        TimeoutClass.GIT_NETWORK -> TimeoutPolicy(timeoutClass, GIT_NETWORK_MILLIS)
        TimeoutClass.INTERACTIVE -> TimeoutPolicy(timeoutClass, null)
    }
}
