package io.github.spectordev9.opencode.ui

import io.github.spectordev9.opencode.formatToolDuration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ToolDurationTest {
    @Test
    fun `formats server-reported tool durations`() {
        assertEquals("duration 1.5s", formatToolDuration(1_000L, 2_550L))
        assertEquals("duration 1:02", formatToolDuration(0L, 62_000L))
    }

    @Test
    fun `omits missing or invalid tool timing`() {
        assertNull(formatToolDuration(null, 10L))
        assertNull(formatToolDuration(10L, null))
        assertNull(formatToolDuration(20L, 10L))
        assertNull(formatToolDuration(-1L, 10L))
    }
}
