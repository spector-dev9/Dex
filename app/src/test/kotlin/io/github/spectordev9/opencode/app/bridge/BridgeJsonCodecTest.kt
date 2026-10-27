package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeError
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.EventEnvelope
import io.github.spectordev9.opencode.core.bridge.EventTopic
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.CommandId
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.model.SessionTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BridgeJsonCodecTest {
    private val codec = BridgeJsonCodec()

    @Test
    fun `round trips command arguments through native JSON values`() {
        val command = CommandEnvelope(
            id = CommandId("cmd-1"),
            name = "session.create",
            args = mapOf(
                "title" to "First",
                "count" to 2L,
                "enabled" to true,
                "model" to mapOf("id" to "model", "providerID" to "provider"),
                "tags" to listOf("a", "b"),
            ),
        )

        val decoded = codec.decodeCommand(codec.encodeCommand(command))

        assertEquals(command.id, decoded.id)
        assertEquals(command.name, decoded.name)
        assertEquals(command.args, decoded.args)
    }

    @Test
    fun `encodes typed session results and decodes them as bridge maps`() {
        val result = ResultEnvelope.Ok(
            id = CommandId("cmd-2"),
            value = Session(
                id = "ses_1",
                slug = "first",
                projectID = "prj_1",
                directory = "/workspace",
                title = "First",
                version = "1.19.3",
                time = SessionTime(created = 1, updated = 2),
            ),
        )

        val decoded = assertIs<ResultEnvelope.Ok<*>>(codec.decodeResult(codec.encodeResult(result)))
        val session = assertIs<Map<*, *>>(decoded.value)

        assertEquals("ses_1", session["id"])
        assertEquals("First", session["title"])
    }

    @Test
    fun `round trips error results with retryability from the error code`() {
        val result = ResultEnvelope.Error(
            id = CommandId("cmd-3"),
            error = BridgeError(
                code = ErrorCode.SERVER_UNAVAILABLE,
                message = "server is starting",
                upstreamStatus = 503,
            ),
        )

        val decoded = assertIs<ResultEnvelope.Error>(codec.decodeResult(codec.encodeResult(result)))

        assertEquals(ErrorCode.SERVER_UNAVAILABLE, decoded.error.code)
        assertEquals("server is starting", decoded.error.message)
        assertEquals(503, decoded.error.upstreamStatus)
    }

    @Test
    fun `round trips event envelopes`() {
        val event = EventEnvelope(
            topic = EventTopic.SESSION_EVENT,
            seq = 7,
            ts = 1_700_000_000_000,
            data = mapOf("type" to "idle", "sessionID" to "ses_1"),
        )

        val decoded = codec.decodeEvent(codec.encodeEvent(event))

        assertEquals(event.topic, decoded.topic)
        assertEquals(event.seq, decoded.seq)
        assertEquals(event.ts, decoded.ts)
        assertEquals(event.data, decoded.data)
    }

    @Test
    fun `rejects bridge messages with the wrong envelope type`() {
        val command = codec.encodeCommand(CommandEnvelope(CommandId("cmd-4"), "runtime.start"))

        assertFailsWith<IllegalArgumentException> { codec.decodeResult(command) }
    }
}
