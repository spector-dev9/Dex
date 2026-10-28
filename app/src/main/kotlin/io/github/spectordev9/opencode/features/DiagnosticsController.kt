package io.github.spectordev9.opencode.features

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticEvent
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticLevel
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticReport
import io.github.spectordev9.opencode.core.diagnostics.impl.InMemoryDiagnosticsService
import io.github.spectordev9.opencode.core.security.LogScrubber
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Deterministic redaction for diagnostics (F26/F76/F77). Values are scrubbed
 * before they reach the in-memory buffer, so exported reports never contain
 * bearer tokens, API keys, passwords, or common credential query parameters.
 */
class SecretRedactingScrubber : LogScrubber {
    override fun scrub(value: String): String {
        var result = value
        RULES.forEach { (pattern, replacement) ->
            result = pattern.replace(result, replacement)
        }
        return result.take(MAX_MESSAGE_CHARS)
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 4_000
        private const val REDACTED = "[redacted]"

        private val RULES: List<Pair<Regex, String>> = listOf(
            Regex(
                """(?i)\b(authorization)\s*[:=]\s*(?:bearer|basic)?\s*[A-Za-z0-9._~+/=-]{6,}""",
            ) to "$1: $REDACTED",
            Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{6,}""") to "Bearer $REDACTED",
            Regex("""\bsk-[A-Za-z0-9_-]{8,}""") to "sk-$REDACTED",
            Regex("""(?i)([?&](?:api[_-]?key|token|secret|password)=)[^&\s]+""") to "$1$REDACTED",
            Regex(
                """(?i)\b(api[_-]?key|token|secret|password|passwd|pass)\b\s*[:=]\s*("[^"]*"|'[^']*'|[^\s&]+)""",
            ) to "$1=$REDACTED",
            Regex("""(?i)\bOPENCODE_LOOPBACK_PASSWORD\s*=\s*\S+""") to
                "OPENCODE_LOOPBACK_PASSWORD=$REDACTED",
        )
    }
}

@Serializable
data class DiagnosticExport(
    val format: String = "opencode-mobile-diagnostics",
    val schemaVersion: Int = 1,
    val generatedAtEpochMillis: Long,
    val appVersion: String,
    val runtimeVersion: String?,
    val openCodeVersion: String?,
    val truncated: Boolean,
    val events: List<DiagnosticExportEvent>,
)

@Serializable
data class DiagnosticExportEvent(
    val epochMillis: Long,
    val level: String,
    val category: String,
    val message: String,
    val attributes: Map<String, String>,
)

/**
 * Wires the diagnostics service into the production graph and exports a
 * structured JSON report (F76/F77). Environment details are limited to
 * redacted, explicitly recorded attributes.
 */
class DiagnosticsController(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private val service = InMemoryDiagnosticsService(
        scrubber = SecretRedactingScrubber(),
        appVersion = ::appVersion,
        runtimeVersion = { null },
        openCodeVersion = { null },
        clock = clock,
    )

    fun record(
        level: DiagnosticLevel,
        category: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    ) {
        service.record(
            DiagnosticEvent(
                epochMillis = clock(),
                level = level,
                category = category,
                message = message,
                attributes = attributes,
            ),
        )
    }

    suspend fun report(): ApiResult<DiagnosticReport> = service.export()

    suspend fun exportJson(): Result<String> {
        val report = when (val result = service.export()) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return Result.failure(IllegalStateException(result.error.message))
        }
        val export = DiagnosticExport(
            generatedAtEpochMillis = report.generatedAtEpochMillis,
            appVersion = report.appVersion,
            runtimeVersion = report.runtimeVersion,
            openCodeVersion = report.openCodeVersion,
            truncated = report.truncated,
            events = report.events.map { event ->
                DiagnosticExportEvent(
                    epochMillis = event.epochMillis,
                    level = event.level.name,
                    category = event.category,
                    message = event.message,
                    attributes = event.attributes,
                )
            },
        )
        return Result.success(json.encodeToString(DiagnosticExport.serializer(), export))
    }

    private fun appVersion(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        info.versionName ?: "unknown"
    }.getOrDefault("unknown")
}
