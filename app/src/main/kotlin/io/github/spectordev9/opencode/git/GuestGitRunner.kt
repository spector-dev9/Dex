package io.github.spectordev9.opencode.git

import android.content.Context
import io.github.spectordev9.opencode.core.git.impl.ShellOutcome
import io.github.spectordev9.opencode.core.git.impl.ShellRunner
import io.github.spectordev9.opencode.runtime.RuntimePaths
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
 * Output handling is deadlock-safe: stdin is written and closed first, the
 * merged output stream is drained to EOF, and only then is the exit code
 * awaited under a timeout.
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
        val hostWorkspace = RuntimePaths.workspaceDirectory(context)
        Files.createDirectories(hostWorkspace)

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
        val environment = mutableMapOf(
            "PROOT_LOADER" to File(nativeDir, "libproot_loader.so").absolutePath,
            "PROOT_TMP_DIR" to root.resolve("tmp").toString(),
            "PROOT_NO_SECCOMP" to "1",
            "LD_LIBRARY_PATH" to root.toString() + ":" + RuntimePaths.runtimeDirectory(root),
            "PATH" to "/system/bin:/system/xbin:/usr/local/sbin:/usr/local/bin:" +
                "/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "GIT_CONFIG_GLOBAL" to root.resolve("gitconfig").toString(),
        )

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

        try {
            stdin?.let { payload ->
                process.outputStream.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } ?: process.outputStream.close()

            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ShellOutcome(exitCode = -1, stdout = output, stderr = "command timed out")
            }
            ShellOutcome(
                exitCode = process.exitValue(),
                stdout = output,
                stderr = "",
            )
        } catch (t: Throwable) {
            process.destroyForcibly()
            ShellOutcome(exitCode = -1, stdout = "", stderr = t.message ?: "command failed")
        }
    }

    companion object {
        const val GUEST_WORKSPACE = "/workspace"
        const val COMMAND_TIMEOUT_SECONDS = 120L
    }
}
