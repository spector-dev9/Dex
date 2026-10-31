package io.github.spectordev9.opencode.core.connection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBufferTest {
    @Test
    fun `add and snapshot`() {
        val b = EventBuffer()
        b.add(EventDto(type = "session.created", properties = JsonObject(emptyMap())))
        assertEquals(1, b.size())
        val snap = b.snapshot()
        assertEquals(1, snap.size)
        assertEquals("session.created", snap[0].type)
    }

    @Test
    fun `merge message part updated by key`() {
        val b = EventBuffer()
        val partId = "p1"
        val props = buildJsonObject { put("part", buildJsonObject { put("id", JsonPrimitive(partId)) }) }
        b.add(EventDto(type = "message.part.updated", properties = props))
        b.add(EventDto(type = "message.part.updated", properties = props))
        assertEquals(1, b.size())
    }

    @Test
    fun `critical events never dropped`() {
        val b = EventBuffer(maxSize = 2)
        b.add(EventDto(type = "permission.asked", properties = JsonObject(emptyMap())))
        b.add(EventDto(type = "message.part.updated", properties = JsonObject(emptyMap())))
        b.add(EventDto(type = "message.updated", properties = JsonObject(emptyMap())))
        assertEquals(2, b.size())
        assertTrue(b.snapshot().any { it.type == "permission.asked" })
    }
}
