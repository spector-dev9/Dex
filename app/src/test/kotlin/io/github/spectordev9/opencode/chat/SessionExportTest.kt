package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.MessageInfo
import io.github.spectordev9.opencode.core.model.Part
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.model.SessionTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionExportTest {
    @Test
    fun `encodes versioned session export with messages and stable identifiers`() {
        val session = Session(
            id = "ses_1",
            slug = "sample",
            projectID = "prj_1",
            directory = "/workspace",
            title = "Sample session",
            version = "1.19.3",
            time = SessionTime(created = 1, updated = 2),
        )
        val message = Message(
            info = MessageInfo(
                id = "msg_1",
                sessionID = "ses_1",
                role = "user",
                time = Json.parseToJsonElement("""{"created":3}""").jsonObject,
            ),
            parts = listOf(
                Part(
                    id = "prt_1",
                    sessionID = "ses_1",
                    messageID = "msg_1",
                    type = "text",
                    text = "Explain this project",
                ),
            ),
        )

        val exported = Json.parseToJsonElement(
            encodeSessionExport(session, listOf(message), exportedAtEpochMillis = 1234L),
        ).jsonObject

        assertEquals(SESSION_EXPORT_FORMAT, exported["format"]!!.jsonPrimitive.content)
        assertEquals(
            SESSION_EXPORT_SCHEMA_VERSION,
            exported["schemaVersion"]!!.jsonPrimitive.content.toInt(),
        )
        assertEquals("1234", exported["exportedAtEpochMillis"]!!.jsonPrimitive.content)
        assertEquals("ses_1", exported["session"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val exportedMessage = exported["messages"]!!.jsonArray.single().jsonObject
        assertEquals("msg_1", exportedMessage["info"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(
            "Explain this project",
            exportedMessage["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertTrue(exported["messages"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `rejects export payloads over the documented limit`() {
        val session = Session(
            id = "ses_1",
            slug = "sample",
            projectID = "prj_1",
            directory = "/workspace",
            title = "Sample session",
            version = "1.19.3",
            time = SessionTime(created = 1, updated = 2),
        )
        val largeMessage = Message(
            info = MessageInfo(
                id = "msg_1",
                sessionID = "ses_1",
                role = "user",
                time = Json.parseToJsonElement("""{"created":3}""").jsonObject,
            ),
            parts = listOf(
                Part(
                    id = "prt_1",
                    sessionID = "ses_1",
                    messageID = "msg_1",
                    type = "text",
                    text = "x".repeat(MAX_SESSION_EXPORT_BYTES),
                ),
            ),
        )

        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            encodeSessionExport(session, listOf(largeMessage), exportedAtEpochMillis = 1234L)
        }

        assertTrue(error.message.orEmpty().contains("20 MiB"))
    }
}
