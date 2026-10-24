package io.github.spectordev9.opencode.spike

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

object SpikeServerProbe {
    private const val RESPONSE_TEXT = "S1-MOCK-OK"
    private const val MAX_REQUEST_BYTES = 1024 * 1024
    private const val HEALTH_TIMEOUT_MS = 90_000L
    private const val PROMPT_TIMEOUT_MS = 120_000L

    fun run(context: Context): List<ProbeRecord> {
        val root = SpikeProbe.runtimeDir(context).toPath()
        val runtime = root.resolve("runtime")
        val binary = runtime.resolve("opencode")
        val loader = Path.of(context.applicationInfo.nativeLibraryDir).resolve("libldmusl.so")
        if (!Files.isRegularFile(binary) || !Files.isRegularFile(loader)) {
            return listOf(ProbeRecord("S1-server-mock", false, "runtime or musl loader is missing"))
        }

        SpikeProbe.prepareRuntime(context)
        val workingDirectory = root.resolve("s1").resolve("workspace")
        val home = root.resolve("s1").resolve("home")
        Files.createDirectories(workingDirectory)
        Files.createDirectories(home.resolve(".config"))
        Files.createDirectories(home.resolve(".local/share"))
        Files.createDirectories(home.resolve(".cache"))
        Files.createDirectories(root.resolve("s1/tmp"))

        val provider = MockProvider()
        val serverPort = reservePort()
        val password = randomSecret()
        val config = writeProviderConfig(root.resolve("s1/opencode.json"), provider.port)
        val command = listOf(
            loader.toString(),
            "--library-path",
            runtime.toString(),
            binary.toString(),
            "serve",
            "--hostname",
            "127.0.0.1",
            "--port",
            serverPort.toString(),
            "--print-logs",
            "--log-level",
            "ERROR",
        )
        val environment = SpikeProbe.runtimeEnvironment(context).apply {
            put("HOME", home.toString())
            put("XDG_CONFIG_HOME", home.resolve(".config").toString())
            put("XDG_DATA_HOME", home.resolve(".local/share").toString())
            put("XDG_CACHE_HOME", home.resolve(".cache").toString())
            put("TMPDIR", root.resolve("s1/tmp").toString())
            put("OPENCODE_CONFIG", config.toString())
            put("OPENCODE_SERVER_PASSWORD", password)
            put("OPENCODE_DISABLE_MODELS_FETCH", "1")
            put("OPENCODE_DISABLE_LSP_DOWNLOAD", "1")
            put("OPENCODE_DISABLE_DEFAULT_PLUGINS", "1")
            put("OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER", "1")
            put("OPENCODE_DISABLE_FFF", "1")
        }
        val output = BoundedOutput()
        var process: Process? = null
        val started = System.currentTimeMillis()
        try {
            provider.start()
            process = ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .apply {
                    environment().remove("LD_PRELOAD")
                    environment().putAll(environment)
                    redirectErrorStream(true)
                }
                .start()
            val processOutput = process.inputStream
            val outputThread = Thread {
                processOutput.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.append(buffer, count)
                    }
                }
            }.apply { name = "s1-server-output"; isDaemon = true; start() }

            val healthy = waitForHealth(serverPort, password, process)
            if (!healthy.first) {
                val detail = "health failed: ${healthy.second}; processExit=${process.exitValueOrNull()}; " +
                    "server=${output.text().replace(password, "[REDACTED]")}"
                return listOf(
                    ProbeRecord(
                        id = "S1-server-health",
                        pass = false,
                        detail = detail,
                        command = command,
                        durationMs = System.currentTimeMillis() - started,
                    ),
                )
            }

