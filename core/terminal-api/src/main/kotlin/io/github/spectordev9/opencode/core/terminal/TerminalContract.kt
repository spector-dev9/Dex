package io.github.spectordev9.opencode.core.terminal

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.PtyId
import io.github.spectordev9.opencode.core.common.RequiresG0
import kotlinx.coroutines.flow.Flow

data class PtySize(
    val columns: Int,
    val rows: Int,
) {
    init {
        require(columns > 0) { "columns must be positive" }
        require(rows > 0) { "rows must be positive" }
    }
}

data class PtySpec(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String,
    val initialSize: PtySize,
    val name: String? = null,
) {
    init {
        require(executable.isNotBlank()) { "executable must not be blank" }
        require(workingDirectory.isNotBlank()) { "workingDirectory must not be blank" }
    }
}

data class PtyExit(
    val exitCode: Int?,
    val signal: Int? = null,
)

sealed interface PtyEvent {
    data class Data(val bytes: ByteArray) : PtyEvent

    data class Exit(val status: PtyExit) : PtyEvent
}

interface PtySession {
    val id: PtyId
    val events: Flow<PtyEvent>

    fun write(bytes: ByteArray): ApiResult<Unit>

    fun resize(size: PtySize): ApiResult<Unit>

    @RequiresG0("signal delivery depends on the native PTY backend")
    fun signal(signal: Int): ApiResult<Unit>

    fun close()
}

@RequiresG0("the native PTY backend is gated by D4 and D10")
interface PtyService {
    fun open(spec: PtySpec): ApiResult<PtySession>
}
