package io.github.spectordev9.opencode.core.diagnostics.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticEvent
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticReport
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticsService
import io.github.spectordev9.opencode.core.security.LogScrubber
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class InMemoryDiagnosticsService(
    private val scrubber: LogScrubber,
    private val appVersion: () -> String,
    private val runtimeVersion: () -> String? = { null },
    private val openCodeVersion: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = DEFAULT_CAPACITY,
) : DiagnosticsService {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = Any()
    private val buffer = ArrayDeque<DiagnosticEvent>(capacity)
    private val events = MutableSharedFlow<DiagnosticEvent>(
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var dropped = 0L

    override fun record(event: DiagnosticEvent) {
        val scrubbed = event.copy(
            message = scrubber.scrub(event.message),
            attributes = event.attributes.entries.associate { (key, value) ->
                scrubber.scrub(key) to scrubber.scrub(value)
            },
        )
        synchronized(lock) {
            if (buffer.size == capacity) {
                buffer.removeFirst()
                dropped++
            }
            buffer.addLast(scrubbed)
        }
        events.tryEmit(scrubbed)
    }

    override fun observe(): Flow<DiagnosticEvent> = events.asSharedFlow()

    override suspend fun export(): ApiResult<DiagnosticReport> {
        val snapshot: List<DiagnosticEvent>
        val truncated: Boolean
        synchronized(lock) {
            snapshot = buffer.toList()
            truncated = dropped > 0
        }
        return ApiResult.Success(
            DiagnosticReport(
                generatedAtEpochMillis = clock(),
                appVersion = appVersion(),
                runtimeVersion = runtimeVersion(),
                openCodeVersion = openCodeVersion(),
                events = snapshot,
                truncated = truncated,
            ),
        )
    }

    private companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
