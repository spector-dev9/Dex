package io.github.spectordev9.opencode.core.connection

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EventBuffer(
    private val maxSize: Int = 1024,
) {
    private val events = mutableListOf<EventDto>()

    @Synchronized
    fun add(event: EventDto) {
        val key = mergeKey(event)
        if (key != null && !isCritical(event.type)) {
            val index = events.indexOfFirst { mergeKey(it) == key }
            if (index >= 0) {
                events[index] = event
                return
            }
        }
        if (events.size >= maxSize) {
            val removable = events.indexOfFirst { !isCritical(it.type) }
            if (removable >= 0) {
                events.removeAt(removable)
            } else if (!isCritical(event.type)) {
                return
            }
        }
        events += event
    }

    @Synchronized
    fun snapshot(): List<EventDto> = events.toList()

    @Synchronized
    fun clear() {
        events.clear()
    }

    @Synchronized
    fun size(): Int = events.size

    private fun mergeKey(event: EventDto): String? {
        val properties = event.properties
        return when (event.type) {
            "message.part.updated" -> {
                val id = properties["part"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                id?.let { "part:$it" }
            }
            "message.updated" -> {
                val id = properties["info"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                id?.let { "message:$it" }
            }
            "session.updated" -> {
                properties["sessionID"]?.jsonPrimitive?.content?.let { "session:$it" }
            }
            else -> null
        }
    }

    private fun isCritical(type: String): Boolean =
        type.startsWith("permission.") ||
            type.startsWith("question.") ||
            type == "session.status" ||
            type == "session.idle" ||
            type == "session.error"
}
