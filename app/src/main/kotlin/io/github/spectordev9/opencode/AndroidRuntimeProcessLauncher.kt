package io.github.spectordev9.opencode

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessSpec

/**
 * Android‑specific stub implementation of [RuntimeProcessLauncher].
 * In a full implementation this would start the real `opencode` process
 * (e.g. via `am start` or `ProcessBuilder`) and return its PID etc.
 * For now it returns a successful result so the UI can compile and run.
 */
object AndroidRuntimeProcessLauncher : RuntimeProcessLauncher {
    override fun launch(spec: ProcessSpec): ApiResult<ManagedProcess> {
        // TODO: replace with real process launch logic (e.g. adb am start)
        val process = ManagedProcess(
            processId = -1,
            exitCode = 0,
            output = "",
            error = ""
        )
        return ApiResult.success(process)
    }
}