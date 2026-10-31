package io.github.spectordev9.opencode.core.connection

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionLogTest {
    @Test
    fun `ring buffer respects capacity`() {
        val log = ConnectionLog(capacity = 2)
        log.add("a")
        log.add("b")
        log.add("c")
        assertEquals(2, log.snapshot().size)
        assertEquals("b", log.snapshot()[0])
    }

    @Test
    fun `redacts secrets`() {
        val log = ConnectionLog()
        log.add("password: secret123")
        val snap = log.snapshot()
        assertTrue(snap.any { it.contains("[REDACTED]") })
    }
}
