package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeCommandHandler
import io.github.spectordev9.opencode.core.bridge.BridgeCommandNames
import io.github.spectordev9.opencode.core.bridge.BridgeError
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.PtyId
import io.github.spectordev9.opencode.core.common.ProcessId
import io.github.spectordev9.opencode.core.common.RuntimeId
import io.github.spectordev9.opencode.core.common.SecretRef
import io.github.spectordev9.opencode.core.common.TrashId
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticEvent
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticLevel
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticReport
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticsService
import io.github.spectordev9.opencode.core.gateway.GatewayHealth
import io.github.spectordev9.opencode.core.gateway.GatewayOperation
import io.github.spectordev9.opencode.core.gateway.OpenCodeGateway
import io.github.spectordev9.opencode.core.gateway.ServerEvent
import io.github.spectordev9.opencode.core.git.GitBranch
import io.github.spectordev9.opencode.core.git.GitChange
import io.github.spectordev9.opencode.core.git.GitChangeKind
import io.github.spectordev9.opencode.core.git.GitCommit
import io.github.spectordev9.opencode.core.git.GitCommitResult
import io.github.spectordev9.opencode.core.git.GitDiff
import io.github.spectordev9.opencode.core.git.GitService
import io.github.spectordev9.opencode.core.git.GitStatus
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.MessageInfo
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.model.SessionTime
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import io.github.spectordev9.opencode.core.opencode.client.PermissionResponse
import io.github.spectordev9.opencode.core.opencode.client.PromptInput
import io.github.spectordev9.opencode.core.opencode.client.PromptModel
import io.github.spectordev9.opencode.core.opencode.client.SessionFileDiff
import io.github.spectordev9.opencode.core.opencode.client.SessionModelRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessExit
import io.github.spectordev9.opencode.core.runtime.ProcessSpec
import io.github.spectordev9.opencode.core.runtime.RuntimeInfo
import io.github.spectordev9.opencode.core.runtime.RuntimeManager
import io.github.spectordev9.opencode.core.runtime.RuntimeProfile
import io.github.spectordev9.opencode.core.runtime.RuntimeState
import io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus
import io.github.spectordev9.opencode.core.security.LogScrubber
import io.github.spectordev9.opencode.core.security.LoopbackAuth
import io.github.spectordev9.opencode.core.security.LoopbackCredential
import io.github.spectordev9.opencode.core.security.SecretStore
import io.github.spectordev9.opencode.core.supervisor.ServerEndpoint
import io.github.spectordev9.opencode.core.supervisor.ServerHealth
import io.github.spectordev9.opencode.core.supervisor.ServerState
import io.github.spectordev9.opencode.core.supervisor.ServerStateStatus
import io.github.spectordev9.opencode.core.supervisor.ServerSupervisor
import io.github.spectordev9.opencode.core.terminal.PtyEvent
import io.github.spectordev9.opencode.core.terminal.PtySession
import io.github.spectordev9.opencode.core.terminal.PtyService
import io.github.spectordev9.opencode.core.terminal.PtySize
import io.github.spectordev9.opencode.core.terminal.PtySpec
import io.github.spectordev9.opencode.core.workspace.Chunk
import io.github.spectordev9.opencode.core.workspace.Entry
import io.github.spectordev9.opencode.core.workspace.EntryKind
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.WriteReceipt
import io.github.spectordev9.opencode.core.workspace.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID
import java.util.Base64
import kotlin.math.min

private fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { "%02x".format(it) }

private fun Map<String, Any?>.string(name: String): String =
    this[name]?.toString() ?: throw IllegalArgumentException("missing $name")

private fun Map<String, Any?>.optionalString(name: String): String? =
    this[name]?.toString()

private fun commandError(command: CommandEnvelope, apiError: ApiError): ResultEnvelope.Error =
    ResultEnvelope.Error(
        id = command.id,
        error = BridgeError(
            code = apiError.code,
            message = apiError.message,
            upstreamStatus = apiError.upstreamStatus,
        ),
    )

