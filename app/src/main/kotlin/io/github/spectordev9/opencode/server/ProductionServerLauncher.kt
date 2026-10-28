package io.github.spectordev9.opencode.server

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessSpec
import io.github.spectordev9.opencode.core.runtime.impl.RuntimeProcessLauncher
import io.github.spectordev9.opencode.core.supervisor.ServerEndpoint
import io.github.spectordev9.opencode.core.supervisor.ServerHealth
import io.github.spectordev9.opencode.core.supervisor.impl.ServerLauncher
import io.github.spectordev9.opencode.core.supervisor.impl.StartedServer
import io.github.spectordev9.opencode.runtime.RuntimePaths
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Starts the supervised OpenCode server inside the app sandbox using the
 * pinned A_DIRECT runtime: the musl loader loads the pinned binary, the
 * server binds an ephemeral loopback port, and the Basic password is the
 * per-launch D6 secret. Mirrors the proven spike server session
 * (`tests/spikes/harness/src/main/kotlin/.../G3ServerSession.kt`).
 */
class ProductionServerLauncher(
    private val context: Context,
    private val processLauncher: RuntimeProcessLauncher,
    private val secrets: LoopbackSecrets,
    private val mockPort: Int,
) : ServerLauncher {

    override suspend fun nextEndpoint(): ApiResult<ServerEndpoint> = withContext(Dispatchers.IO) {
        repeat(ENDPOINT_ATTEMPTS) {
            try {
                ServerSocket(0).use { socket ->
                    return@withContext ApiResult.Success(
                        ServerEndpoint("127.0.0.1", socket.localPort),
                    )
                }
            } catch (_: Throwable) {
                // retry
            }
        }
        ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "no loopback port available"))
    }

    override suspend fun start(endpoint: ServerEndpoint): ApiResult<StartedServer> =
        withContext(Dispatchers.IO) {
            // D6: every supervised server launch provisions a fresh random
            // loopback secret; the recovered bytes are wiped once the child
            // environment carries them.
            val provisioned = runCatching { secrets.provision() }.getOrNull()?.getOrNull()
            val secret = provisioned?.let { secrets.passwordBytes() }
            if (secret == null) {
                return@withContext ApiResult.Failure(
                    ApiError(ErrorCode.E_AUTH, "loopback secret could not be provisioned"),
                )
            }
            try {
                val root = RuntimePaths.runtimeRoot(context)
                val serverDir = RuntimePaths.serverDirectory(root)
                val workspace = serverDir.resolve("workspace")
                val home = serverDir.resolve("home")
                val tmp = serverDir.resolve("tmp")
                listOf(workspace, home.resolve(".config"), home.resolve(".local/share"),
                    home.resolve(".cache"), tmp).forEach { Files.createDirectories(it) }

                val configFile = serverDir.resolve("opencode.json")
                Files.write(
                    configFile,
                    serverConfig("http://127.0.0.1:$mockPort/v1").toByteArray(),
                )

                val loader = File(context.applicationInfo.nativeLibraryDir, "libldmusl.so")
                val runtimeDirectory = RuntimePaths.runtimeDirectory(root)
                val binary = RuntimePaths.opencodeBinary(root)
                if (!loader.isFile || !Files.isRegularFile(binary)) {
                    return@withContext ApiResult.Failure(
                        ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime executable or loader is missing"),
                    )
                }

                val environment = RuntimePaths.baseEnvironment(context, home).apply {
                    put("XDG_CONFIG_HOME", home.resolve(".config").toString())
                    put("XDG_DATA_HOME", home.resolve(".local/share").toString())
                    put("XDG_CACHE_HOME", home.resolve(".cache").toString())
                    put("TMPDIR", tmp.toString())
                    put("XDG_RUNTIME_DIR", tmp.toString())
                    put("OPENCODE_CONFIG", configFile.toString())
                    put("OPENCODE_SERVER_PASSWORD", secret.decodeToString())
                    put("OPENCODE_DISABLE_MODELS_FETCH", "1")
                    put("OPENCODE_DISABLE_LSP_DOWNLOAD", "1")
                    put("OPENCODE_DISABLE_DEFAULT_PLUGINS", "1")
                    put("OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER", "1")
                    put("OPENCODE_DISABLE_FFF", "1")
                }

                val spec = ProcessSpec(
                    executable = loader.absolutePath,
                    arguments = listOf(
                        "--library-path", runtimeDirectory.toString(), binary.toString(),
                        "serve",
                        "--hostname", endpoint.host,
                        "--port", endpoint.port.toString(),
                        "--print-logs", "--log-level", "ERROR",
                    ),
                    environment = environment,
                    workingDirectory = workspace.toString(),
                )
                when (val started = processLauncher.launch(spec)) {
                    is ApiResult.Success -> {
                        secret.fill(0)
                        ApiResult.Success(
                            StartedServer(endpoint = endpoint, process = started.value),
                        )
                    }
                    is ApiResult.Failure -> {
                        secret.fill(0)
                        started
                    }
                }
            } catch (t: Throwable) {
                ApiResult.Failure(
                    ApiError(ErrorCode.E_RUNTIME_DOWN, "server start failed: ${t.message ?: t.javaClass.simpleName}"),
                )
            }
        }

    override suspend fun health(endpoint: ServerEndpoint): ApiResult<ServerHealth> =
        withContext(Dispatchers.IO) {
            val result = try {
                probeHttp(
                    "http://${endpoint.host}:${endpoint.port}/global/health",
                    authorization = secrets.authorizationHeader(),
                    timeoutMs = 2_000,
                )
            } catch (t: Throwable) {
                null
            }
            if (result == null || result.status != 200) {
                ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "server health probe failed"))
            } else {
                ApiResult.Success(
                    ServerHealth(
                        version = runCatching { JSONObject(result.body).getString("version") }
                            .getOrDefault(""),
                        endpoint = endpoint,
                    ),
                )
            }
        }

    /** Stages the per-process log directory used by the process launcher. */
    fun logDirectory(context: Context): Path =
        RuntimePaths.serverDirectory(RuntimePaths.runtimeRoot(context)).resolve("logs")

    private data class ProbeResult(val status: Int, val body: String)

    private fun probeHttp(
        target: String,
        authorization: String?,
        timeoutMs: Int,
    ): ProbeResult {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(target).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            if (!authorization.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", authorization)
            }
            val status = connection.responseCode
            val body = if (status in 200..299) {
                connection.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                ""
            }
            return ProbeResult(status, body)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT_ATTEMPTS = 5

        /**
         * The server starts with a deterministic local model configuration so
         * that prompt round trips work offline; the mock provider is served by
         * [MockLlmServer] on loopback and real provider profiles arrive with
         * the D4 remote-profile work.
         */
        fun serverConfig(mockBaseUrl: String): String = """
            {
              "model": "opencode-mobile-mock/mock",
              "provider": {
                "opencode-mobile-mock": {
                  "npm": "@ai-sdk/openai-compatible",
                  "name": "OpenCode Mobile on-device mock",
                  "options": {
                    "baseURL": "$mockBaseUrl",
                    "apiKey": "placeholder-not-a-secret"
                  },
                  "models": {
                    "mock": {
                      "name": "On-device mock model",
                      "limit": { "context": 8192, "output": 1024 }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
