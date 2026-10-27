package io.github.spectordev9.opencode

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ProcessId
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessSpec

/**
 * Android‑specific stub implementation of [RuntimeProcessLauncher].
 * Returns a successful result with a fake [ManagedProcess] so the UI can compile
 * and run. Replace with a real implementation (e.g. starting the process via `adb am start`)
 * when the D4 runtime profile is frozen.
 */
object AndroidRuntimeProcessLauncher : RuntimeProcessLauncher {
    override fun launch(spec: ProcessSpec): ApiResult<ManagedProcess> {
        val process = object : ManagedProcess {
            override val id = ProcessId("android-stub")
            override val pid = 0L
            override val isAlive = false

            override fun signal(signal: Int): ApiResult<Unit> = ApiResult.Success(Unit)

            override suspend fun waitFor(): ProcessExit = ProcessExit(exitCode = 0)

            override suspend fun terminate(graceMillis: Long = 3_000): ApiResult<Unit> = ApiResult.Success(Unit)
        }
        return ApiResult.Success(process)
    }
}