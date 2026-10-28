package io.github.spectordev9.opencode.server

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic OpenAI-compatible LLM stand-in served on loopback.
 *
 * The pinned server config routes its default model at this server so that
 * prompt round trips work offline and never leave the device. Ports the
 * pattern proven by the S1 spike probe
 * (`tests/spikes/harness/.../SpikeServerProbe.kt`): a raw ServerSocket that
 * answers `POST /v1/chat/completions` with an SSE completion stream and
 * `GET /v1/models` with a static model list.
 */
class MockLlmServer : AutoCloseable {
    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    val requestCount = AtomicInteger()
    val port: Int
        get() = socket.localPort

    fun start() {
        check(running.compareAndSet(false, true))
        socket.soTimeout = 1_000
        acceptThread = Thread(::acceptRequests).apply {
            name = "mock-llm-provider"
            isDaemon = true
            start()
        }
    }

    override fun close() {
        running.set(false)
        acceptThread?.interrupt()
        runCatching { socket.close() }
    }

    private fun acceptRequests() {
        while (running.get()) {
            try {
                socket.accept().use(::handle)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (running.get()) continue
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 10_000
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated mock headers")
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        val size = headers["content-length"]?.toIntOrNull() ?: 0
        if (size !in 0..MAX_REQUEST_BYTES) throw IOException("invalid mock request size")
        val requestBody = input.readNBytes(size)
        if (requestBody.size != size) throw IOException("truncated mock request body")
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        when {
            requestLine.startsWith("POST ") && path.endsWith("/chat/completions") -> {
                requestCount.incrementAndGet()
                streamCompletion(client.getOutputStream(), promptFrom(requestBody))
            }
            requestLine.startsWith("GET ") && path.endsWith("/models") -> {
                write(
                    client.getOutputStream(),
                    200,
                    "application/json",
                    """{"object":"list","data":[{"id":"mock","object":"model","created":0,"owned_by":"opencode-mobile"}]}""",
                )
            }
            else -> write(client.getOutputStream(), 404, "application/json", """{"error":"unsupported mock route"}""")
        }
    }

    private fun promptFrom(body: ByteArray): String? = runCatching {
        val root = JSONObject(String(body, Charsets.UTF_8))
        val messages = root.optJSONArray("messages") ?: return@runCatching null
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "user") continue
            val content = message.opt("content") ?: continue
            when (content) {
                is String -> return@runCatching content
                is JSONArray -> {
                    val text = StringBuilder()
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i) ?: continue
                        if (part.optString("type") == "text") {
                            text.append(part.optString("text"))
                        }
                    }
                    if (text.isNotEmpty()) return@runCatching text.toString()
                }
            }
        }
        null
    }.getOrNull()

    private fun streamCompletion(output: OutputStream, prompt: String?) {
        val created = System.currentTimeMillis() / 1_000
        val response = buildString {
            if (!prompt.isNullOrBlank()) {
                append("You said: \"")
                append(prompt.take(300))
                append("\"\n\n")
            }
            append(RESPONSE_TEXT)
        }
        val chunks = response.chunked(RESPONSE_CHUNK_CHARS)
        writeHeaders(output, "text/event-stream")
        chunks.forEachIndexed { index, chunk ->
            if (index > 0) runCatching { Thread.sleep(CHUNK_DELAY_MS) }
            output.write(sseChunk(created, chunk).toByteArray(Charsets.UTF_8))
        }
        output.write(sseStop(created).toByteArray(Charsets.UTF_8))
        output.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun sseChunk(created: Long, content: String): String {
        val payload = JSONObject()
            .put("id", "chatcmpl-opencode-mobile")
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", "mock")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("delta", JSONObject().put("content", content))
                        .put("index", 0)
                        .put("finish_reason", JSONObject.NULL),
                ),
            )
        return "data: $payload\n\n"
    }

    private fun sseStop(created: Long): String {
        val payload = JSONObject()
            .put("id", "chatcmpl-opencode-mobile")
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", "mock")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("delta", JSONObject())
                        .put("index", 0)
                        .put("finish_reason", "stop"),
                ),
            )
        return "data: $payload\n\n"
    }

    private fun writeHeaders(output: OutputStream, contentType: String) {
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun write(output: OutputStream, status: Int, contentType: String, body: String) {
        val headers = "HTTP/1.1 $status OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(body.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) builder.append(value.toChar())
        }
        return builder.toString()
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 1024 * 1024
        const val RESPONSE_CHUNK_CHARS = 12
        const val CHUNK_DELAY_MS = 90L
        val RESPONSE_TEXT: String =
            "OpenCode Mobile mock is online. " +
                "This reply was generated on-device - no network involved. " +
                "Add a provider API key under Settings to route prompts through a real model."
    }
}