private fun commandOk(command: CommandEnvelope, value: Any?): ResultEnvelope.Ok<Any?> =
    ResultEnvelope.Ok(id = command.id, value = value)

class FakeRuntimeService : RuntimeManager {
    private val stateFlow = MutableStateFlow(
        RuntimeState(
            status = RuntimeStateStatus.READY,
            version = "1.19.3",
        ),
    )

    override val state: StateFlow<RuntimeState> = stateFlow.asStateFlow()
    override val profile: RuntimeProfile = RuntimeProfile.A_DIRECT

    override suspend fun ensureReady(): ApiResult<RuntimeInfo> = ApiResult.Success(
        RuntimeInfo(
            id = RuntimeId("fake-runtime"),
            version = "1.19.3",
            profile = profile,
            rootPath = "/data/local/tmp/opencode",
            manifestSha256 = "abc123",
            openCodeSha256 = "def456",
        ),
    )

    override fun spawn(spec: ProcessSpec): ApiResult<ManagedProcess> = ApiResult.Success(
        object : ManagedProcess {
            override val id: ProcessId = ProcessId(UUID.randomUUID().toString())
            override val pid: Long? = 1L
            override val isAlive: Boolean = true

            override fun signal(signal: Int): ApiResult<Unit> = ApiResult.Success(Unit)

            override suspend fun waitFor(): ProcessExit = ProcessExit(exitCode = 0, signal = null)

            override suspend fun terminate(graceMillis: Long): ApiResult<Unit> = ApiResult.Success(Unit)
        },
    )
}

class FakeServerSupervisor : ServerSupervisor {
    private val stateFlow = MutableStateFlow(
        ServerState(
            status = ServerStateStatus.HEALTHY,
            endpoint = ServerEndpoint("127.0.0.1", 4097),
            version = "1.19.3",
        ),
    )

    override val state: StateFlow<ServerState> = stateFlow.asStateFlow()

    override suspend fun start(): ApiResult<ServerEndpoint> {
        val endpoint = ServerEndpoint("127.0.0.1", 4097)
        stateFlow.value = ServerState(
            status = ServerStateStatus.HEALTHY,
            endpoint = endpoint,
            version = "1.19.3",
        )
        return ApiResult.Success(endpoint)
    }

    override suspend fun stop(graceMillis: Long): ApiResult<Unit> {
        stateFlow.value = ServerState(status = ServerStateStatus.STOPPED)
        return ApiResult.Success(Unit)
    }

    override suspend fun restart(): ApiResult<ServerEndpoint> = start()
}

class FakeWorkspaceService : WorkspaceService {
    private val files = linkedMapOf<String, ByteArray>()
    private val trash = linkedMapOf<String, ByteArray>()
    private val modifiedAt = linkedMapOf<String, Long>()

    override suspend fun list(path: String): ApiResult<List<Entry>> {
        val prefix = path.removeSuffix("/")
        val entries = files.keys
            .filter { it == prefix || it.startsWith(prefix + "/") }
            .map { fullPath ->
                val bytes = files[fullPath] ?: ByteArray(0)
                Entry(
                    path = fullPath,
                    name = fullPath.substringAfterLast('/'),
                    kind = EntryKind.FILE,
                    sizeBytes = bytes.size.toLong(),
                    modifiedAtEpochMillis = timestamp(fullPath),
                )
            }
        return ApiResult.Success(entries)
    }

