package io.github.spectordev9.opencode.core.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes pinned session and tolerates additive upstream fields`() {
        val session = json.decodeFromString(
            Session.serializer(),
            """
            {
              "id":"ses_1",
              "slug":"first-session",
              "projectID":"prj_1",
              "directory":"/workspace",
              "title":"Example",
              "version":"1.19.3",
              "time":{"created":1,"updated":2},
              "newUpstreamField":true
            }
            """.trimIndent(),
        )

        assertEquals("ses_1", session.id)
        assertEquals(2, session.time.updated)
    }

    @Test
    fun `decodes messages with text and non-text parts`() {
        val message = json.decodeFromString(
            Message.serializer(),
            """
            {
              "info":{
                "id":"msg_1",
                "sessionID":"ses_1",
                "role":"assistant",
                "time":{"start":10,"end":12},
                "providerID":"provider",
                "modelID":"model"
              },
              "parts":[
                {"id":"prt_1","sessionID":"ses_1","messageID":"msg_1","type":"text","text":"hello"},
                {"id":"prt_2","sessionID":"ses_1","messageID":"msg_1","type":"reasoning","text":"thinking"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals("assistant", message.info.role)
        assertEquals(listOf("text", "reasoning"), message.parts.map(Part::type))
        assertEquals("hello", message.parts.first().text)
    }

    @Test
    fun `decodes permission request and file entry variants`() {
        val permission = json.decodeFromString(
            PermissionRequest.serializer(),
            """
            {
              "id":"per_1",
              "sessionID":"ses_1",
              "permission":"edit",
              "patterns":["src/**"],
              "metadata":{},
              "always":[],
              "tool":{}
            }
            """.trimIndent(),
        )
        val entry = json.decodeFromString(
            FileEntry.serializer(),
            """{"name":"src","path":"src","absolute":"/workspace/src","type":"directory","ignored":false}""",
        )

        assertEquals("edit", permission.permission)
        assertEquals(FileEntryType.DIRECTORY, entry.type)
    }
}
