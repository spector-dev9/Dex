package io.github.spectordev9.opencode

import android.content.Context
import io.github.spectordev9.opencode.chat.ChatController
import io.github.spectordev9.opencode.chat.FilesController
import io.github.spectordev9.opencode.chat.SharedPreferencesChatSessionStore
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.server.MockLlmServer
import io.github.spectordev9.opencode.core.runtime.RuntimeState
import io.github.spectordev9.opencode.core.supervisor.ServerState
import io.github.spectordev9.opencode.core.supervisor.impl.ProcessServerSupervisor
import io.github.spectordev9.opencode.runtime.ProductionRuntimeManager
import io.github.spectordev9.opencode.runtime.RuntimePaths
import io.github.spectordev9.opencode.security.AndroidKeystoreSecretStore
import io.github.spectordev9.opencode.server.LoopbackSecrets
import io.github.spectordev9.opencode.server.ProductionServerLauncher
import io.github.spectordev9.opencode.terminal.ProductionPtyService
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** On-device OpenAI-compatible stand-in; the default config model routes here. */
    private val mockLlm = MockLlmServer()

    private val secrets = LoopbackSecrets(AndroidKeystoreSecretStore(this.context))

    private val serverLauncher = ProductionServerLauncher(
        context = this.context,
        processLauncher = processLauncher,
        secrets = secrets,
        mockPort = mockLlm.port,
    )

    val supervisor = ProcessServerSupervisor(scope, serverLauncher)

    val terminal: ProductionPtyService = ProductionPtyService(this.context)

    val chat = ChatController(
        scope,
        SharedPreferencesChatSessionStore(this.context),
        io.github.spectordev9.opencode.chat.SharedPrefsPermissionRuleStore(this.context),
    )
    val files = FilesController(scope)
    private val workspaceDirectory = RuntimePaths.workspaceDirectory(this.context).also {
        Files.createDirectories(it)
    }

    /** Guest-shell git for the workspace repository (F31–F35, F36). */
    val git = io.github.spectordev9.opencode.chat.GitController(
        scope = scope,
        git = io.github.spectordev9.opencode.core.git.impl.ShellGitService(
            io.github.spectordev9.opencode.git.GuestGitRunner(this.context),
        ),
        repositoryPath = io.github.spectordev9.opencode.git.GuestGitRunner.GUEST_WORKSPACE,
        workspace = io.github.spectordev9.opencode.core.workspace.impl.FileWorkspaceService(
            workspaceDirectory,
        ),
    )

    /** User-interface preferences (theme mode, font sizes) persisted on-device. */
    val uiPrefs = io.github.spectordev9.opencode.ui.UiPrefs(this.context)

    private var lastAttachedEndpoint: String? = null

    init {
        // The mock provider must be listening before the server's first prompt.
        mockLlm.start()
        scope.launch {
            supervisor.state.collect { state -> onServerState(state) }
        }
    }

    private fun onServerState(state: ServerState) {
        val endpoint = state.endpoint
        if (state.status == io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.HEALTHY &&
            endpoint != null
        ) {
            val endpointKey = "http://${endpoint.host}:${endpoint.port}"
            if (endpointKey == lastAttachedEndpoint) return // avoid re-attach churn
            val password = secrets.passwordText() ?: return
            val client = io.github.spectordev9.opencode.core.opencode.client.OpenCodeHttpClient(
                baseUrl = endpointKey,
                password = password,
            )
            val workspace = RuntimePaths.serverWorkspaceDirectory(context).toString()
            chat.attach(client, workspace)
            files.attach(client, workspace)
            lastAttachedEndpoint = endpointKey
        } else {
            chat.detach()
            files.detach()
            lastAttachedEndpoint = null
        }
    }

    private val ensureMutex = Mutex()

    /** Idempotently installs the pinned runtime and starts the server.
     *  Transient first-run download failures are retried with backoff. */
    fun ensureRunning(): Job = scope.launch {
        ensureMutex.withLock {
            var attempt = 0
            while (true) {
                val ready = runtime.ensureReady()
                if (ready is ApiResult.Success) {
                    supervisor.start() // failure reason is carried in ServerState
                    break
                }
                if (attempt >= START_RETRIES) break
                attempt++
                delay(START_RETRY_BACKOFF_MS * attempt)
            }
        }
    }

    val runtimeState: StateFlow<RuntimeState>
        get() = runtime.state
    val serverState: StateFlow<ServerState>
        get() = supervisor.state

    companion object {
        private const val START_RETRIES = 2
        private const val START_RETRY_BACKOFF_MS = 8_000L

        @Volatile
        private var instance: AppRuntime? = null

        /** Single graph per process; composed once from the activity. */
        fun obtain(context: Context, scope: CoroutineScope): AppRuntime =
            instance ?: synchronized(this) {
                instance ?: AppRuntime(context, scope).also { instance = it }
            }
    }
}
