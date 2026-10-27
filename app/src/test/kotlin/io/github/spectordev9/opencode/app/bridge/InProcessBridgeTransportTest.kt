package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeCommandHandler
import io.github.spectordev9.opencode.core.bridge.BridgeMessage
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.EventEnvelope
import io.github.spectordev9.opencode.core.bridge.EventTopic
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.CommandId
import io.github.spectordev9.opencode.core.common.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class InProcessBridgeTransportTest {
    @Test
    fun `routes commands to the handler and publishes results`() = runBlocking {
        val transport = InProcessBridgeTransport(
            scope = this,
            handler = BridgeCommandHandler { command ->
                ResultEnvelope.Ok(command.id, mapOf("accepted" to true))
            },
        )
        val received = CompletableDeferred<ResultEnvelope>()
        transport.subscribe { message ->
            if (message is ResultEnvelope) received.complete(message)
        }

        transport.send(CommandEnvelope(CommandId("cmd-1"), "runtime.status"))

        val result = assertIs<ResultEnvelope.Ok<*>>(withTimeout(1_000) { received.await() })
        assertEquals(mapOf("accepted" to true), result.value)
    }

    @Test
    fun `turns unexpected handler failures into an upstream error`() = runBlocking {
        val transport = InProcessBridgeTransport(
            scope = this,
            handler = BridgeCommandHandler { error("handler exploded") },
        )
        val received = CompletableDeferred<ResultEnvelope>()
        transport.subscribe { message ->
            if (message is ResultEnvelope) received.complete(message)
        }

        transport.send(CommandEnvelope(CommandId("cmd-2"), "runtime.status"))

        val error = assertIs<ResultEnvelope.Error>(withTimeout(1_000) { received.await() })
        assertEquals(ErrorCode.E_UPSTREAM, error.error.code)
    }

    @Test
    fun `publishes events to all subscribers despite a broken subscriber`() = runBlocking {
        val transport = InProcessBridgeTransport(
            scope = this,
            handler = BridgeCommandHandler { command -> ResultEnvelope.Ok(command.id, Unit) },
        )
        val received = CompletableDeferred<BridgeMessage>()
        transport.subscribe { error("broken subscriber") }
        transport.subscribe { message -> received.complete(message) }

        transport.publish(
            EventEnvelope(
                topic = EventTopic.SERVER_STATE,
                seq = 1,
                ts = 1,
                data = mapOf("status" to "healthy"),
            ),
        )

        val event = assertIs<EventEnvelope<*>>(withTimeout(1_000) { received.await() })
        assertEquals(EventTopic.SERVER_STATE, event.topic)
    }

    @Test
    fun `stops delivery and rejects sends after close`() = runBlocking {
        val transport = InProcessBridgeTransport(
            scope = this,
            handler = BridgeCommandHandler { command -> ResultEnvelope.Ok(command.id, Unit) },
        )
        var delivered = 0
        transport.subscribe { delivered += 1 }
        transport.close()

        transport.publish(EventEnvelope(EventTopic.SERVER_STATE, 1, 1, emptyMap<String, Any?>()))
        assertEquals(0, delivered)
        assertFailsWith<IllegalStateException> {
            transport.send(CommandEnvelope(CommandId("cmd-3"), "runtime.status"))
        }
    }
}
