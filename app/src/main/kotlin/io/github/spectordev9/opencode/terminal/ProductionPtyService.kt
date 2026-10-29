package io.github.spectordev9.opencode.terminal

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.PtyId
import io.github.spectordev9.opencode.core.terminal.PtyEvent
import io.github.spectordev9.opencode.core.terminal.PtyExit
import io.github.spectordev9.opencode.core.terminal.PtyService
import io.github.spectordev9.opencode.core.terminal.PtySession
import io.github.spectordev9.opencode.core.terminal.PtySize
import io.github.spectordev9.opencode.core.terminal.PtySpec
import io.github.spectordev9.opencode.runtime.GuestBootstrap
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Production [PtyService]: opens a native forkpty whose child is the packaged
 * PRoot loader running the pinned Alpine guest. The PTY child always
 * `execve()`s an APK-native executable (`libproot.so` from the read-only
 * native-library directory); guest binaries are only ever loaded by it.
 *
 * Arguments and environment mirror the spike PTY probe that passed on the
 * reference device (`docs/evidence/g3/spike-harness.md`, zero
 * `execute_no_trans` denials), with the production workspace directory bound
 * at `/workspace`.
 */
class ProductionPtyService(private val context: Context) : PtyService {

    override fun open(spec: PtySpec): ApiResult<PtySession> {
        if (spec.executable.isBlank()) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "guest shell path must not be blank"),
            )
        }
        val root = io.github.spectordev9.opencode.runtime.RuntimePaths.runtimeRoot(context)
        val rootfs = io.github.spectordev9.opencode.runtime.RuntimePaths.alpineRootfs(root)
        if (!Files.isRegularFile(rootfs.resolve("bin/sh"))) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime rootfs is not installed yet"),
            )
        }
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so")
        val prootLoader = File(nativeDir, "libproot_loader.so")
        if (!proot.isFile || !prootLoader.isFile) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "packaged PRoot loader is missing"),
            )
        }
        // Bind the same workspace the agent server and Git tab use.
        val hostWorkspace = io.github.spectordev9.opencode.runtime.RuntimePaths
            .serverWorkspaceDirectory(context)
        GuestBootstrap.prepare(context, rootfs, hostWorkspace)
        val guestWorkingDirectory = when (spec.workingDirectory) {
            GUEST_WORKSPACE -> GUEST_WORKSPACE
            else -> GUEST_WORKSPACE // the production workspace is the only v1 mount
        }

        val arguments = listOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-0",
            "-r", rootfs.toString(),
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${hostWorkspace}:${GUEST_WORKSPACE}",
            "-w", guestWorkingDirectory,
            spec.executable,
        ) + spec.arguments

        val environment = GuestBootstrap.environment(rootfs, root).apply {
            put("PROOT_LOADER", prootLoader.absolutePath)
            put("PROOT_NO_SECCOMP", "1")
            put("LD_LIBRARY_PATH", root.toString() + ":" +
                io.github.spectordev9.opencode.runtime.RuntimePaths.runtimeDirectory(root))
            put("TERM", "xterm-256color")
        }
        spec.environment.forEach { (key, value) -> environment[key] = value }

        val handle = NativePty.open(
            workdir = hostWorkspace.toString(),
            columns = spec.initialSize.columns,
            rows = spec.initialSize.rows,
            arguments = arguments.toTypedArray(),
            environmentNames = environment.keys.toTypedArray(),
            environmentValues = environment.values.toTypedArray(),
        )
        if (handle <= 0) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "native PTY open failed"),
            )
        }
        return ApiResult.Success(NativePtySession(PtyId("pty-$handle"), handle, spec.name))
    }

    companion object {
        const val GUEST_WORKSPACE = "/workspace"
    }
}

internal class NativePtySession(
    override val id: PtyId,
    private val handle: Int,
    @Suppress("UNUSED_PARAMETER") name: String?,
) : PtySession {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override val events: Flow<PtyEvent> = callbackFlow {
        val reader = Thread {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            try {
                while (true) {
                    when (val count = NativePty.pollRead(handle, buffer, READ_TIMEOUT_MS)) {
                        in 1..READ_BUFFER_BYTES ->
                            trySend(PtyEvent.Data(buffer.copyOf(count)))
                        0 -> Unit // poll timeout without data
                        -1 -> {
                            val code = NativePty.exitCode(handle)
                            trySend(
                                PtyEvent.Exit(
                                    PtyExit(exitCode = if (code >= 0) code else null),
                                ),
                            )
                            break
                        }
                        else -> {
                            // Handle error; surface as exit so the UI stops.
                            trySend(PtyEvent.Exit(PtyExit(exitCode = null)))
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
                // The reader dies silently; close() still reaps the session.
            } finally {
                close()
            }
        }
        reader.name = "opencode-pty-reader"
        reader.isDaemon = true
        reader.start()
        awaitClose()
    }

    override fun write(bytes: ByteArray): ApiResult<Unit> {
        if (closed.get()) {
            return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "PTY session is closed"))
        }
        return try {
            val written = NativePty.write(handle, bytes, bytes.size)
            when {
                written == bytes.size -> ApiResult.Success(Unit)
                written >= 0 -> ApiResult.Failure(
                    ApiError(ErrorCode.E_RUNTIME_DOWN, "partial PTY write: $written of ${bytes.size}"),
                )
                else -> ApiResult.Failure(
                    ApiError(ErrorCode.E_RUNTIME_DOWN, "PTY write failed"),
                )
            }
        } catch (t: Throwable) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "PTY write failed: ${t.message}"))
        }
    }

    override fun resize(size: PtySize): ApiResult<Unit> {
        if (closed.get()) {
            return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "PTY session is closed"))
        }
        return when (NativePty.resize(handle, size.columns, size.rows)) {
            in 0..0 -> ApiResult.Success(Unit)
            else -> ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "PTY resize failed"))
        }
    }

    override fun signal(signal: Int): ApiResult<Unit> {
        if (closed.get()) {
            return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "PTY session is closed"))
        }
        return when (NativePty.signal(handle, signal)) {
            in 0..0 -> ApiResult.Success(Unit)
            else -> ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "PTY signal failed"))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            NativePty.close(handle)
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 16384
        const val READ_TIMEOUT_MS = 40
    }
}