    override suspend fun stat(path: String): ApiResult<Entry> {
        val bytes = files[path] ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing path $path"))
        return ApiResult.Success(
            Entry(
                path = path,
                name = path.substringAfterLast('/'),
                kind = EntryKind.FILE,
                sizeBytes = bytes.size.toLong(),
                modifiedAtEpochMillis = timestamp(path),
            ),
        )
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ApiResult<Chunk> {
        val bytes = files[path] ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing path $path"))
        val from = offset.coerceAtLeast(0L)
        val start = min(bytes.size.toLong(), from).toInt()
        val safeLength = maxOf(0, length)
        val to = min(bytes.size.toLong(), start.toLong() + safeLength.toLong()).toInt()
        val slice = bytes.copyOfRange(start, to)
        return ApiResult.Success(Chunk(offset = from, bytes = slice, endOfFile = to >= bytes.size))
    }

    override suspend fun write(path: String, data: ByteArray, expect: Precondition): ApiResult<WriteReceipt> {
        val previous = files[path]
        if (expect is Precondition.Match) {
            val matches = previous != null &&
                previous.size.toLong() == expect.sizeBytes &&
                timestamp(path) == expect.modifiedAtEpochMillis &&
                previous.sha256().equals(expect.sha256, ignoreCase = true)
            if (!matches) {
                return ApiResult.Failure(ApiError(ErrorCode.E_CONFLICT, "write precondition failed for $path"))
            }
        }
        files[path] = data.copyOf()
        modifiedAt[path] = System.currentTimeMillis()
        val updatedAt = timestamp(path)
        return ApiResult.Success(
            WriteReceipt(
                path = path,
                sizeBytes = data.size.toLong(),
                modifiedAtEpochMillis = updatedAt,
                sha256 = data.sha256(),
            ),
        )
    }

    override suspend fun mkdir(path: String): ApiResult<Entry> {
        files[path] = ByteArray(0)
        return ApiResult.Success(
            Entry(
                path = path,
                name = path.substringAfterLast('/'),
                kind = EntryKind.DIRECTORY,
                sizeBytes = 0L,
                modifiedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun rename(from: String, to: String): ApiResult<Entry> {
        val data = files.remove(from) ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing path $from"))
        val previousModifiedAt = modifiedAt.remove(from) ?: System.currentTimeMillis()
        files[to] = data
        modifiedAt[to] = previousModifiedAt
        return ApiResult.Success(
            Entry(
                path = to,
                name = to.substringAfterLast('/'),
                kind = EntryKind.FILE,
                sizeBytes = data.size.toLong(),
                modifiedAtEpochMillis = previousModifiedAt,
            ),
        )
    }

    override suspend fun move(from: String, to: String): ApiResult<Entry> = rename(from, to)

    override suspend fun trash(path: String): ApiResult<TrashId> {
        val data = files.remove(path) ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing path $path"))
        modifiedAt.remove(path)
        val id = TrashId(UUID.randomUUID().toString())
        trash[id.value] = data
        return ApiResult.Success(id)
    }

    override suspend fun restore(id: TrashId): ApiResult<Entry> {
        val data = trash.remove(id.value) ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing trash id $id"))
        val path = "/restored/${UUID.randomUUID()}"
        files[path] = data
        modifiedAt[path] = System.currentTimeMillis()
        return ApiResult.Success(
            Entry(path, path.substringAfterLast('/'), EntryKind.FILE, data.size.toLong(), timestamp(path)),
        )
    }

    override suspend fun search(path: String, query: String, limit: Int): ApiResult<List<Entry>> {
        val results = files.keys
            .filter { it.contains(query, ignoreCase = true) }
            .take(limit)
            .map { fullPath ->
                val data = files[fullPath] ?: ByteArray(0)
                Entry(fullPath, fullPath.substringAfterLast('/'), EntryKind.FILE, data.size.toLong(), System.currentTimeMillis())
            }
        return ApiResult.Success(results)
    }

    private fun timestamp(path: String): Long =
        modifiedAt.getOrPut(path, System::currentTimeMillis)
}

class FakeGitService : GitService {
    override suspend fun status(repositoryPath: String): ApiResult<GitStatus> = ApiResult.Success(
        GitStatus(
            branch = "main",
            commit = "abc123",
            ahead = 0,
            behind = 0,
            changes = listOf(
                GitChange(path = "$repositoryPath/README.md", kind = GitChangeKind.UNTRACKED, staged = false, unstaged = true),
            ),
        ),
    )

    override suspend fun diff(repositoryPath: String, path: String?, staged: Boolean): ApiResult<GitDiff> =
        ApiResult.Success(GitDiff(path = path, patch = "# fake diff\n", binary = false, truncated = false))

    override suspend fun initialize(repositoryPath: String): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun fetch(repositoryPath: String, remote: String?): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun pull(repositoryPath: String, remote: String?, branch: String?): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun push(repositoryPath: String, remote: String?, branch: String?): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun branches(repositoryPath: String): ApiResult<List<GitBranch>> = ApiResult.Success(
        listOf(GitBranch("main", true), GitBranch("feature/fake", false)),
    )

    override suspend fun checkout(repositoryPath: String, ref: String): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun stage(repositoryPath: String, paths: List<String>): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun unstage(repositoryPath: String, paths: List<String>): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun discardChanges(repositoryPath: String, paths: List<String>): ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun commit(repositoryPath: String, message: String, paths: List<String>): ApiResult<GitCommitResult> =
        ApiResult.Success(GitCommitResult(hash = "deadbeef", subject = message))

    override suspend fun history(repositoryPath: String, limit: Int): ApiResult<List<GitCommit>> =
        ApiResult.Success(emptyList())

    override suspend fun diffRevision(repositoryPath: String, from: String, to: String): ApiResult<GitDiff> =
        ApiResult.Success(GitDiff(path = null, patch = "# fake diff\n", binary = false, truncated = false))
}

class FakePtyService : PtyService {
    override fun open(spec: PtySpec): ApiResult<PtySession> {
        val session = object : PtySession {
            override val id: PtyId = PtyId(UUID.randomUUID().toString())
            override val events: Flow<PtyEvent> = MutableSharedFlow<PtyEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

            override fun write(bytes: ByteArray): ApiResult<Unit> = ApiResult.Success(Unit)

            override fun resize(size: PtySize): ApiResult<Unit> = ApiResult.Success(Unit)

            override fun signal(signal: Int): ApiResult<Unit> = ApiResult.Success(Unit)

            override fun close() = Unit
        }
        return ApiResult.Success(session)
    }
}

class FakeSecretStore : SecretStore {
    private val values = linkedMapOf<String, CharArray>()

    override suspend fun put(ref: SecretRef, secret: CharArray): ApiResult<Unit> {
        values[ref.value] = secret.copyOf()
        return ApiResult.Success(Unit)
    }

    override suspend fun get(ref: SecretRef): ApiResult<CharArray> =
        values[ref.value]?.copyOf()?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "missing secret ${ref.value}"))

    override suspend fun delete(ref: SecretRef): ApiResult<Unit> {
        values.remove(ref.value)
        return ApiResult.Success(Unit)
    }
}

class FakeLoopbackAuth : LoopbackAuth {
    override suspend fun provision(secretRef: SecretRef): ApiResult<LoopbackCredential> = ApiResult.Success(
        LoopbackCredential(username = "opencode", password = "opencode".toCharArray()),
    )

    override fun authorizationHeader(credential: LoopbackCredential): String =
        "Basic ${
            Base64.getEncoder().encodeToString(
                "${credential.username}:${credential.password.concatToString()}".toByteArray(),
            )
        }"

    override fun verify(authorizationHeader: String, credential: LoopbackCredential): Boolean {
        val encoded = authorizationHeader.removePrefix("Basic ")
        if (encoded == authorizationHeader) return false
        val decoded = try {
            Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = "${credential.username}:${credential.password.concatToString()}"
        return decoded == expected
    }
}

class FakeDiagnosticsService : DiagnosticsService {
    private val events = MutableSharedFlow<DiagnosticEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun record(event: DiagnosticEvent) {
        events.tryEmit(event)
    }

    override fun observe(): Flow<DiagnosticEvent> = events

    override suspend fun export(): ApiResult<DiagnosticReport> = ApiResult.Success(
        DiagnosticReport(
            generatedAtEpochMillis = System.currentTimeMillis(),
            appVersion = "0.0.0",
            runtimeVersion = "1.19.3",
            openCodeVersion = "1.19.3",
            events = emptyList(),
        ),
    )
}

class FakeOpenCodeClient : OpenCodeClient {
    val sessions = linkedMapOf(
        "ses_123" to Session(
            id = "ses_123",
            slug = "sample",
            projectID = "prj_1",
            directory = "/workspace",
            title = "Sample",
            version = "1.19.3",
            time = SessionTime(created = 1, updated = 2),
        ),
    )
    var lastPrompt: PromptInput? = null
    var promptCalls: Int = 0
    var lastPermission: Pair<String, PermissionResponse>? = null
    var healthResult: ApiResult<GatewayHealth> =
        ApiResult.Success(GatewayHealth(version = "1.19.3", endpointPort = 4097))
    private val serverEvents = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 32)

    override suspend fun listSessions(directory: String): ApiResult<List<Session>> =
        ApiResult.Success(sessions.values.toList())

    override suspend fun getSession(sessionID: String, directory: String): ApiResult<Session> =
        sessions[sessionID]?.let { ApiResult.Success(it) }
            ?: ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing session $sessionID"))

    override suspend fun createSession(
        directory: String,
        title: String?,
        parentID: String?,
        agent: String?,
        model: SessionModelRef?,
    ): ApiResult<Session> {
        val session = Session(
            id = "ses_new",
            slug = "new",
            projectID = "prj_1",
            directory = directory,
            title = title ?: "New session",
            version = "1.19.3",
            time = SessionTime(created = 3, updated = 3),
            parentID = parentID,
            agent = agent,
        )
        sessions[session.id] = session
        return ApiResult.Success(session)
    }

    override suspend fun updateSession(
        sessionID: String,
        directory: String,
        title: String?,
        archived: Boolean?,
    ): ApiResult<Session> {
        val session = sessions[sessionID]
            ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing session $sessionID"))
        val updated = session.copy(title = title ?: session.title)
        sessions[sessionID] = updated
        return ApiResult.Success(updated)
    }

    override suspend fun deleteSession(sessionID: String, directory: String): ApiResult<Boolean> =
        ApiResult.Success(sessions.remove(sessionID) != null)

    override suspend fun sessionStatuses(directory: String): ApiResult<JsonObject> =
        ApiResult.Success(
            buildJsonObject {
                put("ses_123", buildJsonObject { put("type", "idle") })
            },
        )

    override suspend fun forkSession(
        sessionID: String,
        directory: String,
        messageID: String?,
    ): ApiResult<Session> {
        val session = sessions[sessionID]
            ?: return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "missing session $sessionID"))
        val forked = session.copy(id = "ses_fork", parentID = sessionID)
        sessions[forked.id] = forked
        return ApiResult.Success(forked)
    }

    override suspend fun abortSession(sessionID: String, directory: String): ApiResult<Boolean> =
        ApiResult.Success(true)

    override suspend fun revertSession(
        sessionID: String,
        directory: String,
        messageID: String,
        partID: String?,
    ): ApiResult<Session> = getSession(sessionID, directory)

    override suspend fun unrevertSession(sessionID: String, directory: String): ApiResult<Session> =
        getSession(sessionID, directory)

    override suspend fun summarizeSession(
        sessionID: String,
        directory: String,
        providerID: String,
        modelID: String,
        auto: Boolean,
    ): ApiResult<Boolean> = ApiResult.Success(true)

    override suspend fun sessionDiff(
        sessionID: String,
        directory: String,
    ): ApiResult<List<SessionFileDiff>> = ApiResult.Success(
        listOf(
            SessionFileDiff(
                file = "README.md",
                patch = "@@ fake @@",
                additions = 1.0,
                deletions = 0.0,
                status = "modified",
            ),
        ),
    )

    override suspend fun listMessages(sessionID: String, directory: String): ApiResult<List<Message>> =
        ApiResult.Success(emptyList())

    override suspend fun promptAsync(
        sessionID: String,
        directory: String,
        prompt: PromptInput,
    ): ApiResult<Unit> {
        lastPrompt = prompt
        promptCalls++
        return ApiResult.Success(Unit)
    }

    override suspend fun runCommand(
        sessionID: String,
        directory: String,
        command: String,
        arguments: String,
        agent: String?,
        model: PromptModel?,
        messageID: String?,
    ): ApiResult<Message> = ApiResult.Success(message(sessionID))

    override suspend fun runShell(
        sessionID: String,
        directory: String,
        command: String,
        agent: String,
        model: PromptModel?,
        messageID: String?,
    ): ApiResult<Message> = ApiResult.Success(message(sessionID))

    override suspend fun replyPermission(
        sessionID: String,
        permissionID: String,
        directory: String,
        response: PermissionResponse,
    ): ApiResult<Boolean> {
        lastPermission = permissionID to response
        return ApiResult.Success(true)
    }

    override suspend fun listFiles(path: String, directory: String): ApiResult<List<io.github.spectordev9.opencode.core.model.FileEntry>> =
        ApiResult.Success(emptyList())

    override suspend fun <T> call(operation: GatewayOperation<T>): ApiResult<T> =
        ApiResult.Failure(ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "not used by fake client"))

    override fun events(directory: String?): Flow<ServerEvent> = serverEvents

    fun emitEvent(event: ServerEvent) {
        serverEvents.tryEmit(event)
    }

    override suspend fun health(): ApiResult<GatewayHealth> = healthResult

    private fun message(sessionID: String): Message = Message(
        info = MessageInfo(
            id = "msg_1",
            sessionID = sessionID,
            role = "assistant",
            time = buildJsonObject { put("created", 1L) },
        ),
        parts = emptyList(),
    )
}

