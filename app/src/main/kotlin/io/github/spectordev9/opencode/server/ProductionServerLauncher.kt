package io.github.spectordev9.opencode.server

import android.content.Context
import io.github.spectordev9.opencode.AndroidManagedProcess
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
import io.github.spectordev9.opencode.chat.ProviderSettingsStore
import io.github.spectordev9.opencode.chat.buildProviderConfig
import io.github.spectordev9.opencode.runtime.GuestBootstrap
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
 * Starts the supervised OpenCode server inside the app sandbox through the
 * pinned Alpine rootfs: PRoot provides the guest `/etc/resolv.conf` that
 * Bun's c-ares resolver requires on Android, the server binds an ephemeral
 * loopback port, and the Basic password is the per-launch D6 secret.
 */
class ProductionServerLauncher(
    private val context: Context,
    private val processLauncher: RuntimeProcessLauncher,
    private val secrets: LoopbackSecrets,
    private val mockPort: Int,
    private val providerSettings: ProviderSettingsStore? = null,
) : ServerLauncher {
    private var lastProcessLog: File? = null

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
                val rootfs = RuntimePaths.alpineRootfs(root)
                listOf(workspace, home.resolve(".config"), home.resolve(".local/share"),
                    home.resolve(".cache"), tmp).forEach { Files.createDirectories(it) }

                val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
                val loader = File(nativeDirectory, "libldmusl.so")
                val proot = File(nativeDirectory, "libproot.so")
                val prootLoader = File(nativeDirectory, "libproot_loader.so")
                val shellWrapper = RuntimePaths.guestShellWrapper(context)
                val runtimeDirectory = RuntimePaths.runtimeDirectory(root)
                val binary = RuntimePaths.opencodeBinary(root)
                if (!loader.isFile || !proot.isFile || !prootLoader.isFile ||
                    !shellWrapper.isFile || !Files.isRegularFile(binary) ||
                    !Files.isRegularFile(rootfs.resolve("bin/sh"))
                ) {
                    return@withContext ApiResult.Failure(
                        ApiError(
                            ErrorCode.E_RUNTIME_DOWN,
                            "runtime executable, guest shell wrapper or Alpine rootfs is missing",
                        ),
                    )
                }
                GuestBootstrap.prepare(context, rootfs, workspace)

                val configFile = serverDir.resolve("opencode.json")
                val mockBaseUrl = "http://127.0.0.1:$mockPort/v1"
                val config = providerSettings?.let { store ->
                    buildProviderConfig(store.settings.value, store.apiKey(), mockBaseUrl,
                        shellWrapper.absolutePath)
                } ?: buildProviderConfig(
                    io.github.spectordev9.opencode.chat.LocalProviderSettings(),
                    null,
                    mockBaseUrl,
                    shellWrapper.absolutePath,
                )
                Files.write(configFile, config.toByteArray())

                val environment = RuntimePaths.baseEnvironment(context, home).apply {
                    put("XDG_CONFIG_HOME", home.resolve(".config").toString())
                    put("XDG_DATA_HOME", home.resolve(".local/share").toString())
                    put("XDG_CACHE_HOME", home.resolve(".cache").toString())
                    put("TMPDIR", tmp.toString())
                    put("XDG_RUNTIME_DIR", tmp.toString())
                    put("SHELL", shellWrapper.absolutePath)
                    put("DEX_GUEST_PROOT", proot.absolutePath)
                    put("DEX_GUEST_LOADER", prootLoader.absolutePath)
                    put("DEX_GUEST_ROOTFS", rootfs.toString())
                    put("DEX_GUEST_WORKSPACE", workspace.toString())
                    put("DEX_GUEST_TMP", tmp.toString())
                    put("DEX_GUEST_RUNTIME", runtimeDirectory.toString())
                    put("OPENCODE_CONFIG", configFile.toString())
                    put("OPENCODE_SERVER_PASSWORD", secret.decodeToString())
                    put("OPENCODE_DISABLE_MODELS_FETCH", "1")
                    put("OPENCODE_DISABLE_LSP_DOWNLOAD", "1")
                    put("OPENCODE_DISABLE_DEFAULT_PLUGINS", "1")
                    put("OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER", "1")
                    put("OPENCODE_DISABLE_FFF", "1")
                    put("PROOT_LOADER", prootLoader.absolutePath)
                    put("PROOT_NO_SECCOMP", "1")
                    put("PROOT_TMP_DIR", tmp.toString())
                    put("LD_LIBRARY_PATH", "$runtimeDirectory:/usr/lib:/lib")
                }

                val arguments = buildList {
                    add(proot.absolutePath)
                    add("--kill-on-exit")
                    add("-0")
                    add("-r")
                    add(rootfs.toString())
                    listOf("/dev", "/proc", "/sys").forEach { path ->
                        add("-b")
                        add(path)
                    }
                    listOf(
                        nativeDirectory.absolutePath,
                        serverDir.toString(),
                        workspace.toString(),
                        runtimeDirectory.toString(),
                        rootfs.toString(),
                    ).forEach { path ->
                        add("-b")
                        add("$path:$path")
                    }
                    add("-w")
                    add(workspace.toString())
                    add(binary.toString())
                    add("serve")
                    add("--hostname")
                    add(endpoint.host)
                    add("--port")
                    add(endpoint.port.toString())
                    add("--print-logs")
                    add("--log-level")
                    add("ERROR")
                }

                val spec = ProcessSpec(
                    executable = proot.absolutePath,
                    arguments = arguments,
                    environment = environment,
                    workingDirectory = workspace.toString(),
                )
                when (val started = processLauncher.launch(spec)) {
                    is ApiResult.Success -> {
                        lastProcessLog = (started.value as? AndroidManagedProcess)?.logFile
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
                    timeoutMs = 3_000,
                )
            } catch (t: Throwable) {
                null
            }
            if (result == null || result.status != 200) {
                val logTail = lastProcessLog
                    ?.let { file -> runCatching { file.readText().takeLast(LOG_TAIL_CHARS) }.getOrNull() }
                    ?.trim()
                val detail = if (logTail.isNullOrBlank()) {
                    "server health probe failed"
                } else {
                    "server health probe failed: ${redact(logTail)}"
                }
                ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, detail))
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

    private fun redact(value: String): String =
        value.replace(
            Regex("(?i)(password|token|secret|key|authorization)=([^\\s]+)"),
        ) { match -> "${match.groupValues[1]}=<redacted>" }

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
        const val LOG_TAIL_CHARS = 2_000
    }
}
