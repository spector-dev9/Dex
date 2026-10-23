package io.github.spectordev9.opencode.core.supervisor

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.RequiresG0
import kotlinx.coroutines.flow.StateFlow

enum class ServerStateStatus {
    STOPPED,
    STARTING,
    HEALTHY,
    DEGRADED,
    BACKOFF,
    CRASH_LOOP,
}

data class ServerEndpoint(
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65_535) { "port must be between 1 and 65535" }
    }
}

data class ServerState(
    val status: ServerStateStatus,
    val endpoint: ServerEndpoint? = null,
    val version: String? = null,
    val backoffUntilEpochMillis: Long? = null,
    val reason: String? = null,
)

data class ServerHealth(
    val version: String,
    val endpoint: ServerEndpoint,
)

@RequiresG0("supervisor timeouts and crash-loop limits require device evidence")
data class SupervisorConfig(
    val startupTimeoutMillis: Long = 10_000,
    val healthTimeoutMillis: Long = 2_000,
    val stopGraceMillis: Long = 3_000,
    val initialBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 15_000,
    val crashLoopThreshold: Int = 5,
    val crashLoopWindowMillis: Long = 300_000,
) {
    init {
        require(startupTimeoutMillis > 0) { "startupTimeoutMillis must be positive" }
        require(healthTimeoutMillis > 0) { "healthTimeoutMillis must be positive" }
        require(stopGraceMillis >= 0) { "stopGraceMillis must not be negative" }
        require(initialBackoffMillis > 0) { "initialBackoffMillis must be positive" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "maxBackoffMillis must be at least initialBackoffMillis"
        }
        require(crashLoopThreshold > 0) { "crashLoopThreshold must be positive" }
        require(crashLoopWindowMillis > 0) { "crashLoopWindowMillis must be positive" }
    }
}

interface ServerSupervisor {
    val state: StateFlow<ServerState>

    suspend fun start(): ApiResult<ServerEndpoint>

    suspend fun stop(graceMillis: Long = 3_000): ApiResult<Unit>

    suspend fun restart(): ApiResult<ServerEndpoint>
}
