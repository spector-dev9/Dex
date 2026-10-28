package io.github.spectordev9.opencode.features

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import io.github.spectordev9.opencode.runtime.RuntimePaths
import java.nio.file.Files

data class CompatibilityReport(
    val supported: Boolean,
    val arm64: Boolean,
    val sdkInt: Int,
    val freeStorageBytes: Long,
    val totalMemoryBytes: Long,
    val runtimeInstalled: Boolean,
    val androidBuildSupported: Boolean,
    val reasons: List<String>,
    val warnings: List<String>,
) {
    val canRunGuestRuntime: Boolean
        get() = supported
}

/**
 * Runtime and build compatibility preflight (F64/F69/F74). Pure evaluation is
 * separated from device probes so thresholds can be tested on the JVM.
 */
object DeviceCompatibility {
    const val MIN_RUNTIME_FREE_BYTES = 512L * 1024 * 1024
    const val RECOMMENDED_ANDROID_BUILD_FREE_BYTES = 8L * 1024 * 1024 * 1024
    const val RECOMMENDED_ANDROID_BUILD_MEMORY_BYTES = 4L * 1024 * 1024 * 1024
    const val MIN_ANDROID_BUILD_SDK = 30

    fun evaluate(
        supportedAbis: List<String>,
        sdkInt: Int,
        freeStorageBytes: Long,
        totalMemoryBytes: Long,
        runtimeInstalled: Boolean,
    ): CompatibilityReport {
        val arm64 = supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
        val reasons = buildList {
            if (!arm64) add("This device does not report arm64-v8a support")
            if (sdkInt < Build.VERSION_CODES.O) add("Android 8.0 or newer is required")
            if (freeStorageBytes < MIN_RUNTIME_FREE_BYTES) {
                add("At least 512 MiB of free storage is required for the runtime")
            }
            if (!runtimeInstalled) add("The guest runtime is not installed yet")
        }
        val warnings = buildList {
            if (freeStorageBytes < RECOMMENDED_ANDROID_BUILD_FREE_BYTES) {
                add("Local Android builds are likely to run out of storage; prefer a remote build")
            }
            if (totalMemoryBytes < RECOMMENDED_ANDROID_BUILD_MEMORY_BYTES) {
                add("Device memory is below the recommended 4 GiB for Android builds")
            }
            if (sdkInt < MIN_ANDROID_BUILD_SDK) {
                add("Android SDK level $sdkInt may be too old for current Gradle builds")
            }
        }
        return CompatibilityReport(
            supported = arm64 && sdkInt >= Build.VERSION_CODES.O &&
                freeStorageBytes >= MIN_RUNTIME_FREE_BYTES && runtimeInstalled,
            arm64 = arm64,
            sdkInt = sdkInt,
            freeStorageBytes = freeStorageBytes,
            totalMemoryBytes = totalMemoryBytes,
            runtimeInstalled = runtimeInstalled,
            androidBuildSupported = warnings.isEmpty(),
            reasons = reasons,
            warnings = warnings,
        )
    }

    fun probe(context: Context): CompatibilityReport {
        val runtimeRoot = RuntimePaths.runtimeRoot(context)
        val runtimeInstalled = runCatching {
            Files.isRegularFile(RuntimePaths.alpineRootfs(runtimeRoot).resolve("bin/sh"))
        }.getOrDefault(false)
        val stat = runCatching { StatFs(runtimeRoot.toString()) }.getOrNull()
        val free = stat?.availableBytes ?: runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)
        val memory = context.getSystemService(Context.ACTIVITY_SERVICE)
            ?.let { it as? ActivityManager }
            ?.let { manager ->
                ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
            }
            ?: 0L
        return evaluate(
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            sdkInt = Build.VERSION.SDK_INT,
            freeStorageBytes = free,
            totalMemoryBytes = memory,
            runtimeInstalled = runtimeInstalled,
        )
    }
}
