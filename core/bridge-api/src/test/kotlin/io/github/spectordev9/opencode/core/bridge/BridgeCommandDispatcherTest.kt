package io.github.spectordev9.opencode.core.bridge

import io.github.spectordev9.opencode.core.common.CommandId
import io.github.spectordev9.opencode.core.common.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BridgeCommandDispatcherTest {
    @Test
    fun `dispatcher routes commands and wraps failures`() = kotlinx.coroutines.runBlocking {
        val dispatcher = BridgeCommandDispatcher(
            BridgeCommandNames.RUNTIME_STATUS to { command ->
                ResultEnvelope.Ok(
                    id = command.id,
                    value = mapOf("status" to "ready"),
                )
            },
        )

        val result = dispatcher.handle(
            CommandEnvelope(
                id = CommandId("cmd-1"),
                name = BridgeCommandNames.RUNTIME_STATUS,
            ),
        )

        assertIs<ResultEnvelope.Ok<Map<String, String>>>(result)
        assertEquals("ready", result.value["status"])
    }

    @Test
    fun `dispatcher returns unsupported command result`() = kotlinx.coroutines.runBlocking {
        val dispatcher = BridgeCommandDispatcher()

        val result = dispatcher.handle(
            CommandEnvelope(
                id = CommandId("cmd-2"),
                name = "runtime.missing",
            ),
        )

        assertIs<ResultEnvelope.Error>(result)
        assertEquals(ErrorCode.E_UNSUPPORTED_VERSION, result.error.code)
        assertTrue(result.error.message!!.contains("unsupported command"))
    }

    @Test
    fun `in memory transport records sent events and subscriptions`() {
        val transport = InMemoryBridgeTransport()
        val seen = mutableListOf<BridgeMessage>()
        val sub = transport.subscribe { message -> seen += message }

        val event = EventEnvelope(
            topic = EventTopic.RUNTIME_STATE,
            seq = 1L,
            ts = 123L,
            data = mapOf("status" to "ready"),
        )

        transport.send(event)

        assertEquals(1, transport.messages.size)
        assertEquals(event, transport.messages.first())
        assertEquals(1, seen.size)
        assertEquals(event, seen.first())

        sub.close()
        transport.close()
    }
}