            val healthRecord = ProbeRecord(
                id = "S1-server-health",
                pass = true,
                detail = "authenticated /global/health returned HTTP ${healthy.second}",
                command = command,
                exitCode = 0,
                durationMs = healthy.third,
            )
            val prompt = runPrompt(serverPort, password, workingDirectory, provider)
            return listOf(
                healthRecord,
                ProbeRecord(
                    id = "S1-mock-provider-prompt",
                    pass = prompt.pass,
                    detail = prompt.detail,
                    durationMs = System.currentTimeMillis() - started - healthy.third,
                    stdout = prompt.response,
                    stderr = output.text().replace(password, "[REDACTED]"),
                ),
            )
        } catch (failure: Exception) {
            return listOf(
                ProbeRecord(
                    id = "S1-server-mock",
                    pass = false,
                    detail = "${failure.javaClass.simpleName}: ${failure.message}",
                    command = command,
                    durationMs = System.currentTimeMillis() - started,
                    stderr = output.text().replace(password, "[REDACTED]"),
                ),
            )
        } finally {
            process?.let(::stopProcess)
            provider.close()
        }
    }

    private fun runPrompt(
        port: Int,
        password: String,
        workspace: Path,
        provider: MockProvider,
    ): PromptOutcome {
        val baseUrl = "http://127.0.0.1:$port"
        val directoryQuery = "directory=${Uri.encode(workspace.toString())}"
        val auth = "Basic " + Base64.getEncoder()
            .encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))
        val create = request(
            url = "$baseUrl/session?$directoryQuery",
            method = "POST",
            authorization = auth,
            body = JSONObject()
                .put("title", "S1 deterministic mock-provider probe")
                .put("model", JSONObject().put("providerID", "s1-mock").put("id", "mock"))
                .toString(),
        )
        if (create.status !in 200..299) {
            return PromptOutcome(false, "session create returned HTTP ${create.status}: ${create.body}", "")
        }
        val sessionID = try {
            JSONObject(create.body).getString("id")
        } catch (failure: Exception) {
            return PromptOutcome(false, "session create returned invalid JSON", "")
        }
        if (!sessionID.matches(Regex("ses[A-Za-z0-9]+"))) {
            return PromptOutcome(false, "server returned an invalid session ID", "")
        }

        val prompt = request(
            url = "$baseUrl/session/$sessionID/prompt_async?$directoryQuery",
            method = "POST",
            authorization = auth,
            body = JSONObject()
                .put("model", JSONObject().put("providerID", "s1-mock").put("modelID", "mock"))
                .put(
                    "parts",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", "Reply with exactly $RESPONSE_TEXT and no other text."),
                    ),
                )
                .toString(),
        )
        if (prompt.status !in 200..299) {
            return PromptOutcome(false, "prompt_async returned HTTP ${prompt.status}: ${prompt.body}", "")
        }

        val deadline = System.currentTimeMillis() + PROMPT_TIMEOUT_MS
        var lastResponse = ""
        while (System.currentTimeMillis() < deadline) {
            val messages = request(
                url = "$baseUrl/session/$sessionID/message?$directoryQuery",
                method = "GET",
                authorization = auth,
            )
            if (messages.status !in 200..299) {
                return PromptOutcome(
                    false,
                    "message list returned HTTP ${messages.status}: ${messages.body}",
                    messages.body,
                )
            }
            lastResponse = assistantText(messages.body)
            if (lastResponse.contains(RESPONSE_TEXT)) {
                val providerObserved = provider.requestCount.get() > 0
                return PromptOutcome(
                    providerObserved,
                    if (providerObserved) {
                        "mock prompt round trip passed; provider requests=${provider.requestCount.get()}"
                    } else {
                        "expected response appeared without a mock-provider request"
                    },
                    lastResponse,
                )
            }
            Thread.sleep(300)
        }
        return PromptOutcome(
            false,
            "timed out waiting for $RESPONSE_TEXT; provider requests=${provider.requestCount.get()}",
            lastResponse,
        )
    }

    private fun assistantText(body: String): String {
        val messages = JSONArray(body)
        return buildString {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val info = message.optJSONObject("info") ?: continue
                if (info.optString("role") != "assistant") continue
                val parts = message.optJSONArray("parts") ?: continue
                for (partIndex in 0 until parts.length()) {
                    val part = parts.optJSONObject(partIndex) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }
        }
    }

    private fun waitForHealth(port: Int, password: String, process: Process): Triple<Boolean, Int, Long> {
        val started = System.currentTimeMillis()
        val deadline = started + HEALTH_TIMEOUT_MS
        var lastStatus = 0
        while (System.currentTimeMillis() < deadline && process.isAlive) {
            try {
                val response = request(
                    url = "http://127.0.0.1:$port/global/health",
                    method = "GET",
                    authorization = "Basic " + Base64.getEncoder()
                        .encodeToString("opencode:$password".toByteArray(Charsets.UTF_8)),
                    timeoutMs = 2_000,
                )
                lastStatus = response.status
                if (response.status == 200) {
                    val health = JSONObject(response.body)
                    if (health.optBoolean("healthy") && health.optString("version") == "1.19.3") {
                        return Triple(true, response.status, System.currentTimeMillis() - started)
                    }
                }
            } catch (_: IOException) {
                Thread.sleep(250)
            }
        }
        return Triple(false, lastStatus, System.currentTimeMillis() - started)
    }

    private fun request(
        url: String,
        method: String,
        authorization: String,
        body: String? = null,
        timeoutMs: Int = 10_000,
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Authorization", authorization)
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(status, response)
        } finally {
            connection.disconnect()
        }
    }

    private fun writeProviderConfig(path: Path, mockPort: Int): Path {
        val model = JSONObject()
            .put("name", "S1 deterministic mock")
            .put("limit", JSONObject().put("context", 8192).put("output", 512))
        val provider = JSONObject()
            .put("npm", "@ai-sdk/openai-compatible")
            .put("name", "S1 local mock")
            .put(
                "options",
                JSONObject()
                    .put("baseURL", "http://127.0.0.1:$mockPort/v1")
                    .put("apiKey", "spike-only-not-a-secret"),
            )
            .put("models", JSONObject().put("mock", model))
        val config = JSONObject()
            .put("model", "s1-mock/mock")
            .put("provider", JSONObject().put("s1-mock", provider))
        Files.newBufferedWriter(path, Charsets.UTF_8).use { it.write(config.toString()) }
        return path
    }

    private fun reservePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun randomSecret(): String =
        ByteArray(32).also(SecureRandom()::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

    private fun stopProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun Process.exitValueOrNull(): Int? =
        try {
            exitValue()
        } catch (_: IllegalThreadStateException) {
            null
        }

    private data class HttpResult(val status: Int, val body: String)

    private data class PromptOutcome(val pass: Boolean, val detail: String, val response: String)

    private class BoundedOutput {
        private val value = StringBuilder()

        @Synchronized
        fun append(bytes: ByteArray, count: Int) {
            val remaining = MAX_LOG_CHARS - value.length
            if (remaining > 0) {
                value.append(String(bytes, 0, minOf(count, remaining), Charsets.UTF_8))
            }
        }

        @Synchronized
        fun text(): String = value.toString()

        private companion object {
            const val MAX_LOG_CHARS = 8_000
        }
    }

    private class MockProvider : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(false)
        val requestCount = AtomicInteger()
        val port: Int
            get() = socket.localPort

        private var acceptThread: Thread? = null

        fun start() {
            check(running.compareAndSet(false, true))
            socket.soTimeout = 1_000
            acceptThread = Thread(::acceptRequests).apply {
                name = "s1-mock-provider"
                isDaemon = true
                start()
            }
        }

        private fun acceptRequests() {
            while (running.get()) {
                try {
                    socket.accept().use(::handle)
                } catch (_: java.net.SocketTimeoutException) {
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
                val line = readLine(input) ?: throw IOException("truncated mock-provider headers")
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
            }
            val size = headers["content-length"]?.toIntOrNull() ?: 0
            if (size !in 0..MAX_REQUEST_BYTES) throw IOException("invalid mock-provider request size")
            val requestBody = input.readNBytes(size)
            if (requestBody.size != size) throw IOException("truncated mock-provider request body")
            val path = requestLine.split(' ').getOrNull(1).orEmpty()
            if (requestLine.startsWith("POST ") && path.endsWith("/chat/completions")) {
                requestCount.incrementAndGet()
                writeResponse(client, 200, "text/event-stream", completionStream())
            } else if (requestLine.startsWith("GET ") && path.endsWith("/models")) {
                writeResponse(
                    client,
                    200,
                    "application/json",
                    """{"object":"list","data":[{"id":"mock","object":"model","created":0,"owned_by":"spike"}]}""",
                )
            } else {
                writeResponse(client, 404, "application/json", """{"error":"unsupported mock route"}""")
            }
        }

        private fun completionStream(): String {
            val created = System.currentTimeMillis() / 1000
            val content = JSONObject()
                .put("id", "chatcmpl-s1-mock")
                .put("object", "chat.completion.chunk")
                .put("created", created)
                .put("model", "mock")
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject()
                            .put(
                                "delta",
                                JSONObject().put("role", "assistant").put("content", RESPONSE_TEXT),
                            )
                            .put("index", 0)
                            .put("finish_reason", JSONObject.NULL),
                    ),
                )
            val finished = JSONObject()
                .put("id", "chatcmpl-s1-mock")
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
            return "data: $content\n\ndata: $finished\n\ndata: [DONE]\n\n"
        }

        private fun writeResponse(client: Socket, status: Int, contentType: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(header.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }

        private fun readLine(input: java.io.InputStream): String? {
            val output = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) return if (output.size() == 0) null else output.toString(Charsets.US_ASCII.name())
                if (next == '\n'.code) {
                    val bytes = output.toByteArray()
                    val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                    return String(bytes, 0, length, Charsets.US_ASCII)
                }
                if (output.size() >= MAX_HEADER_LINE_BYTES) throw IOException("mock-provider header line is too long")
                output.write(next)
            }
        }

        override fun close() {
            running.set(false)
            socket.close()
            acceptThread?.join(1_000)
        }

        private companion object {
            const val MAX_HEADER_LINE_BYTES = 16 * 1024
        }
    }
}
