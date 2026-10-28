package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeCommandHandler
import io.github.spectordev9.opencode.core.bridge.BridgeCommandNames
import io.github.spectordev9.opencode.core.bridge.BridgeError
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.TrashId
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticsService
import io.github.spectordev9.opencode.core.git.GitService
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import io.github.spectordev9.opencode.core.opencode.client.PermissionResponse
import io.github.spectordev9.opencode.core.opencode.client.PromptInput
import io.github.spectordev9.opencode.core.opencode.client.PromptModel
import io.github.spectordev9.opencode.core.opencode.client.PromptPart
import io.github.spectordev9.opencode.core.opencode.client.SessionModelRef
import io.github.spectordev9.opencode.core.runtime.RuntimeManager
import io.github.spectordev9.opencode.core.security.LogScrubber
import io.github.spectordev9.opencode.core.supervisor.ServerSupervisor
import io.github.spectordev9.opencode.core.terminal.PtyService
import io.github.spectordev9.opencode.core.terminal.PtySession
import io.github.spectordev9.opencode.core.terminal.PtySize
import io.github.spectordev9.opencode.core.terminal.PtySpec
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.WorkspaceService
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private fun Map<String, Any?>.string(name: String): String =
    this[name]?.toString() ?: throw IllegalArgumentException("missing $name")

private fun Map<String, Any?>.optionalString(name: String): String? =
    this[name]?.toString()

private fun Map<String, Any?>.int(name: String, default: Int): Int =
    this[name]?.let { value ->
        (value as? Number)?.toInt() ?: value.toString().toIntOrNull()
    } ?: default

private fun Map<String, Any?>.long(name: String, default: Long): Long =
    this[name]?.let { value ->
        (value as? Number)?.toLong() ?: value.toString().toLongOrNull()
    } ?: default

private fun Map<String, Any?>.boolean(name: String, default: Boolean): Boolean =
    this[name]?.let { value ->
        when (value) {
            is Boolean -> value
            else -> value.toString().toBooleanStrictOrNull()
        }
    } ?: default

private fun Map<String, Any?>.strings(name: String): List<String> =
    when (val value = this[name]) {
        null -> emptyList()
        is List<*> -> value.map { it.toString() }
        else -> listOf(value.toString())
    }

private fun Map<String, Any?>.bytes(name: String): ByteArray =
    when (val value = this[name]) {
        null -> ByteArray(0)
        is ByteArray -> value
        else -> value.toString().toByteArray(Charsets.UTF_8)
    }

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

private fun <T> commandResult(command: CommandEnvelope, result: ApiResult<T>): ResultEnvelope =
    when (result) {
        is ApiResult.Success -> commandOk(command, result.value)
        is ApiResult.Failure -> commandError(command, result.error)
    }

private fun unsupported(command: CommandEnvelope): ResultEnvelope.Error =
    commandError(
        command,
        ApiError(
            ErrorCode.E_UNSUPPORTED_VERSION,
            "command is not implemented in the G1 composition: ${command.name}",
        ),
    )

private fun Map<String, Any?>.sessionID(): String =
    optionalString("sessionId") ?: string("sessionID")

private fun Map<String, Any?>.promptModel(name: String = "model"): PromptModel? {
    val model = this[name] as? Map<*, *> ?: return null
    return PromptModel(
        providerID = model["providerID"]?.toString()
            ?: throw IllegalArgumentException("missing $name.providerID"),
        modelID = model["modelID"]?.toString()
            ?: throw IllegalArgumentException("missing $name.modelID"),
    )
}

private fun Map<String, Any?>.sessionModel(): SessionModelRef? {
    val model = this["model"] as? Map<*, *> ?: return null
    return SessionModelRef(
        id = model["id"]?.toString() ?: throw IllegalArgumentException("missing model.id"),
        providerID = model["providerID"]?.toString()
            ?: throw IllegalArgumentException("missing model.providerID"),
        variant = model["variant"]?.toString(),
    )
}