class FakeGateway : OpenCodeGateway {
    override suspend fun <T> call(operation: GatewayOperation<T>): ApiResult<T> {
        val body = when (operation.name) {
            BridgeCommandNames.RUNTIME_STATUS -> "ready"
            BridgeCommandNames.SESSION_PROMPT_ASYNC -> "accepted"
            else -> "ok"
        }.toByteArray()
        return try {
            ApiResult.Success(operation.decoder.decode(200, body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            ApiResult.Failure(
                ApiError(
                    code = ErrorCode.E_UPSTREAM,
                    message = exception.message ?: "fake gateway response could not be decoded",
                ),
            )
        }
    }

    override fun events(directory: String?): Flow<ServerEvent> = MutableSharedFlow<ServerEvent>()

    override suspend fun health(): ApiResult<GatewayHealth> = ApiResult.Success(
        GatewayHealth(version = "1.19.3", endpointPort = 4097),
    )
}

class FakeLogScrubber : LogScrubber {
    override fun scrub(value: String): String = value
        .replace("(?i)password".toRegex(), "[REDACTED]")
        .replace("(?i)token".toRegex(), "[REDACTED]")
}

class AppBridgeCommandHandler(
    private val runtime: RuntimeManager = FakeRuntimeService(),
    private val supervisor: ServerSupervisor = FakeServerSupervisor(),
    private val workspace: WorkspaceService = FakeWorkspaceService(),
    private val git: GitService = FakeGitService(),
    private val terminal: PtyService = FakePtyService(),
    private val secrets: SecretStore = FakeSecretStore(),
    private val loopbackAuth: LoopbackAuth = FakeLoopbackAuth(),
    private val diagnostics: DiagnosticsService = FakeDiagnosticsService(),
    private val gateway: OpenCodeGateway = FakeGateway(),
    private val scrubber: LogScrubber = FakeLogScrubber(),
) : BridgeCommandHandler {
    override suspend fun handle(command: CommandEnvelope): ResultEnvelope {
        return when (command.name) {
            BridgeCommandNames.RUNTIME_STATUS -> {
                val state = runtime.state.value
                commandOk(
                    command,
                    mapOf(
                        "status" to state.status.name.lowercase(Locale.ROOT),
                        "version" to (state.version ?: "unknown"),
                    ),
                )
            }
            BridgeCommandNames.RUNTIME_START -> when (val ready = runtime.ensureReady()) {
                is ApiResult.Success -> commandOk(command, mapOf("status" to "ready", "id" to ready.value.id.value))
                is ApiResult.Failure -> commandError(command, ready.error)
            }
            BridgeCommandNames.RUNTIME_EXPORT_DIAGNOSTICS -> when (val report = diagnostics.export()) {
                is ApiResult.Success -> commandOk(command, report.value)
                is ApiResult.Failure -> commandError(command, report.error)
            }
            BridgeCommandNames.SESSION_PROMPT_ASYNC -> {
                val sessionId = command.args.string("sessionId")
                val prompt = command.args.string("prompt")
                commandOk(command, mapOf("sessionId" to sessionId, "prompt" to scrubber.scrub(prompt), "accepted" to true))
            }
            BridgeCommandNames.WORKSPACE_LIST -> when (val result = workspace.list(command.args.string("path"))) {
                is ApiResult.Success -> commandOk(command, result.value)
                is ApiResult.Failure -> commandError(command, result.error)
            }
            BridgeCommandNames.WORKSPACE_WRITE -> {
                val path = command.args.string("path")
                val data = (command.args["content"] ?: command.args["data"] ?: "").toString().toByteArray()
                when (val result = workspace.write(path, data, Precondition.None)) {
                    is ApiResult.Success -> commandOk(command, result.value)
                    is ApiResult.Failure -> commandError(command, result.error)
                }
            }
            BridgeCommandNames.WORKSPACE_READ_RANGE -> {
                val path = command.args.string("path")
                val offset = command.args["offset"]?.toString()?.toLongOrNull() ?: 0L
                val length = command.args["length"]?.toString()?.toIntOrNull() ?: 4096
                when (val result = workspace.readRange(path, offset, length)) {
                    is ApiResult.Success -> commandOk(command, result.value)
                    is ApiResult.Failure -> commandError(command, result.error)
                }
            }
            BridgeCommandNames.GIT_STATUS -> when (val result = git.status(command.args.string("repositoryPath"))) {
                is ApiResult.Success -> commandOk(command, result.value)
                is ApiResult.Failure -> commandError(command, result.error)
            }
            BridgeCommandNames.TERMINAL_OPEN -> {
                val executable = command.args.string("executable")
                val workingDir = command.args.string("workingDirectory")
                val rows = command.args["rows"]?.toString()?.toIntOrNull() ?: 24
                val cols = command.args["columns"]?.toString()?.toIntOrNull() ?: 80
                when (
                    val result = terminal.open(
                        PtySpec(
                            executable = executable,
                            workingDirectory = workingDir,
                            initialSize = PtySize(rows = rows, columns = cols),
                            name = command.args.optionalString("name"),
                        ),
                    )
                ) {
                    is ApiResult.Success -> commandOk(command, mapOf("ptyId" to result.value.id.value))
                    is ApiResult.Failure -> commandError(command, result.error)
                }
            }
            BridgeCommandNames.SETTINGS_GET -> commandOk(command, mapOf("key" to (command.args["key"]?.toString() ?: ""), "value" to "default"))
            BridgeCommandNames.SETTINGS_SET -> commandOk(command, mapOf("key" to command.args.string("key"), "value" to command.args["value"].toString()))
            else -> commandError(command, ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "unsupported command: ${command.name}"))
        }
    }
}
