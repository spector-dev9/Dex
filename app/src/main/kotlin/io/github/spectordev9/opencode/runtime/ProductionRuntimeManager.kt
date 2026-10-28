package io.github.spectordev9.opencode.runtime

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.RuntimeId
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessSpec
import io.github.spectordev9.opencode.core.runtime.RuntimeInfo
import io.github.spectordev9.opencode.core.runtime.RuntimeManager
import io.github.spectordev9.opencode.core.runtime.RuntimeProfile
import io.github.spectordev9.opencode.core.runtime.RuntimeState
import io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus
import io.github.spectordev9.opencode.core.runtime.impl.RuntimeProcessLauncher
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Production [RuntimeManager] over the pinned-runtime layout staged by
 * [PinnedRuntimeInstaller]. `ensureReady()` re-verifies the manifest pin and
 * the installed executable digest at startup (RT-3 startup enforcement);
 * `spawn()` is gated on a READY state.
 */
class ProductionRuntimeManager(
    private val context: Context,
    private val processLauncher: RuntimeProcessLauncher,
) : RuntimeManager {
    private val stateFlow = MutableStateFlow(RuntimeState(status = RuntimeStateStatus.ABSENT))

    override val state: StateFlow<RuntimeState> = stateFlow.asStateFlow()
    override val profile: RuntimeProfile = RuntimeProfile.A_DIRECT

    @Volatile
    private var runtimeInfo: RuntimeInfo? = null

    override suspend fun ensureReady(): ApiResult<RuntimeInfo> = withContext(Dispatchers.IO) {
        runtimeInfo?.let { return@withContext ApiResult.Success(it) }

        stateFlow.value = RuntimeState(
            status = RuntimeStateStatus.ABSENT,
            version = null,
            reason = "runtime will download on first start",
        )
        val installer = PinnedRuntimeInstaller(context)
        try {
        stateFlow.value = RuntimeState(status = RuntimeStateStatus.INSTALLING, reason = null)
        val summary = installer.install(
            progress = { item, received, total ->
                // Keep the banner alive during the multi-minute first-run
                // download; StateFlow dedupes identical values.
                stateFlow.value = RuntimeState(
                    status = RuntimeStateStatus.INSTALLING,
                    reason = downloadProgressReason(item, received, total),
                )
            },
            phase = { phase ->
                stateFlow.value = RuntimeState(
                    status = RuntimeStateStatus.INSTALLING,
                    reason = phase,
                )
            },
        )
            stateFlow.value = RuntimeState(
                status = RuntimeStateStatus.READY,
                version = manifestVersion(),
                reason = null,
            )
            val info = verifyAndBuildRuntimeInfo() ?: return@withContext ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "installed runtime failed startup verification"),
            )
            runtimeInfo = info
            ApiResult.Success(info).also {
                installSummary = summary
            }
        } catch (t: Throwable) {
            installSummary = null
            stateFlow.value = RuntimeState(
                status = RuntimeStateStatus.FAILED,
                version = null,
                reason = "runtime install failed: ${t.message ?: t.javaClass.simpleName}",
            )
            ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime install failed: ${t.message ?: t.javaClass.simpleName}"),
            )
        }
    }

    override fun spawn(spec: ProcessSpec): ApiResult<ManagedProcess> {
        if (stateFlow.value.status != RuntimeStateStatus.READY) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime is not ready"),
            )
        }
        return processLauncher.launch(spec)
    }

    /** Last successful install evidence summary (RT-3), or null while absent. */
    @Volatile
    var installSummary: String? = null
        private set

    internal fun verifyAndBuildRuntimeInfo(): RuntimeInfo? {
        val root = RuntimePaths.runtimeRoot(context)
        val binaryPath = RuntimePaths.opencodeBinary(root)
        if (!Files.isRegularFile(binaryPath, NOFOLLOW_LINKS)) {
            return null
        }
        // The digests were verified by core:runtime installers; here we latch
        // the identity recorded in the pinned manifest for RuntimeInfo.
        return try {
            JSONObject(
                context.assets.open("runtime-manifest.json").bufferedReader().readText(),
            ).let { manifest ->
                RuntimeInfo(
                    id = RuntimeId("android-pinned-${manifest.getString("version")}"),
                    version = manifest.getString("version"),
                    profile = RuntimeProfile.A_DIRECT,
                    rootPath = root.toString(),
                    manifestSha256 = manifest.getString("binarySha256"),
                    openCodeSha256 = digestOf(binaryPath),
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun manifestVersion(): String = try {
        JSONObject(
            context.assets.open("runtime-manifest.json").bufferedReader().readText(),
        ).getString("version")
    } catch (_: Throwable) {
        "unknown"
    }

    /** Banner-friendly download progress line ("Downloading X · 12 / 98 MiB"). */
    private fun downloadProgressReason(item: String, received: Long, total: Long): String {
        fun mib(bytes: Long) = "${bytes / (1024 * 1024)} MiB"
        return if (total > 0) {
            "Downloading $item · ${mib(received)} / ${mib(total)}"
        } else {
            "Downloading $item · ${mib(received)}"
        }
    }

    private fun digestOf(path: java.nio.file.Path): String =
        MessageDigest.getInstance("SHA-256").let { digest ->
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
}
