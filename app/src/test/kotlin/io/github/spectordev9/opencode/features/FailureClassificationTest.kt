package io.github.spectordev9.opencode.features

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FailureClassificationTest {
    @Test
    fun `classifies runtime and provider failures`() {
        assertEquals(FailureClass.PTY, classifyFailure("PTY spawn failed").kind)
        assertEquals(FailureClass.MISSING_BINARY, classifyFailure("command not found: gradle").kind)
        assertEquals(FailureClass.PERMISSION, classifyFailure("Permission denied for /workspace").kind)
        assertEquals(FailureClass.LIFECYCLE, classifyFailure("lifecycle interrupted the task").kind)
        assertEquals(FailureClass.PROVIDER, classifyFailure("provider model rejected the request").kind)
        assertEquals(FailureClass.API, classifyFailure("HTTP 503 from upstream").kind)
        assertEquals(FailureClass.APP, classifyFailure("unexpected null state").kind)
    }

    @Test
    fun `marks transient classes retryable`() {
        assertTrue(classifyFailure("HTTP timeout").retryable)
        assertTrue(classifyFailure("PTY exit").retryable)
        assertFalse(classifyFailure("Permission denied").retryable)
        assertFalse(classifyFailure("unexpected null state").retryable)
    }
}