private fun parsePrompt(args: Map<String, Any?>): PromptInput {
    val parts = (args["parts"] as? List<*>)
        ?.map { part ->
            val value = part as? Map<*, *>
                ?: throw IllegalArgumentException("prompt parts must be objects")
            val type = value["type"]?.toString() ?: "text"
            when (type) {
                "text" -> PromptPart(
                    text = value["text"]?.toString()
                        ?: throw IllegalArgumentException("missing prompt part text"),
                )
                "file" -> PromptPart(
                    type = type,
                    mime = value["mime"]?.toString()
                        ?: throw IllegalArgumentException("missing file prompt part mime"),
                    filename = value["filename"]?.toString(),
                    url = value["url"]?.toString()
                        ?: throw IllegalArgumentException("missing file prompt part url"),
                )
                else -> throw IllegalArgumentException("unsupported prompt part type: $type")
            }
        }
        ?: listOf(PromptPart(text = args.string("prompt")))
    require(parts.isNotEmpty()) { "prompt requires at least one part" }
    return PromptInput(
        parts = parts,
        messageID = args.optionalString("messageId"),
        model = args.promptModel(),
        agent = args.optionalString("agent"),
        noReply = args["noReply"]?.let { it as? Boolean },
        system = args.optionalString("system"),
        variant = args.optionalString("variant"),
    )
}

private fun parsePermissionResponse(args: Map<String, Any?>): PermissionResponse {
    val response = (args.optionalString("response") ?: args.string("reply"))
        .lowercase(Locale.ROOT)
    return when (response) {
        "once" -> PermissionResponse.ONCE
        "always" -> PermissionResponse.ALWAYS
        "reject" -> PermissionResponse.REJECT
        else -> throw IllegalArgumentException("invalid permission response: $response")
    }
}

private fun parsePrecondition(args: Map<String, Any?>): Precondition {
    val expected = args["expect"] as? Map<*, *> ?: run {
        val size = args["expectSizeBytes"] ?: return Precondition.None
        val modified = args["expectModifiedAtEpochMillis"]
        val sha = args["expectSha256"]
        return Precondition.Match(
            sizeBytes = (size as? Number)?.toLong() ?: size.toString().toLong(),
            modifiedAtEpochMillis = (modified as? Number)?.toLong() ?: modified.toString().toLong(),
            sha256 = sha.toString(),
        )
    }
    return Precondition.Match(
        sizeBytes = (expected["sizeBytes"] as? Number)?.toLong()
            ?: expected["sizeBytes"].toString().toLong(),
        modifiedAtEpochMillis = (expected["modifiedAtEpochMillis"] as? Number)?.toLong()
            ?: expected["modifiedAtEpochMillis"].toString().toLong(),
        sha256 = expected["sha256"].toString(),
    )
}

