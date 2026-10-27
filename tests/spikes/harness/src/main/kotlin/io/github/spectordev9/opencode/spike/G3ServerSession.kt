package io.github.spectordev9.opencode.spike

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class G3ServerSession(
    private val context: Context,
    val port: Int = DEFAULT_PORT,
    val secret: String = DEFAULT_SECRET,
) {
    private var process: java.lang.Process? = null
    private var outputThread: Thread? = null
    private var pid: Int? = null
    private val logFile: File = G3Support.g3Dir(context, "server").resolve("server.log")

    val workspace: File = G3Support.g3Dir(context, "server/workspace")
    private val home: File = G3Support.g3Dir(context, "server/home")
    private val tmp: File = G3Support.g3Dir(context, "server/tmp")
    private val pidFile: File = G3Support.g3Dir(context, "server").resolve("pid")
    private val configFile: File = G3Support.g3Dir(context, "server").resolve("opencode.json")

    fun start(): Boolean {
        stop()
        SpikeProbe.prepareRuntime(context)
        val runtime = File(SpikeProbe.runtimeDir(context), "runtime")
        val binary = File(runtime, "opencode")
        val loader = File(context.applicationInfo.nativeLibraryDir, "libldmusl.so")
        if (!binary.isFile || !loader.isFile) {
            lastError = "runtime or musl loader is missing; download the pinned runtime first"
            return false
        }
        if (!G3Support.waitForPortFree(port, 1_000)) {
            lastError = "port $port is already in use"
            return false
        }
        workspace.mkdirs()
        home.resolve(".config").mkdirs()
        home.resolve(".local/share").mkdirs()
        home.resolve(".cache").mkdirs()
        tmp.mkdirs()
        writeConfig()
        pidFile.delete()
        logFile.parentFile?.mkdirs()
        logFile.writeText("")
        val launcher = writeLauncher(loader, runtime, binary)
        val environment = SpikeProbe.runtimeEnvironment(context).apply {
            put("HOME", home.absolutePath)
            put("XDG_CONFIG_HOME", home.resolve(".config").absolutePath)
            put("XDG_DATA_HOME", home.resolve(".local/share").absolutePath)
            put("XDG_CACHE_HOME", home.resolve(".cache").absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("OPENCODE_CONFIG", configFile.absolutePath)
            put("OPENCODE_SERVER_PASSWORD", secret)
            put("OPENCODE_DISABLE_MODELS_FETCH", "1")
            put("OPENCODE_DISABLE_LSP_DOWNLOAD", "1")
            put("OPENCODE_DISABLE_DEFAULT_PLUGINS", "1")
            put("OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER", "1")
            put("OPENCODE_DISABLE_FFF", "1")
        }
        return try {
            val started = ProcessBuilder("/system/bin/sh", launcher.absolutePath)
                .directory(workspace)
                .apply {
                    environment().remove("LD_PRELOAD")
                    environment().putAll(environment)
                    redirectErrorStream(true)
                }
                .start()
            process = started
            pid = readPid()
            outputThread = Thread {
                try {
                    FileOutputStream(logFile, true).use { file ->
                        started.inputStream.use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                file.write(buffer, 0, count)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                name = "g3-server-output"
                isDaemon = true
                start()
            }
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            process = null
            pid = null
            false
        }
    }

    fun waitForHealth(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val running = process?.isAlive == true
            if (!running) return false
            val result = runCatching {
                G3Support.http(
                    "http://127.0.0.1:$port/global/health",
                    authorization = G3Support.basic(secret),
                    timeoutMs = 2_000,
                )
            }.getOrNull()
            if (result?.status == 200) return true
            Thread.sleep(100)
        }
        return false
    }

    fun healthStatus(): Int? = runCatching {
        G3Support.http(
            "http://127.0.0.1:$port/global/health",
            authorization = G3Support.basic(secret),
            timeoutMs = 2_000,
        ).status
    }.getOrNull()

    fun createSession(title: String): String? {
        val result = runCatching {
            G3Support.http(
                "http://127.0.0.1:$port/session?directory=${workspace.absolutePath}",
                method = "POST",
                authorization = G3Support.basic(secret),
                body = """{"title":"${title.replace("\"", "")}"}""",
                timeoutMs = 10_000,
            )
        }.getOrNull() ?: return null
        if (result.status != 200) return null
        return SESSION_ID.find(result.body)?.groupValues?.get(1)
    }

    fun sessionExists(id: String): Boolean = runCatching {
        G3Support.http(
            "http://127.0.0.1:$port/session/$id?directory=${workspace.absolutePath}",
            authorization = G3Support.basic(secret),
            timeoutMs = 5_000,
        ).status == 200
    }.getOrDefault(false)

    fun isAlive(): Boolean = process?.isAlive == true

    fun pid(): Int? = pid

    fun kill() {
        val currentPid = pid
        if (currentPid != null && currentPid > 0) {
            runCatching { android.os.Process.killProcess(currentPid) }
        }
        process?.destroyForcibly()
        runCatching { process?.waitFor(5, TimeUnit.SECONDS) }
        process = null
    }

    fun stop() {
        val current = process
        if (current != null) {
            current.destroy()
            val exited = runCatching { current.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited) {
                current.destroyForcibly()
                runCatching { current.waitFor(5, TimeUnit.SECONDS) }
            }
        }
        process = null
        pid = null
        outputThread?.join(500)
        outputThread = null
    }

    fun restart(): Boolean {
        stop()
        G3Support.waitForPortFree(port, 5_000)
        return start()
    }

    fun lastLog(maxChars: Int = 4_000): String =
        runCatching {
            val text = logFile.readText()
            text.takeLast(maxChars)
        }.getOrDefault("")

    var lastError: String? = null
        private set

    private fun writeConfig() {
        configFile.writeText(
            """
            {
              "model": "g3-mock/mock",
              "provider": {
                "g3-mock": {
                  "npm": "@ai-sdk/openai-compatible",
                  "name": "G3 local mock",
                  "options": {
                    "baseURL": "http://127.0.0.1:9/v1",
                    "apiKey": "g3-placeholder-not-a-secret"
                  },
                  "models": {
                    "mock": {
                      "name": "G3 deterministic mock",
                      "limit": { "context": 8192, "output": 512 }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
    }

    private fun writeLauncher(loader: File, runtime: File, binary: File): File {
        val launcher = G3Support.g3Dir(context, "server").resolve("start.sh")
        launcher.writeText(
            """
            #!/system/bin/sh
            echo ${'$'}${'$'} > '${pidFile.absolutePath}'
            exec '${loader.absolutePath}' --library-path '${runtime.absolutePath}' '${binary.absolutePath}' serve --hostname 127.0.0.1 --port $port --print-logs --log-level ERROR
            """.trimIndent() + "\n",
        )
        return launcher
    }

    private fun readPid(): Int? {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            val value = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
            if (value != null && value > 0) return value
            Thread.sleep(20)
        }
        return null
    }

    companion object {
        const val DEFAULT_PORT = 45123
        const val DEFAULT_SECRET = "g3-test-secret-2026"
        private val SESSION_ID = Regex(""""id"\s*:\s*"([^"]+)"""")
    }
}
