package io.github.spectordev9.opencode

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.ProcessId
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessExit
import io.github.spectordev9.opencode.core.runtime.ProcessSpec
import io.github.spectordev9.opencode.core.runtime.impl.RuntimeProcessLauncher
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform process launcher for the frozen A_DIRECT runtime profile.
 *
 * The exec target ([ProcessSpec.executable]) is always an APK-native library
 * — the musl loader or the packaged PRoot binary — both of which live in the
 * read-only native-library directory. Writable-storage artifacts (the OpenCode
 * binary, the guest shell) are only ever loaded by those native executables,
 * never execve()'d directly. This is the pattern proven on the reference
 * device with zero `execute_no_trans` denials (`docs/evidence/g3/spike-harness.md`).
 */
class AndroidRuntimeProcessLauncher(
    context: android.content.Context,
    private val logDirectory: File,
) : RuntimeProcessLauncher {
    private val nativeLibraryDir: String = context.applicationInfo.nativeLibraryDir

    override fun launch(spec: ProcessSpec): ApiResult<ManagedProcess> {
        if (spec.executable.isNotBlank() &&
            !spec.executable.startsWith(nativeLibraryDir)
        ) {
            return ApiResult.Failure(
                ApiError(
                    ErrorCode.E_UNSUPPORTED_VERSION,
                    "only APK-native executables may be launched (executable=${spec.executable})",
                ),
            )
        }
        val workingDirectory = File(spec.workingDirectory)
        if (!workingDirectory.isDirectory) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "working directory does not exist: ${spec.workingDirectory}"),
            )
        }
        return try {
            val builder = ProcessBuilder(buildList {
                add(spec.executable)
                addAll(spec.arguments)
            }).directory(workingDirectory).apply {
                environment().apply {
                    remove("LD_PRELOAD")
                    putAll(spec.environment)
                }
                redirectErrorStream(true)
            }
            logDirectory.mkdirs()
            val process = builder.start()
            // Process.pid() is not an Android SDK API; a launch sequence gives
            // log files a stable, unique name instead.
            val serial = PROCESS_SEQUENCE.incrementAndGet()
            val logFile = File(logDirectory, "process-%06d.log".format(serial))
            Thread {
                try {
                    FileOutputStream(logFile, true).use { file ->
                        process.inputStream.use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                file.write(buffer, 0, count)
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // drain loop is best effort
                }
            }.apply {
                name = "runtime-process-log"
                isDaemon = true
                start()
            }
            ApiResult.Success(AndroidManagedProcess(process, serial, logFile))
        } catch (t: Throwable) {
            ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "process launch failed: ${t.message ?: t.javaClass.simpleName}"),
            )
        }
    }

    private companion object {
        val PROCESS_SEQUENCE = AtomicLong(0)
    }
}

internal class AndroidManagedProcess(
    private val process: java.lang.Process,
    private val serial: Long,
    val logFile: File,
) : ManagedProcess {
    override val id: ProcessId = ProcessId("android-$serial")

    // The Android SDK does not expose the child's OS pid via java.lang.Process;
    // lifecycle handling relies on destroy()/destroyForcibly() only.
    override val pid: Long? = null

    override val isAlive: Boolean
        get() = process.isAlive

    override fun signal(signal: Int): ApiResult<Unit> = try {
        when (signal) {
            SIGKILL -> process.destroyForcibly()
            else -> process.destroy()
        }
        ApiResult.Success(Unit)
    } catch (t: Throwable) {
        ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "signal $signal failed: ${t.message}"))
    }

    override suspend fun waitFor(): ProcessExit = withContext(Dispatchers.IO) {
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        ProcessExit(exitCode = code, signal = null)
    }

    override suspend fun terminate(graceMillis: Long): ApiResult<Unit> =
        withContext(Dispatchers.IO) {
            process.destroy()
            val exited = runCatching { process.waitFor(graceMillis, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
            if (!exited) {
                process.destroyForcibly()
                runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            }
            ApiResult.Success(Unit)
        }

    private companion object {
        const val SIGKILL = 9
    }
}
