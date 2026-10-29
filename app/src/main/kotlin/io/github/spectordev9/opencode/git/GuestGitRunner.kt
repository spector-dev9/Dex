package io.github.spectordev9.opencode.git

import android.content.Context
import io.github.spectordev9.opencode.core.git.impl.ShellOutcome
import io.github.spectordev9.opencode.core.git.impl.ShellRunner
import io.github.spectordev9.opencode.runtime.GuestBootstrap
import io.github.spectordev9.opencode.runtime.RuntimePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one-shot commands inside the pinned Alpine guest via PRoot (the same
 * exec pattern as [io.github.spectordev9.opencode.terminal.ProductionPtyService]):
 * only APK-native executables are exec'd; guest binaries are loaded by PRoot.
 *
 * Output handling is deadlock-safe: output is drained on a helper thread
 * while stdin is written and the process is awaited, so a command that emits
 * more than a pipe buffer cannot stall before it exits.
 */
class GuestGitRunner(private val context: Context) : ShellRunner {

    override suspend fun run(command: String, stdin: String?): ShellOutcome = withContext(Dispatchers.IO) {
        val root = RuntimePaths.runtimeRoot(context)
        val rootfs = RuntimePaths.alpineRootfs(root)
        if (!Files.isRegularFile(rootfs.resolve("bin/sh"))) {
            return@withContext ShellOutcome(
                exitCode = 127,
                stdout = "",
                stderr = "runtime rootfs is not installed yet",
            )
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so")
        if (!proot.isFile) {
            return@withContext ShellOutcome(127, "", "packaged PRoot loader is missing")
        }
        val hostWorkspace = RuntimePaths.serverWorkspaceDirectory(context)
        GuestBootstrap.prepare(context, rootfs, hostWorkspace)

        val arguments = listOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-0",
            "-r", rootfs.toString(),
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$hostWorkspace:/workspace",
            "-w", GUEST_WORKSPACE,
            "/bin/sh",
            "-c",
            command,
        )
        val environment = GuestBootstrap.environment(rootfs, root).apply {
            put("PROOT_LOADER", File(nativeDir, "libproot_loader.so").absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put("LD_LIBRARY_PATH", root.toString() + ":" + RuntimePaths.runtimeDirectory(root))
            put("TERM", "xterm-256color")
        }

        val process = try {
            ProcessBuilder(arguments)
                .directory(hostWorkspace.toFile())
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                    redirectErrorStream(true)
                }
                .start()
        } catch (t: Throwable) {
            return@withContext ShellOutcome(127, "", "process launch failed: ${t.message}")
        }

        val output = ByteArrayOutputStream()
        var truncated = false
        val drain = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = MAX_OUTPUT_BYTES - output.size()
                            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                            if (count > remaining) truncated = true
                        }
                    }
                }
            } catch (_: Throwable) {
                // The timeout path still reports the partial output.
            }
        }, "guest-git-output").apply { isDaemon = true }
        drain.start()
        try {
            stdin?.let { payload ->
                process.outputStream.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } ?: process.outputStream.close()

            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            drain.join(DRAIN_TIMEOUT_MILLIS)
            val text = synchronized(output) {
                output.toString(Charsets.UTF_8.name()) +
                    if (truncated) "\n… output truncated" else ""
            }
            if (!finished) {
                ShellOutcome(exitCode = -1, stdout = text, stderr = "command timed out")
            } else {
                ShellOutcome(
                    exitCode = process.exitValue(),
                    stdout = text,
                    stderr = "",
                )
            }
        } catch (t: Throwable) {
            process.destroyForcibly()
            drain.join(DRAIN_TIMEOUT_MILLIS)
            val text = synchronized(output) { output.toString(Charsets.UTF_8.name()) }
            ShellOutcome(exitCode = -1, stdout = text, stderr = t.message ?: "command failed")
        }
    }

    companion object {
        const val GUEST_WORKSPACE = "/workspace"
        const val COMMAND_TIMEOUT_SECONDS = 120L
        const val DRAIN_TIMEOUT_MILLIS = 5_000L
        const val MAX_OUTPUT_BYTES = 8 * 1024 * 1024
    }
}
