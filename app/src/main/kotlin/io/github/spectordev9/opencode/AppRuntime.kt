package io.github.spectordev9.opencode

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.runtime.RuntimeState
import io.github.spectordev9.opencode.core.supervisor.ServerState
import io.github.spectordev9.opencode.core.supervisor.impl.ProcessServerSupervisor
import io.github.spectordev9.opencode.runtime.ProductionRuntimeManager
import io.github.spectordev9.opencode.runtime.RuntimePaths
import io.github.spectordev9.opencode.security.AndroidKeystoreSecretStore
import io.github.spectordev9.opencode.server.LoopbackSecrets
import io.github.spectordev9.opencode.server.ProductionServerLauncher
import io.github.spectordev9.opencode.terminal.ProductionPtyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Composition root for the production app process graph.
 *
 * On first UI attach the graph ensures the pinned runtime is installed and
 * verified, then starts the supervised loopback server; the terminal UI keeps
 * its own PTY lifecycle via [ProductionPtyService].
 */
class AppRuntime private constructor(context: Context, private val scope: CoroutineScope) {
    val context: Context = context.applicationContext

    // One launcher for every supervised process keeps the log sequence unique.
    private val processLauncher = AndroidRuntimeProcessLauncher(
        this.context,
        logDirectory = RuntimePaths
            .serverDirectory(RuntimePaths.runtimeRoot(this.context))
            .resolve("logs")
            .toFile(),
    )

    val runtime = ProductionRuntimeManager(
        context = this.context,
        processLauncher = processLauncher,
    )

    private val serverLauncher = ProductionServerLauncher(
        context = this.context,
        processLauncher = processLauncher,
        secrets = LoopbackSecrets(AndroidKeystoreSecretStore(this.context)),
    )

    val supervisor = ProcessServerSupervisor(scope, serverLauncher)

    val terminal: ProductionPtyService = ProductionPtyService(this.context)

    private val ensureMutex = Mutex()

    /** Idempotently installs the pinned runtime and starts the server. */
    fun ensureRunning(): Job = scope.launch {
        ensureMutex.withLock {
            when (runtime.ensureReady()) {
                is ApiResult.Failure -> return@withLock // state already FAILED
                else -> Unit
            }
            supervisor.start() // failure reason is carried in ServerState
        }
    }

    val runtimeState: StateFlow<RuntimeState>
        get() = runtime.state
    val serverState: StateFlow<ServerState>
        get() = supervisor.state

    companion object {
        @Volatile
        private var instance: AppRuntime? = null

        /** Single graph per process; composed once from the activity. */
        fun obtain(context: Context, scope: CoroutineScope): AppRuntime =
            instance ?: synchronized(this) {
                instance ?: AppRuntime(context, scope).also { instance = it }
            }
    }
}
