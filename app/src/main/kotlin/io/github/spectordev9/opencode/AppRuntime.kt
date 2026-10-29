package io.github.spectordev9.opencode

import android.content.Context
import io.github.spectordev9.opencode.chat.ChatController
import io.github.spectordev9.opencode.chat.ChatTaskEvent
import io.github.spectordev9.opencode.chat.ChatTaskEventListener
import io.github.spectordev9.opencode.chat.FilesController
import io.github.spectordev9.opencode.chat.SharedPreferencesChatSessionStore
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticLevel
import io.github.spectordev9.opencode.features.AdvancedTools
import io.github.spectordev9.opencode.features.classifyFailure
import io.github.spectordev9.opencode.features.BuildController
import io.github.spectordev9.opencode.features.DeviceCompatibility
import io.github.spectordev9.opencode.features.DiagnosticsController
import io.github.spectordev9.opencode.features.ProjectEnvironmentStore
import io.github.spectordev9.opencode.features.ServerExtensionsController
import io.github.spectordev9.opencode.features.TaskNotification
import io.github.spectordev9.opencode.features.TaskNotificationKind
import io.github.spectordev9.opencode.features.TaskNotifier
import io.github.spectordev9.opencode.features.WorkspaceCatalog
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

    private val sessionStore = SharedPreferencesChatSessionStore(this.context)

    val diagnostics = DiagnosticsController(this.context)

    val notifier = TaskNotifier(this.context)

    val projects = WorkspaceCatalog(
        this.context,
        RuntimePaths.serverWorkspaceDirectory(this.context).also {
            Files.createDirectories(it)
        },
    )

    val environment = ProjectEnvironmentStore(
        this.context,
        AndroidKeystoreSecretStore(this.context),
    )

    val chat = ChatController(
        scope,
        sessionStore,
        io.github.spectordev9.opencode.chat.SharedPrefsPermissionRuleStore(this.context),
        ChatTaskEventListener { event -> onChatTaskEvent(event) },
    )
    val files = FilesController(scope)
    private val workspaceDirectory = RuntimePaths.workspaceDirectory(this.context).also {
        Files.createDirectories(it)
    }

    /** Local workspace service (F52 instruction editing, git conflict flows). */
    val workspace: io.github.spectordev9.opencode.core.workspace.WorkspaceService =
        io.github.spectordev9.opencode.core.workspace.impl.FileWorkspaceService(workspaceDirectory)

    /** Guest-shell git for the workspace repository (F31–F35, F36). */
    val git = io.github.spectordev9.opencode.chat.GitController(
        scope = scope,
        git = io.github.spectordev9.opencode.core.git.impl.ShellGitService(
            io.github.spectordev9.opencode.git.GuestGitRunner(this.context),
        ),
        repositoryPath = io.github.spectordev9.opencode.git.GuestGitRunner.GUEST_WORKSPACE,
        workspace = workspace,
    )

    /** User-interface preferences (theme mode, font sizes) persisted on-device. */
    val uiPrefs = io.github.spectordev9.opencode.ui.UiPrefs(this.context)

    /** Saved server endpoints (F72); tokens stay in a separate preference file. */
    val profiles = io.github.spectordev9.opencode.chat.SharedPrefsServerProfileStore(this.context)

    /** Individual data-cleanup actions (F78). */
    val cleaner = io.github.spectordev9.opencode.chat.DeviceDataCleaner(this.context, sessionStore, profiles)

    val extensions = ServerExtensionsController(scope)

    val builds = BuildController(
        scope = scope,
        ptyService = terminal,
        workspaceDirectory = workspaceDirectory,
        environment = { environment.resolve(projects.activeDirectory()) },
    )

    val advanced = AdvancedTools(
        extensions = extensions,
        projects = projects,
        environment = environment,
        builds = builds,
        diagnostics = diagnostics,
        notifier = notifier,
        selectProject = ::selectProject,
        createProject = ::createProject,
        compatibilityProbe = { DeviceCompatibility.probe(this.context) },
    )

    private var lastAttachedEndpoint: String? = null
    private var currentClient: io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient? = null
    private var remoteProfileName: String? = null
    private var pendingSessionId: String? = null

    init {
        notifier.ensureChannel()
        // The mock provider must be listening before the server's first prompt.
        mockLlm.start()
        scope.launch {
            supervisor.state.collect { state -> onServerState(state) }
        }
    }

    private fun onServerState(state: ServerState) {
        // An explicitly connected remote profile stays authoritative until the
        // user switches back to the local runtime (F73).
        if (remoteProfileName != null) return
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
            currentClient = client
            attachWorkspace(client)
            lastAttachedEndpoint = endpointKey
            pendingSessionId?.let { sessionID ->
                pendingSessionId = null
                scope.launch { chat.selectSession(sessionID) }
            }
            diagnostics.record(
                DiagnosticLevel.INFO,
                "server",
                "Connected to loopback server",
            )
        } else {
            if (lastAttachedEndpoint != null) {
                diagnostics.record(
                    DiagnosticLevel.WARN,
                    "server",
                    "Server connection lost",
                )
            }
            chat.detach()
            files.detach()
            extensions.detach()
            currentClient = null
            lastAttachedEndpoint = null
        }
    }

    private fun attachWorkspace(
        client: io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient,
        directoryOverride: String? = null,
    ) {
        val directory = directoryOverride ?: projects.activeDirectory()
        chat.attach(client, directory)
        files.attach(client, directory)
        extensions.attach(client, directory)
    }

    /**
     * Connects to a saved remote OpenCode endpoint (F30/F72-F74). The remote
     * directory is treated as a server-side path and is never resolved on the
     * device. Credentials are used only for the HTTP client.
     */
    suspend fun connectProfile(name: String): String {
        val profile = profiles.profiles.value.firstOrNull { it.name == name }
            ?: return "Profile not found: $name"
        val path = runCatching { java.net.URI(profile.url).path }.getOrNull().orEmpty()
        if (path.isNotBlank() && path != "/") {
            return "Custom base paths are not supported; use scheme://host:port"
        }
        val token = profiles.tokenFor(name).orEmpty()
        val client = io.github.spectordev9.opencode.core.opencode.client.OpenCodeHttpClient(
            baseUrl = profile.url,
            password = token.ifBlank { "opencode" },
        )
        return when (val health = client.health()) {
            is ApiResult.Success -> {
                remoteProfileName = name
                currentClient = client
                lastAttachedEndpoint = null
                attachWorkspace(client, profile.directory.ifBlank { "." })
                profiles.select(name)
                diagnostics.record(
                    DiagnosticLevel.INFO,
                    "server",
                    "Connected to remote profile",
                    mapOf("profile" to name),
                )
                "Connected to ${profile.name} (OpenCode ${health.value.version})"
            }
            is ApiResult.Failure -> {
                diagnostics.record(
                    DiagnosticLevel.ERROR,
                    "server",
                    "Remote connection failed",
                    mapOf("profile" to name, "reason" to (health.error.message ?: "unknown")),
                )
                "Remote connection failed: ${health.error.message}"
            }
        }
    }

    /** Returns to the supervised local runtime (F73). */
    fun disconnectProfile() {
        remoteProfileName = null
        currentClient = null
        chat.detach()
        files.detach()
        extensions.detach()
        lastAttachedEndpoint = null
        val state = supervisor.state.value
        if (state.status == io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.HEALTHY) {
            onServerState(state)
        } else {
            ensureRunning()
        }
    }

    fun activeRemoteProfile(): String? = remoteProfileName

    /** Routes a deep link to a session after the server is attached (F61). */
    fun openSession(sessionID: String) {
        val clean = sessionID.trim()
        if (clean.isEmpty() || clean.length > 256) return
        if (currentClient == null) {
            pendingSessionId = clean
        } else {
            scope.launch { chat.selectSession(clean) }
        }
    }

    /** Switches the active local project folder and reattaches chat/files (F10). */
    fun selectProject(id: String): ApiResult<io.github.spectordev9.opencode.features.ProjectWorkspace> {
        if (remoteProfileName != null) {
            return ApiResult.Failure(
                io.github.spectordev9.opencode.core.common.ApiError(
                    io.github.spectordev9.opencode.core.common.ErrorCode.E_CONFLICT,
                    "Disconnect the remote profile before switching local projects",
                ),
            )
        }
        val result = projects.select(id)
        if (result is ApiResult.Success) {
            currentClient?.let(::attachWorkspace)
            builds.clear()
        }
        return result
    }

    /** Creates and selects a project folder inside the server workspace (F10). */
    fun createProject(name: String): ApiResult<io.github.spectordev9.opencode.features.ProjectWorkspace> {
        if (remoteProfileName != null) {
            return ApiResult.Failure(
                io.github.spectordev9.opencode.core.common.ApiError(
                    io.github.spectordev9.opencode.core.common.ErrorCode.E_CONFLICT,
                    "Disconnect the remote profile before creating local projects",
                ),
            )
        }
        val result = projects.create(name)
        if (result is ApiResult.Success) {
            currentClient?.let(::attachWorkspace)
            builds.clear()
        }
        return result
    }

    /** Reconciles authoritative state after the app returns to the foreground (F48). */
    fun onForeground() {
        if (remoteProfileName != null) {
            scope.launch { chat.reconcile() }
            return
        }
        if (supervisor.state.value.status ==
            io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.HEALTHY
        ) {
            scope.launch { chat.reconcile() }
        } else {
            diagnostics.record(DiagnosticLevel.WARN, "lifecycle", "Foreground resume without a healthy server")
            ensureRunning()
        }
    }

    private fun onChatTaskEvent(event: ChatTaskEvent) {
        notifier.notify(
            TaskNotification(
                kind = when (event.kind) {
                    io.github.spectordev9.opencode.chat.ChatTaskEventKind.COMPLETED ->
                        TaskNotificationKind.COMPLETED
                    io.github.spectordev9.opencode.chat.ChatTaskEventKind.PERMISSION ->
                        TaskNotificationKind.PERMISSION
                    io.github.spectordev9.opencode.chat.ChatTaskEventKind.FAILED ->
                        TaskNotificationKind.FAILED
                },
                title = event.title,
                body = event.body,
                sessionID = event.sessionID,
            ),
        )
        diagnostics.record(
            level = when (event.kind) {
                io.github.spectordev9.opencode.chat.ChatTaskEventKind.FAILED -> DiagnosticLevel.ERROR
                io.github.spectordev9.opencode.chat.ChatTaskEventKind.PERMISSION -> DiagnosticLevel.WARN
                io.github.spectordev9.opencode.chat.ChatTaskEventKind.COMPLETED -> DiagnosticLevel.INFO
            },
            category = "task",
            message = event.title,
            attributes = buildMap {
                put("kind", event.kind.name)
                put("session", event.sessionID ?: "none")
                if (event.kind == io.github.spectordev9.opencode.chat.ChatTaskEventKind.FAILED) {
                    val classification = classifyFailure(event.body)
                    put("failure_class", classification.kind.name)
                    put("retryable", classification.retryable.toString())
                }
            },
        )
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
