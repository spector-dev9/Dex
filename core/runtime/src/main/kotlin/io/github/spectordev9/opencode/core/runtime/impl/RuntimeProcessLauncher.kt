package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessSpec

/** Platform implementations must not execute runtime files from writable storage. */
fun interface RuntimeProcessLauncher {
    fun launch(spec: ProcessSpec): ApiResult<ManagedProcess>
}
