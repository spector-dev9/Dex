package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeCommandHandler
import io.github.spectordev9.opencode.core.bridge.BridgeError
import io.github.spectordev9.opencode.core.bridge.BridgeEventSink
import io.github.spectordev9.opencode.core.bridge.BridgeMessage
import io.github.spectordev9.opencode.core.bridge.BridgeSubscription
import io.github.spectordev9.opencode.core.bridge.BridgeTransport
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.EventEnvelope
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class InProcessBridgeTransport(
    private val scope: CoroutineScope,
    private val handler: BridgeCommandHandler,
) : BridgeTransport, BridgeEventSink {
    private val subscribers = CopyOnWriteArrayList<(BridgeMessage) -> Unit>()
    private val jobs = CopyOnWriteArrayList<Job>()
    private val closed = AtomicBoolean(false)

    override fun send(message: BridgeMessage) {
        check(!closed.get()) { "bridge transport is closed" }
        when (message) {
            is CommandEnvelope -> {
                val job = scope.launch {
                    val result = try {
                        handler.handle(message)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        ResultEnvelope.Error(
                            id = message.id,
                            error = BridgeError(
                                code = ErrorCode.E_UPSTREAM,
                                message = "bridge command handler failed",
                            ),
                        )
                    }
                    dispatch(result)
                }
                jobs.add(job)
                job.invokeOnCompletion { jobs.remove(job) }
            }

            is EventEnvelope<*> -> dispatch(message)
            is ResultEnvelope -> dispatch(message)
        }
    }

    override fun subscribe(handler: (BridgeMessage) -> Unit): BridgeSubscription {
        check(!closed.get()) { "bridge transport is closed" }
        subscribers.add(handler)
        return BridgeSubscription { subscribers.remove(handler) }
    }

    override fun publish(event: EventEnvelope<*>) {
        if (!closed.get()) dispatch(event)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobs.forEach(Job::cancel)
        jobs.clear()
        subscribers.clear()
    }

    private fun dispatch(message: BridgeMessage) {
        subscribers.forEach { subscriber ->
            try {
                subscriber(message)
            } catch (_: Throwable) {
                // A broken consumer must not prevent delivery to other consumers.
            }
        }
    }
}
