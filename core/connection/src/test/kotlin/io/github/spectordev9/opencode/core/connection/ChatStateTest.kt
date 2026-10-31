package io.github.spectordev9.opencode.core.connection

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChatStateTest {
    @Test
    fun `default state idle`() {
        val s = ChatState()
        assertEquals(ConnectionPhase.Idle, s.phase)
        assertEquals(null, s.activeSession)
    }

    @Test
    fun `active session resolved`() {
        val s = ChatState(
            sessionID = "s1",
            sessions = listOf(SessionDto(id = "s1"))
        )
        assertNotNull(s.activeSession)
        assertEquals("s1", s.activeSession!!.id)
    }
}
