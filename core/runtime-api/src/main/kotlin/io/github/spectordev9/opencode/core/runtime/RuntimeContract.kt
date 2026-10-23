package io.github.spectordev9.opencode.core.runtime

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ProcessId
import io.github.spectordev9.opencode.core.common.RequiresG0
import io.github.spectordev9.opencode.core.common.RuntimeId
import kotlinx.coroutines.flow.StateFlow

enum class RuntimeProfile {
    A_DIRECT,
    B_PROOT,
}

enum class RuntimeStateStatus {
    ABSENT,
    INSTALLING,
    READY,
    FAILED,
}

data class RuntimeState(
    val status: RuntimeStateStatus,
    val reason: String? = null,
    val version: String? = null,
)

data class RuntimeInfo(
    val id: RuntimeId,
    val version: String,
    val profile: RuntimeProfile,
    val rootPath: String,
    val manifestSha256: String,
    val openCodeSha256: String,
)

data class ProcessSpec(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String,
    val interactive: Boolean = false,
) {
    init {
        require(executable.isNotBlank()) { "executable must not be blank" }
        require(workingDirectory.isNotBlank()) { "workingDirectory must not be blank" }
    }
}

data class ProcessExit(
    val exitCode: Int?,
    val signal: Int? = null,
)

interface ManagedProcess {
    val id: ProcessId
    val pid: Long?
    val isAlive: Boolean

    fun signal(signal: Int): ApiResult<Unit>

    suspend fun waitFor(): ProcessExit

    suspend fun terminate(graceMillis: Long = 3_000): ApiResult<Unit>
}

interface RuntimeManager {
    val state: StateFlow<RuntimeState>
    val profile: RuntimeProfile

    suspend fun ensureReady(): ApiResult<RuntimeInfo>

    @RequiresG0("process spawning depends on the frozen D4 runtime profile")
    fun spawn(spec: ProcessSpec): ApiResult<ManagedProcess>
}
