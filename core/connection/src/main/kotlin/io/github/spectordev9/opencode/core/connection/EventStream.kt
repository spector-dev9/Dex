package io.github.spectordev9.opencode.core.connection

import java.net.SocketTimeoutException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response

class EventStream(
    private val api: OpenCodeApi,
    private val scope: CoroutineScope,
    private val log: ConnectionLog = ConnectionLog(),
    private val buffer: EventBuffer = EventBuffer(),
    private val onEvent: suspend (EventDto) -> Unit = {},
    private val onReconcile: suspend () -> Unit = {},
    private val onError: (ConnectionError) -> Unit = {},
    private val random: Random = Random.Default,
) {
    @Volatile
    private var job: Job? = null

    @Volatile
    private var activeResponse: Response? = null

    fun start(directory: String) {
        if (job?.isActive == true) return
        job = scope.launch { runLoop(directory) }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeActiveResponse()
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun snapshot(): List<EventDto> = buffer.snapshot()

    private suspend fun runLoop(directory: String) {
        var backoffMs = 500L
        while (currentCoroutineContext().isActive) {
            try {
                val response = api.openEventStream(directory)
                activeResponse = response
                response.use {
                    backoffMs = 500L
                    val body = it.body ?: throw ParseError("SSE response had no body")
                    val source = body.source()
                    val parser = SseParser()
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        parser.feed(line + "\n").forEach { frame -> handleFrame(frame) }
                    }
                    parser.finish().forEach { frame -> handleFrame(frame) }
                    log.add("SSE stream ended")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SocketTimeoutException) {
                log.add("SSE idle timeout")
                probeAfterIdle()
            } catch (error: ConnectionError) {
                if (!currentCoroutineContext().isActive) break
                log.add("SSE error: ${error.message}")
                onError(error)
            } catch (error: Exception) {
                if (!currentCoroutineContext().isActive) break
                val mapped = ErrorMapping.fromThrowable(error)
                log.add("SSE error: ${mapped.message}")
                onError(mapped)
            } finally {
                activeResponse = null
            }
            if (!currentCoroutineContext().isActive) break
            val jittered = random.nextLong(backoffMs / 2, backoffMs + 1)
            delay(jittered)
            backoffMs = (backoffMs * 2).coerceAtMost(8_000)
        }
    }

    private suspend fun probeAfterIdle() {
        try {
            val health = api.health()
            if (!health.healthy) onError(ServerNotReady("health endpoint reported unhealthy"))
        } catch (error: ConnectionError) {
            onError(error)
        }
    }

    private suspend fun handleFrame(frame: SseFrame) {
        if (frame.data.isBlank()) return
        val event = try {
            ConnectionJson.decode(EventDto.serializer(), frame.data)
        } catch (error: ParseError) {
            log.add("Ignored malformed SSE frame: ${error.message}")
            return
        }
        buffer.add(event)
        if (event.type == "server.connected") onReconcile()
        onEvent(event)
    }

    private fun closeActiveResponse() {
        val response = activeResponse
        activeResponse = null
        try {
            response?.close()
        } catch (_: Exception) {
        }
    }
}
