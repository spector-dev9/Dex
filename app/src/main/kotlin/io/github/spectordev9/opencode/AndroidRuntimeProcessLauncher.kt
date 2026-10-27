package io.github.spectordev9.opencode

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessSpec
import io.github.spectordev9.opencode.core.runtime.impl.RuntimeProcessLauncher

object AndroidRuntimeProcessLauncher : RuntimeProcessLauncher {
    override fun launch(spec: ProcessSpec): ApiResult<ManagedProcess> =
        ApiResult.Failure(
            ApiError(
                code = ErrorCode.E_RUNTIME_DOWN,
                message = "Android runtime process launching is not implemented",
            ),
        )
}