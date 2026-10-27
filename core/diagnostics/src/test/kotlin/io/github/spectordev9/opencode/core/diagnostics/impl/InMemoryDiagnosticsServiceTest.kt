package io.github.spectordev9.opencode.core.diagnostics.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticEvent
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticLevel
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticReport
import io.github.spectordev9.opencode.core.security.LogScrubber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class InMemoryDiagnosticsServiceTest {
    private val scrubber = LogScrubber { value -> value.replace("hunter2", "[redacted]") }

    @Test
    fun `record scrubs messages and attributes before storing`() = runBlocking {
        val service = service()

        service.record(
            DiagnosticEvent(
                epochMillis = 10,
                level = DiagnosticLevel.INFO,
                category = "gateway",
                message = "auth failed for hunter2",
                attributes = mapOf("authorization" to "Bearer hunter2"),
            ),
        )

        val report = (service.export() as ApiResult.Success<DiagnosticReport>).value
        val event = report.events.single()
        assertEquals("auth failed for [redacted]", event.message)
        assertEquals("Bearer [redacted]", event.attributes.getValue("authorization"))
        assertFalse(report.truncated)
    }

    @Test
    fun `bounded buffer drops the oldest events and marks the report truncated`() = runBlocking {
        val service = service(capacity = 2)

        repeat(3) { index ->
            service.record(event(index))
        }

        val report = (service.export() as ApiResult.Success<DiagnosticReport>).value
        assertEquals(listOf(1L, 2L), report.events.map { it.epochMillis })
        assertTrue(report.truncated)
    }

    @Test
    fun `export reports supplied version metadata`() = runBlocking {
        val service = service()

        service.record(event(1))

        val report = (service.export() as ApiResult.Success<DiagnosticReport>).value
        assertEquals("9.9.9", report.appVersion)
        assertEquals("1.0.0", report.runtimeVersion)
        assertEquals("1.19.3", report.openCodeVersion)
        assertEquals(42, report.generatedAtEpochMillis)
    }

    private fun service(capacity: Int = 10): InMemoryDiagnosticsService = InMemoryDiagnosticsService(
        scrubber = scrubber,
        appVersion = { "9.9.9" },
        runtimeVersion = { "1.0.0" },
        openCodeVersion = { "1.19.3" },
        clock = { 42 },
        capacity = capacity,
    )

    private fun event(epochMillis: Long): DiagnosticEvent = DiagnosticEvent(
        epochMillis = epochMillis,
        level = DiagnosticLevel.INFO,
        category = "test",
        message = "event $epochMillis",
    )
}
