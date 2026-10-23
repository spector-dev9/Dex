package io.github.spectordev9.opencode.core.bridge

import io.github.spectordev9.opencode.core.common.ErrorCode

private fun unsupportedCommand(command: CommandEnvelope): ResultEnvelope.Error =
    ResultEnvelope.Error(
        id = command.id,
        error = BridgeError(
            code = ErrorCode.E_UNSUPPORTED_VERSION,
            message = "unsupported command: ${command.name}",
        ),
    )

private fun upstreamFailure(command: CommandEnvelope, throwable: Throwable): ResultEnvelope.Error =
    ResultEnvelope.Error(
        id = command.id,
        error = BridgeError(
            code = ErrorCode.E_UPSTREAM,
            message = throwable.message ?: "command execution failed",
        ),
    )

class BridgeCommandDispatcher(
    private val handlers: Map<String, suspend (CommandEnvelope) -> ResultEnvelope> = emptyMap(),
) : BridgeCommandHandler {
    constructor(vararg entries: Pair<String, suspend (CommandEnvelope) -> ResultEnvelope>) : this(entries.toMap())

    override suspend fun handle(command: CommandEnvelope): ResultEnvelope {
        val handler = handlers[command.name] ?: return unsupportedCommand(command)

        return try {
            handler(command)
        } catch (throwable: Throwable) {
            upstreamFailure(command, throwable)
        }
    }
}

class InMemoryBridgeTransport : BridgeTransport {
    private val subscribers = linkedSetOf<(BridgeMessage) -> Unit>()
    private val sent = mutableListOf<BridgeMessage>()

    val messages: List<BridgeMessage>
        get() = sent.toList()

    override fun send(message: BridgeMessage) {
        sent += message
        subscribers.forEach { subscriber -> subscriber(message) }
    }

    override fun subscribe(handler: (BridgeMessage) -> Unit): BridgeSubscription {
        subscribers += handler
        return BridgeSubscription { subscribers.remove(handler) }
    }

    override fun close() {
        subscribers.clear()
    }
}