class AppBridgeHandler(
    private val runtime: RuntimeManager,
    private val supervisor: ServerSupervisor,
    private val workspace: WorkspaceService,
    private val git: GitService,
    private val terminal: PtyService,
    private val client: OpenCodeClient,
    private val diagnostics: DiagnosticsService,
    private val scrubber: LogScrubber,
) : BridgeCommandHandler {
    private val terminalSessions = ConcurrentHashMap<String, PtySession>()

    override suspend fun handle(command: CommandEnvelope): ResultEnvelope {
        return when (command.name) {
            BridgeCommandNames.RUNTIME_STATUS -> runtimeStatus(command)
            BridgeCommandNames.RUNTIME_START -> startRuntime(command)
            BridgeCommandNames.RUNTIME_STOP -> stopRuntime(command)
            BridgeCommandNames.RUNTIME_RESTART -> restartRuntime(command)
            BridgeCommandNames.RUNTIME_EXPORT_DIAGNOSTICS -> exportDiagnostics(command)
            BridgeCommandNames.SESSION_LIST -> commandResult(
                command,
                client.listSessions(command.args.string("directory")),
            )
            BridgeCommandNames.SESSION_GET -> commandResult(
                command,
                client.getSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                ),
            )
            BridgeCommandNames.SESSION_CREATE -> commandResult(
                command,
                client.createSession(
                    directory = command.args.string("directory"),
                    title = command.args.optionalString("title"),
                    parentID = command.args.optionalString("parentId"),
                    agent = command.args.optionalString("agent"),
                    model = command.args.sessionModel(),
                ),
            )
            BridgeCommandNames.SESSION_UPDATE -> commandResult(
                command,
                client.updateSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                    title = command.args.optionalString("title"),
                    archived = command.args["archived"] as? Boolean,
                ),
            )
            BridgeCommandNames.SESSION_DELETE -> commandResult(
                command,
                client.deleteSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                ),
            )
            BridgeCommandNames.SESSION_STATUS -> commandResult(
                command,
                client.sessionStatuses(command.args.string("directory")),
            )
            BridgeCommandNames.SESSION_FORK -> commandResult(
                command,
                client.forkSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                    messageID = command.args.optionalString("messageId"),
                ),
            )
            BridgeCommandNames.SESSION_ABORT -> commandResult(
                command,
                client.abortSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                ),
            )
            BridgeCommandNames.SESSION_REVERT -> commandResult(
                command,
                client.revertSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                    messageID = command.args.string("messageId"),
                    partID = command.args.optionalString("partId"),
                ),
            )
            BridgeCommandNames.SESSION_UNREVERT -> commandResult(
                command,
                client.unrevertSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                ),
            )
            BridgeCommandNames.SESSION_SUMMARIZE -> commandResult(
                command,
                client.summarizeSession(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                    providerID = command.args.string("providerId"),
                    modelID = command.args.string("modelId"),
                    auto = command.args.boolean("auto", false),
                ),
            )
            BridgeCommandNames.SESSION_DIFF -> commandResult(
                command,
                client.sessionDiff(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                ),
            )
            BridgeCommandNames.SESSION_MESSAGES -> commandResult(
                command,
                client.listMessages(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                ),
            )
            BridgeCommandNames.SESSION_PROMPT_ASYNC -> unitResult(
                command,
                client.promptAsync(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                    prompt = parsePrompt(command.args),
                ),
            )
            BridgeCommandNames.SESSION_COMMAND -> commandResult(
                command,
                client.runCommand(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                    command = command.args.string("command"),
                    arguments = command.args.optionalString("arguments") ?: "",
                    agent = command.args.optionalString("agent"),
                    model = command.args.promptModel(),
                    messageID = command.args.optionalString("messageId"),
                ),
            )
            BridgeCommandNames.SESSION_SHELL -> commandResult(
                command,
                client.runShell(
                    sessionID = command.args.sessionID(),
                    directory = command.args.string("directory"),
                    command = command.args.string("command"),
                    agent = command.args.string("agent"),
                    model = command.args.promptModel(),
                    messageID = command.args.optionalString("messageId"),
                ),
            )
            BridgeCommandNames.SESSION_PERMISSION_REPLY -> commandResult(
                command,
                client.replyPermission(
                    sessionID = command.args.sessionID(),
                    permissionID = command.args.string("permissionId"),
                    directory = command.args.string("directory"),
                    response = parsePermissionResponse(command.args),
                ),
            )
            BridgeCommandNames.WORKSPACE_LIST -> commandResult(
                command,
                workspace.list(command.args.optionalString("path") ?: "/"),
            )
            BridgeCommandNames.WORKSPACE_STAT -> commandResult(
                command,
                workspace.stat(command.args.string("path")),
            )
            BridgeCommandNames.WORKSPACE_READ_RANGE -> commandResult(
                command,
                workspace.readRange(
                    path = command.args.string("path"),
                    offset = command.args.long("offset", 0L),
                    length = command.args.int("length", 4096),
                ),
            )
            BridgeCommandNames.WORKSPACE_WRITE -> commandResult(
                command,
                workspace.write(
                    path = command.args.string("path"),
                    data = command.args["dataBase64"]?.toString()?.let {
                        Base64.getDecoder().decode(it)
                    } ?: command.args["content"]?.toString()?.toByteArray(Charsets.UTF_8)
                        ?: command.args["data"]?.let { command.args.bytes("data") }
                        ?: ByteArray(0),
                    expect = parsePrecondition(command.args),
                ),
            )
            BridgeCommandNames.WORKSPACE_MKDIR -> commandResult(
                command,
                workspace.mkdir(command.args.string("path")),
            )
            BridgeCommandNames.WORKSPACE_RENAME -> commandResult(
                command,
                workspace.rename(command.args.string("from"), command.args.string("to")),
            )
            BridgeCommandNames.WORKSPACE_MOVE -> commandResult(
                command,
                workspace.move(command.args.string("from"), command.args.string("to")),
            )
            BridgeCommandNames.WORKSPACE_TRASH -> commandResult(
                command,
                workspace.trash(command.args.string("path")),
            )
            BridgeCommandNames.WORKSPACE_RESTORE -> commandResult(
                command,
                workspace.restore(TrashId(command.args.string("id"))),
            )
            BridgeCommandNames.WORKSPACE_SEARCH -> commandResult(
                command,
                workspace.search(
                    path = command.args.optionalString("path") ?: "/",
                    query = command.args.string("query"),
                    limit = command.args.int("limit", 100),
                ),
            )
            BridgeCommandNames.GIT_STATUS -> commandResult(
                command,
                git.status(command.args.string("repositoryPath")),
            )
            BridgeCommandNames.GIT_DIFF -> commandResult(
                command,
                git.diff(
                    repositoryPath = command.args.string("repositoryPath"),
                    path = command.args.optionalString("path"),
                    staged = command.args.boolean("staged", false),
                ),
            )
            BridgeCommandNames.GIT_INIT -> unitResult(
                command,
                git.initialize(command.args.string("repositoryPath")),
            )
            BridgeCommandNames.GIT_FETCH -> unitResult(
                command,
                git.fetch(
                    repositoryPath = command.args.string("repositoryPath"),
                    remote = command.args.optionalString("remote"),
                ),
            )
            BridgeCommandNames.GIT_PULL -> unitResult(
                command,
                git.pull(
                    repositoryPath = command.args.string("repositoryPath"),
                    remote = command.args.optionalString("remote"),
                    branch = command.args.optionalString("branch"),
                ),
            )
            BridgeCommandNames.GIT_BRANCHES -> commandResult(
                command,
                git.branches(command.args.string("repositoryPath")),
            )
            BridgeCommandNames.GIT_CHECKOUT -> unitResult(
                command,
                git.checkout(
                    repositoryPath = command.args.string("repositoryPath"),
                    ref = command.args.string("ref"),
                ),
            )
            BridgeCommandNames.GIT_COMMIT -> commandResult(
                command,
                git.commit(
                    repositoryPath = command.args.string("repositoryPath"),
                    message = command.args.string("message"),
                    paths = command.args.strings("paths"),
                ),
            )
            BridgeCommandNames.TERMINAL_OPEN -> openTerminal(command)
            BridgeCommandNames.TERMINAL_WRITE -> writeTerminal(command)
            BridgeCommandNames.TERMINAL_RESIZE -> resizeTerminal(command)
            BridgeCommandNames.TERMINAL_SIGNAL -> signalTerminal(command)
            BridgeCommandNames.TERMINAL_CLOSE -> closeTerminal(command)
            else -> unsupported(command)
        }
    }

    private fun runtimeStatus(command: CommandEnvelope): ResultEnvelope {
        val runtimeState = runtime.state.value
        val serverState = supervisor.state.value
        return commandOk(
            command,
            mapOf(
                "status" to runtimeState.status.name.lowercase(Locale.ROOT),
                "runtimeVersion" to (runtimeState.version ?: "unknown"),
                "serverStatus" to serverState.status.name.lowercase(Locale.ROOT),
                "serverVersion" to (serverState.version ?: "unknown"),
                "endpointHost" to serverState.endpoint?.host,
                "endpointPort" to serverState.endpoint?.port,
                "reason" to scrubber.scrub(serverState.reason ?: runtimeState.reason ?: ""),
            ),
        )
    }

    private suspend fun startRuntime(command: CommandEnvelope): ResultEnvelope {
        return when (val ready = runtime.ensureReady()) {
            is ApiResult.Failure -> commandError(command, ready.error)
            is ApiResult.Success -> when (val started = supervisor.start()) {
                is ApiResult.Failure -> commandError(command, started.error)
                is ApiResult.Success -> commandOk(
                    command,
                    mapOf(
                        "status" to "ready",
                        "runtimeId" to ready.value.id.value,
                        "runtimeVersion" to ready.value.version,
                        "version" to scrubber.scrub(
                            supervisor.state.value.version ?: ready.value.version,
                        ),
                        "endpointHost" to started.value.host,
                        "endpointPort" to started.value.port,
                    ),
                )
            }
        }
    }

    private suspend fun stopRuntime(command: CommandEnvelope): ResultEnvelope =
        unitResult(command, supervisor.stop())

    private suspend fun restartRuntime(command: CommandEnvelope): ResultEnvelope {
        return when (val ready = runtime.ensureReady()) {
            is ApiResult.Failure -> commandError(command, ready.error)
            is ApiResult.Success -> when (val restarted = supervisor.restart()) {
                is ApiResult.Failure -> commandError(command, restarted.error)
                is ApiResult.Success -> commandOk(
                    command,
                    mapOf(
                        "status" to "ready",
                        "runtimeVersion" to ready.value.version,
                        "endpointHost" to restarted.value.host,
                        "endpointPort" to restarted.value.port,
                    ),
                )
            }
        }
    }

    private suspend fun exportDiagnostics(command: CommandEnvelope): ResultEnvelope =
        commandResult(command, diagnostics.export())

    private fun openTerminal(command: CommandEnvelope): ResultEnvelope {
        val spec = PtySpec(
            executable = command.args.string("executable"),
            arguments = command.args.strings("arguments"),
            environment = (command.args["environment"] as? Map<*, *>)
                ?.entries
                ?.associate { it.key.toString() to it.value.toString() }
                ?: emptyMap(),
            workingDirectory = command.args.string("workingDirectory"),
            initialSize = PtySize(
                columns = command.args.int("columns", 80),
                rows = command.args.int("rows", 24),
            ),
            name = command.args.optionalString("name"),
        )
        return when (val result = terminal.open(spec)) {
            is ApiResult.Failure -> commandError(command, result.error)
            is ApiResult.Success -> {
                terminalSessions[result.value.id.value] = result.value
                commandOk(
                    command,
                    mapOf(
                        "ptyId" to result.value.id.value,
                        "name" to spec.name,
                    ),
                )
            }
        }
    }

    private fun writeTerminal(command: CommandEnvelope): ResultEnvelope {
        val session = terminalSessions[command.args.string("ptyId")]
            ?: return commandError(command, ApiError(ErrorCode.E_CONFLICT, "unknown terminal session"))
        val bytes = command.args["dataBase64"]?.toString()?.let {
            Base64.getDecoder().decode(it)
        } ?: command.args.bytes("data")
        return unitResult(command, session.write(bytes))
    }

    private fun resizeTerminal(command: CommandEnvelope): ResultEnvelope {
        val session = terminalSessions[command.args.string("ptyId")]
            ?: return commandError(command, ApiError(ErrorCode.E_CONFLICT, "unknown terminal session"))
        return unitResult(
            command,
            session.resize(
                PtySize(
                    columns = command.args.int("columns", 80),
                    rows = command.args.int("rows", 24),
                ),
            ),
        )
    }

    private fun signalTerminal(command: CommandEnvelope): ResultEnvelope {
        val session = terminalSessions[command.args.string("ptyId")]
            ?: return commandError(command, ApiError(ErrorCode.E_CONFLICT, "unknown terminal session"))
        return unitResult(command, session.signal(command.args.int("signal", 15)))
    }

    private fun closeTerminal(command: CommandEnvelope): ResultEnvelope {
        val session = terminalSessions.remove(command.args.string("ptyId"))
            ?: return commandError(command, ApiError(ErrorCode.E_CONFLICT, "unknown terminal session"))
        session.close()
        return commandOk(command, mapOf("closed" to true))
    }

    private fun <T> unitResult(command: CommandEnvelope, result: ApiResult<T>): ResultEnvelope =
        when (result) {
            is ApiResult.Failure -> commandError(command, result.error)
            is ApiResult.Success -> commandOk(command, mapOf("ok" to true))
        }
}
