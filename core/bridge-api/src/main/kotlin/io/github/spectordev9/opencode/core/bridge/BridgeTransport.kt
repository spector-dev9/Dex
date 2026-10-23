package io.github.spectordev9.opencode.core.bridge

fun interface BridgeSubscription {
    fun close()
}

interface BridgeTransport {
    fun send(message: BridgeMessage)

    fun subscribe(handler: (BridgeMessage) -> Unit): BridgeSubscription

    fun close()
}

interface BridgeCommandHandler {
    suspend fun handle(command: CommandEnvelope): ResultEnvelope
}

fun interface BridgeEventSink {
    fun publish(event: EventEnvelope<*>)
}
