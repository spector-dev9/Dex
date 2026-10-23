package io.github.spectordev9.opencode.core.diagnostics

import io.github.spectordev9.opencode.core.common.ApiResult
import kotlinx.coroutines.flow.Flow

enum class DiagnosticLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class DiagnosticEvent(
    val epochMillis: Long,
    val level: DiagnosticLevel,
    val category: String,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
)

data class DiagnosticReport(
    val generatedAtEpochMillis: Long,
    val appVersion: String,
    val runtimeVersion: String?,
    val openCodeVersion: String?,
    val events: List<DiagnosticEvent>,
    val truncated: Boolean = false,
)

interface DiagnosticsService {
    fun record(event: DiagnosticEvent)

    fun observe(): Flow<DiagnosticEvent>

    suspend fun export(): ApiResult<DiagnosticReport>
}
