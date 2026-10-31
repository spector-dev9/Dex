package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.TimeoutClass
import io.github.spectordev9.opencode.core.gateway.GatewayOperation
import io.github.spectordev9.opencode.core.gateway.GatewayResponseDecoder
import io.github.spectordev9.opencode.core.gateway.GatewayRequest
import io.github.spectordev9.opencode.core.gateway.HttpMethod
import io.github.spectordev9.opencode.core.gateway.ServerEvent
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.MessageInfo
import io.github.spectordev9.opencode.core.model.PermissionRequest
import io.github.spectordev9.opencode.core.model.Part
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClientException
import io.github.spectordev9.opencode.core.opencode.client.PermissionResponse
import io.github.spectordev9.opencode.core.opencode.client.PromptInput
import io.github.spectordev9.opencode.core.opencode.client.PromptModel
import io.github.spectordev9.opencode.core.opencode.client.PromptPart
import io.github.spectordev9.opencode.core.opencode.client.SessionFileDiff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val MAX_PROMPT_ATTACHMENTS = 5

private const val REPLY_POLL_INTERVAL_MS = 1_500L
private const val REPLY_POLL_TIMEOUT_MS = 180_000L
private const val EVENT_RECONNECT_BASE_DELAY_MS = 500L
private const val MAX_EVENT_RECONNECT_DELAY_MS = 30_000L
private const val ON_DEVICE_MOCK_PROVIDER_ID = "opencode-mobile-mock"
private const val MAX_COMMAND_NAME_CHARS = 128

private val emptyPermissionRules: MutableStateFlow<Map<String, PermissionResponse>> = MutableStateFlow(emptyMap())
const val MAX_PROMPT_ATTACHMENT_BYTES = 5 * 1024 * 1024
const val MAX_PROMPT_ATTACHMENTS_TOTAL_BYTES = 10 * 1024 * 1024

data class PromptAttachment(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

interface ChatSessionStore {
    fun activeSessionId(directory: String): String?

    fun setActiveSessionId(directory: String, sessionID: String?)

    /** Clears every cached entry; returns the number removed (F78). */
    fun clearAll(): Int = 0
}

/**
 * Live chat state for the supervised server.
 *
 * Owns sessions, the active conversation, provider/model selection, composer
 * submission, permission prompts and the SSE event feed. UI composition
 * mirrors the reference mobile client (`tests/…/Test` ChatView): a session
 * list, a message stream with tool cards, permission prompts above the
 * composer and a mode/model-aware composer.
 */
fun interface ChatTaskEventListener {
    fun onTaskEvent(event: ChatTaskEvent)
}

enum class ChatTaskEventKind {
    COMPLETED,
    PERMISSION,
    FAILED,
}

data class ChatTaskEvent(
    val kind: ChatTaskEventKind,
    val title: String,
    val body: String,
    val sessionID: String? = null,
)

enum class TaskStatus {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    INTERRUPTED,
}

enum class PromptIntensity(
    val variant: String?,
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    AUTO(null, "Auto", "Auto", "Use the model default"),
    LOW("low", "Low", "Low", "Fast, minimal reasoning"),
    MEDIUM("medium", "Medium", "Med", "Balanced reasoning"),
    HIGH("high", "High", "High", "Deeper reasoning"),
    MAX("max", "Max", "Max", "Maximum reasoning budget"),
}

class ChatController(
    private val scope: CoroutineScope,
    private val sessionStore: ChatSessionStore? = null,
    private val permissionRules: PermissionRuleStore? = null,
    private val taskEvents: ChatTaskEventListener? = null,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val mutex = Mutex()
    private var client: OpenCodeClient? = null
    private var directory: String = ""
    private var eventsJob: Job? = null
    private var reloadChannel = Channel<Unit>(Channel.CONFLATED)
    private var reloadJob: Job? = null
    private var replyPollJob: Job? = null

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    /** Attach to a healthy server endpoint. Replaces any previous client. */
    fun attach(client: OpenCodeClient, directory: String) {
        eventsJob?.cancel()
        eventsJob = null
        reloadJob?.cancel()
        reloadJob = null
        replyPollJob?.cancel()
        replyPollJob = null
        this.client = client
        this.directory = directory
        _state.update {
            ChatUiState(
                connected = true,
                directory = directory,
                // carry composer controls across reconnects
                mode = it.mode,
                intensity = it.intensity,
            )
        }
        eventsJob = scope.launch {
            var failures = 0
            while (isActive && this@ChatController.client === client) {
                try {
                    client.events(directory).collect { event ->
                        failures = 0
                        onEvent(event)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: OpenCodeClientException) {
                    if (!failure.apiError.retryable) {
                        detach()
                        break
                    }
                } catch (_: Exception) {
                }
                if (!isActive || this@ChatController.client !== client) break
                val backoff = minOf(
                    MAX_EVENT_RECONNECT_DELAY_MS,
                    EVENT_RECONNECT_BASE_DELAY_MS shl failures.coerceAtMost(6),
                )
                failures++
                delay(backoff)
            }
        }
        reloadJob = scope.launch {
            for (ignored in reloadChannel) {
                delay(200)
                loadMessages()
            }
        }
        scope.launch { refresh() }
    }

    /** Detach from the current client (server stopped or replaced). */
    fun detach() {
        eventsJob?.cancel()
        eventsJob = null
        reloadJob?.cancel()
        reloadJob = null
        replyPollJob?.cancel()
        replyPollJob = null
        client = null
        _state.update {
            val interrupted = it.taskStatus == TaskStatus.RUNNING ||
                it.taskStatus == TaskStatus.WAITING_APPROVAL
            ChatUiState(
                mode = it.mode,
                intensity = it.intensity,
                taskStatus = if (interrupted) TaskStatus.INTERRUPTED else TaskStatus.IDLE,
            )
        }
    }

    /**
     * Re-queries authoritative server state after a foreground resume or SSE
     * reconnect (F48/F49). Prompts are never resent automatically.
     */
    suspend fun reconcile() {
        val activeClient = client ?: return
        listSessions(activeClient)
        val activeSession = _state.value.activeSessionId
        if (activeSession != null && _state.value.sessions.none { it.id == activeSession }) {
            _state.update { it.copy(activeSessionId = null, messages = emptyList()) }
        }
        if (_state.value.activeSessionId == null) {
            val saved = sessionStore?.activeSessionId(directory)
            if (saved != null && _state.value.sessions.any { it.id == saved }) {
                selectSession(saved)
            } else {
                pickLatestSession()
            }
        } else {
            loadMessages()
        }
        loadAgents(activeClient)
        runCatching { loadProviders(activeClient) }
    }

    private suspend fun refresh() {
        val activeClient = client ?: return
        runCatching { listSessions(activeClient) }
        loadAgents(activeClient)
        if (_state.value.activeSessionId == null) {
            // Surface provider/models early so the composer chip is filled.
            runCatching { loadProviders(activeClient) }
            val savedSessionID = sessionStore?.activeSessionId(directory)
            if (savedSessionID != null && _state.value.sessions.any { it.id == savedSessionID }) {
                selectSession(savedSessionID)
            } else {
                if (savedSessionID != null) {
                    sessionStore?.setActiveSessionId(directory, null)
                }
                pickLatestSession()
            }
        }
    }

    private suspend fun listSessions(activeClient: OpenCodeClient) {
        val result = activeClient.listSessions(directory)
        when (result) {
            is ApiResult.Success -> {
                val sessions = result.value
                    .filter { it.time.archived == null }
                    .sortedByDescending { it.time.updated }
                _state.update { it.copy(sessions = sessions, error = null) }
            }
            is ApiResult.Failure -> _state.update {
                it.copy(error = result.error.message)
            }
        }
    }

    private suspend fun pickLatestSession() {
        val sessions = _state.value.sessions
        val target = sessions.firstOrNull()
        if (target != null) {
            selectSession(target.id)
        }
    }

    suspend fun selectSession(sessionID: String) {
        sessionStore?.setActiveSessionId(directory, sessionID)
        _state.update {
            it.copy(
                activeSessionId = sessionID,
                messages = emptyList(),
                sessionDiffs = emptyList(),
                diffError = null,
                diffLoading = false,
                taskStatus = TaskStatus.IDLE,
                permissions = it.permissions.filter { p -> p.sessionID == sessionID },
            )
        }
        loadMessages()
    }

    /** Creates a fresh session and makes it active. */
    suspend fun newSession() {
        val activeClient = client ?: return
        val result = activeClient.createSession(directory)
        when (result) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        sessions = listOf(result.value) + it.sessions,
                        activeSessionId = result.value.id,
                        messages = emptyList(),
                        error = null,
                    )
                }
                sessionStore?.setActiveSessionId(directory, result.value.id)
            }
            is ApiResult.Failure -> _state.update {
                it.copy(error = result.error.message)
            }
        }
    }

    suspend fun renameSession(sessionID: String, title: String) {
        val activeClient = client ?: return
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) {
            _state.update { it.copy(error = "Session title cannot be empty") }
            return
        }
        when (val result = activeClient.updateSession(sessionID, directory, title = normalizedTitle)) {
            is ApiResult.Success -> _state.update { current ->
                current.copy(
                    sessions = current.sessions.map { if (it.id == sessionID) result.value else it },
                    error = null,
                )
            }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    suspend fun archiveSession(sessionID: String) {
        val activeClient = client ?: return
        when (val result = activeClient.updateSession(sessionID, directory, archived = true)) {
            is ApiResult.Success -> removeSessionFromVisibleList(sessionID)
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    suspend fun deleteSession(sessionID: String) {
        val activeClient = client ?: return
        when (val result = activeClient.deleteSession(sessionID, directory)) {
            is ApiResult.Success -> {
                if (!result.value) {
                    _state.update { it.copy(error = "Server did not delete the session") }
                } else {
                    removeSessionFromVisibleList(sessionID)
                }
            }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    suspend fun forkSession(sessionID: String) {
        val activeClient = client ?: return
        when (val result = activeClient.forkSession(sessionID, directory)) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        sessions = listOf(result.value) + it.sessions.filterNot { session ->
                            session.id == result.value.id
                        },
                        error = null,
                    )
                }
                selectSession(result.value.id)
            }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    suspend fun loadSessionDiff() {
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        _state.update { it.copy(diffLoading = true, diffError = null) }
        when (val result = activeClient.sessionDiff(sessionID, directory)) {
            is ApiResult.Success -> _state.update {
                if (it.activeSessionId == sessionID) {
                    it.copy(sessionDiffs = result.value, diffLoading = false, diffError = null)
                } else {
                    it
                }
            }
            is ApiResult.Failure -> _state.update {
                if (it.activeSessionId == sessionID) {
                    it.copy(sessionDiffs = emptyList(), diffLoading = false, diffError = result.error.message)
                } else {
                    it
                }
            }
        }
    }

    suspend fun exportSessionJson(sessionID: String): String? {
        val activeClient = client
        val session = _state.value.sessions.firstOrNull { it.id == sessionID }
        if (activeClient == null || session == null) {
            _state.update { it.copy(error = "The selected session is no longer available") }
            return null
        }
        return when (val result = activeClient.listMessages(sessionID, directory)) {
            is ApiResult.Success -> try {
                encodeSessionExport(session, result.value, System.currentTimeMillis())
                    .also { _state.update { current -> current.copy(error = null) } }
            } catch (failure: IllegalArgumentException) {
                _state.update {
                    it.copy(error = failure.message ?: "Session export is too large")
                }
                null
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(error = result.error.message ?: "Could not load session messages") }
                null
            }
        }
    }

    private suspend fun removeSessionFromVisibleList(sessionID: String) {
        val wasActive = _state.value.activeSessionId == sessionID
        _state.update {
            it.copy(
                sessions = it.sessions.filterNot { session -> session.id == sessionID },
                activeSessionId = if (wasActive) null else it.activeSessionId,
                messages = if (wasActive) emptyList() else it.messages,
                error = null,
            )
        }
        if (wasActive) {
            sessionStore?.setActiveSessionId(directory, null)
            pickLatestSession()
        }
    }

    suspend fun setMode(mode: String) {
        _state.update { it.copy(mode = mode) }
    }

    suspend fun setModel(model: PromptModel?) {
        _state.update { current ->
            val changed = current.selectedModel?.providerID != model?.providerID ||
                current.selectedModel?.modelID != model?.modelID
            current.copy(
                selectedModel = model,
                intensity = if (changed) PromptIntensity.AUTO else current.intensity,
            )
        }
    }

    suspend fun setIntensity(intensity: PromptIntensity) {
        _state.update { it.copy(intensity = intensity) }
    }

    private suspend fun loadProviders(activeClient: OpenCodeClient) {
        val result = activeClient.call(providersOperation)
        if (result is ApiResult.Success) {
            val providers = parseProviders(json, result.value)
            val configured = when (val config = activeClient.call(configOperation(directory))) {
                is ApiResult.Success -> parseConfigModel(json, config.value)
                is ApiResult.Failure -> null
            }
            val default = configured ?: parseDefaultModel(json, result.value)
            val resolved = default ?: fallbackPromptModel(providers)
            val selected = _state.value.selectedModel?.takeIf { s: PromptModel ->
                providers.any { p -> p.id == s.providerID && p.models.any { m -> m.id == s.modelID } }
            } ?: resolved
            _state.update {
                it.copy(
                    providers = providers,
                    defaultModel = default ?: resolved,
                    selectedModel = selected,
                )
            }
        }
    }

    private fun resolvePromptModel(): PromptModel {
        return _state.value.selectedModel
            ?: _state.value.defaultModel
            ?: fallbackPromptModel(_state.value.providers)
            ?: PromptModel(ON_DEVICE_MOCK_PROVIDER_ID, "mock")
    }

    private fun fallbackPromptModel(providers: List<ProviderInfo>): PromptModel? {
        val available = providers.filter { it.models.isNotEmpty() }
        val provider = available.firstOrNull { it.id == CUSTOM_PROVIDER_ID }
            ?: available.firstOrNull { it.id == ON_DEVICE_MOCK_PROVIDER_ID }
            ?: available.firstOrNull { it.id != "opencode" }
            ?: available.firstOrNull()
            ?: return null
        val firstModel = provider.models.firstOrNull() ?: return null
        return PromptModel(provider.id, firstModel.id)
    }

    private suspend fun loadAgents(activeClient: OpenCodeClient) {
        when (val result = activeClient.call(agentsOperation(directory))) {
            is ApiResult.Success -> {
                val agents = try {
                    parseAgents(json, result.value)
                } catch (_: kotlinx.serialization.SerializationException) {
                    _state.update {
                        it.copy(agentError = "OpenCode returned an invalid response for agent.list")
                    }
                    return
                }
                _state.update { current ->
                    val selectedAgent = current.mode.takeIf { mode ->
                        agents.any { it.id == mode }
                    } ?: agents.firstOrNull { it.mode == "primary" }?.id
                        ?: agents.firstOrNull()?.id
                        ?: current.mode
                    current.copy(agents = agents, mode = selectedAgent)
                }
            }
            is ApiResult.Failure -> _state.update {
                it.copy(agentError = result.error.message ?: "Could not load available agents")
            }
        }
    }

    private suspend fun loadMessages() {
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        mutex.withLock {
            val result = activeClient.listMessages(sessionID, directory)
            when (result) {
                is ApiResult.Success -> _state.update {
                    if (it.activeSessionId == sessionID) {
                        it.copy(messages = result.value, error = null)
                    } else {
                        it
                    }
                }
                is ApiResult.Failure -> _state.update {
                    if (it.activeSessionId == sessionID) it.copy(error = result.error.message) else it
                }
            }
        }
    }

    suspend fun send(text: String, attachments: List<PromptAttachment> = emptyList()): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return false
        if (attachments.size > MAX_PROMPT_ATTACHMENTS ||
            attachments.any { it.bytes.isEmpty() || it.bytes.size > MAX_PROMPT_ATTACHMENT_BYTES } ||
            attachments.sumOf { it.bytes.size.toLong() } > MAX_PROMPT_ATTACHMENTS_TOTAL_BYTES
        ) {
            _state.update { it.copy(error = "Attachments exceed the supported size or count limit") }
            return false
        }
        val activeClient = client ?: return false
        val sessionID = _state.value.activeSessionId ?: return false
        val model = resolvePromptModel()
        val result = activeClient.promptAsync(
            sessionID = sessionID,
            directory = directory,
            prompt = PromptInput(
                parts = buildList {
                    if (trimmed.isNotEmpty()) add(PromptPart(text = trimmed))
                    attachments.forEach { attachment ->
                        val safeFilename = attachment.filename
                            .filterNot(Char::isISOControl)
                            .take(255)
                            .ifBlank { "attachment" }
                        add(
                            PromptPart(
                                type = "file",
                                mime = attachment.mimeType.ifBlank { "application/octet-stream" },
                                filename = safeFilename,
                                url = "data:${attachment.mimeType.ifBlank { "application/octet-stream" }};" +
                                    "base64,${java.util.Base64.getEncoder().encodeToString(attachment.bytes)}",
                            ),
                        )
                    }
                },
                model = model,
                agent = _state.value.mode,
                variant = _state.value.intensity.variant,
            ),
        )
        when (result) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        sending = true,
                        taskStatus = TaskStatus.RUNNING,
                        error = null,
                        // Optimistic echo; replaced when the server round-trips.
                        messages = if (trimmed.isEmpty()) it.messages else {
                            it.messages + optimisticUserMessage(sessionID, trimmed)
                        },
                    )
                }
                replyPollJob?.cancel()
                replyPollJob = scope.launch { awaitReply(sessionID) }
                return true
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(error = result.error.message) }
                return false
            }
        }
    }

    /**
     * Invokes a server command or skill against the active session (F53).
     * The server remains authoritative for whether the command exists.
     */
    suspend fun runCommand(command: String, arguments: String = ""): Boolean {
        val cleanCommand = command.trim()
        if (cleanCommand.isEmpty() || cleanCommand.length > MAX_COMMAND_NAME_CHARS ||
            cleanCommand.any { it.isWhitespace() || it.isISOControl() }
        ) {
            _state.update { it.copy(error = "Command name is invalid") }
            return false
        }
        if (_state.value.sending) {
            _state.update { it.copy(error = "Wait for the current response to finish") }
            return false
        }
        val activeClient = client ?: return false
        val sessionID = _state.value.activeSessionId ?: return false
        val result = activeClient.runCommand(
            sessionID = sessionID,
            directory = directory,
            command = cleanCommand,
            arguments = arguments,
            agent = _state.value.mode,
            model = resolvePromptModel(),
            variant = _state.value.intensity.variant,
        )
        return when (result) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        sending = true,
                        taskStatus = TaskStatus.RUNNING,
                        error = null,
                        messages = (it.messages + result.value).distinctBy { message -> message.info.id },
                    )
                }
                replyPollJob?.cancel()
                replyPollJob = scope.launch { awaitReply(sessionID) }
                true
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(error = result.error.message) }
                false
            }
        }
    }

    /**
     * Polls the session message list while a prompt is in flight so replies
     * appear even when the SSE feed is unavailable. The event feed still
     * clears [ChatUiState.sending] as soon as `session.idle` arrives.
     */
    private suspend fun awaitReply(sessionID: String) {
        val deadline = System.currentTimeMillis() + REPLY_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(REPLY_POLL_INTERVAL_MS)
            val current = _state.value
            if (current.activeSessionId != sessionID) return
            if (!current.sending) {
                if (current.error == null) loadMessages()
                return
            }
            loadMessages()
        }
        _state.update {
            if (it.activeSessionId == sessionID && it.sending) {
                it.copy(
                    sending = false,
                    taskStatus = if (it.error == null) TaskStatus.FAILED else it.taskStatus,
                    error = it.error ?: "The assistant did not reply in time",
                )
            } else {
                it
            }
        }
    }

    suspend fun retryPrompt(messageID: String, text: String) {
        val trimmed = text.trim()
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Prompt cannot be empty") }
            return
        }
        if (_state.value.sending) {
            _state.update { it.copy(error = "Wait for the current response to finish before retrying") }
            return
        }
        if (_state.value.messages.none { it.info.id == messageID && it.info.role == "user" }) {
            _state.update { it.copy(error = "The prompt is no longer available in this session") }
            return
        }
        if (messageID.startsWith("local-")) {
            _state.update { it.copy(error = "Wait for the prompt to sync before retrying it") }
            return
        }
        when (val result = activeClient.revertSession(sessionID, directory, messageID)) {
            is ApiResult.Success -> {
                loadMessages()
                send(trimmed)
            }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    /** Creates a session first if none is active, then sends [text]. */
    suspend fun sendOrStart(
        text: String,
        attachments: List<PromptAttachment> = emptyList(),
    ): Boolean {
        if (text.isBlank() && attachments.isEmpty()) return false
        if (_state.value.activeSessionId == null) {
            val title = text.trim().take(48).ifBlank {
                attachments.firstOrNull()?.filename?.take(48) ?: "New session"
            }
            val activeClient = client ?: return false
            val created = activeClient.createSession(directory, title = title)
            if (created is ApiResult.Failure) {
                _state.update { it.copy(error = created.error.message) }
                return false
            }
            if (created is ApiResult.Success) {
                _state.update {
                    it.copy(
                        sessions = listOf(created.value) + it.sessions,
                        activeSessionId = created.value.id,
                        messages = emptyList(),
                    )
                }
                sessionStore?.setActiveSessionId(directory, created.value.id)
            }
        }
        return send(text, attachments)
    }

    suspend fun abort() {
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        when (val result = activeClient.abortSession(sessionID, directory)) {
            is ApiResult.Success -> _state.update { current ->
                if (current.activeSessionId != sessionID || !current.sending) {
                    current
                } else if (result.value) {
                    current.copy(sending = false, taskStatus = TaskStatus.INTERRUPTED, error = null)
                } else {
                    current.copy(error = "Server did not acknowledge the cancellation request")
                }
            }
            is ApiResult.Failure -> _state.update { current ->
                if (current.activeSessionId == sessionID && current.sending) {
                    current.copy(error = result.error.message)
                } else {
                    current
                }
            }
        }
    }

    /** Remembered per-tool permission answers (F39); the server stays authoritative. */
    val rememberedPermissionRules: StateFlow<Map<String, PermissionResponse>>
        get() = permissionRules?.rules ?: emptyPermissionRules

    fun clearPermissionRule(tool: String) {
        permissionRules?.clearRule(tool)
    }

    suspend fun answerPermission(permissionID: String, response: PermissionResponse) {
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        val permission = _state.value.permissions.firstOrNull { it.id == permissionID }
        val result = activeClient.replyPermission(sessionID, permissionID, directory, response)
        if (result is ApiResult.Success) {
            // Remember the user's explicit "always" choice for future prompts (F39).
            if (response == PermissionResponse.ALWAYS && permission != null) {
                permissionRules?.setRule(permission.permission, PermissionResponse.ALWAYS)
            }
            _state.update {
                it.copy(permissions = it.permissions.filter { p -> p.id != permissionID })
            }
        }
    }

    /** Answers a pending permission according to a remembered rule, if any (F39). */
    private fun maybeAutoAnswer(permission: PermissionRequest) {
        val rule = permissionRules?.ruleFor(permission.permission) ?: return
        if (rule != PermissionResponse.ALWAYS) return
        // Destructive commands always require manual confirmation (F40).
        val command = permission.metadata["command"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (DestructiveCommandDetector.match(command.orEmpty()) != null) return
        scope.launch {
            answerPermission(permission.id, rule)
            _state.update {
                it.copy(
                    authMessage = "Auto-answered '${permission.permission}' per your remembered rule",
                )
            }
        }
    }

    /**
     * Stores an API key for [providerID] on the server (PUT /auth). Custom
     * OpenAI-compatible endpoints use Settings -> AI provider; this remains
     * available for built-in providers and remote profiles.
     */
    suspend fun saveApiKey(providerID: String, apiKey: String) {
        val activeClient = client ?: return
        if (providerID.isBlank() || apiKey.isBlank()) return
        val payload = org.json.JSONObject()
            .put("type", "api")
            .put("key", apiKey.trim())
        val operation = GatewayOperation<Boolean>(
            name = "auth.set",
            request = GatewayRequest(
                method = HttpMethod.PUT,
                path = "/auth/$providerID",
                body = payload.toString().toByteArray(Charsets.UTF_8),
                contentType = "application/json",
            ),
            timeoutClass = TimeoutClass.LIST_GET,
            decoder = GatewayResponseDecoder { _, _ -> true },
        )
        when (val result = activeClient.call(operation)) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    authMessage = "Key saved for $providerID",
                    error = null,
                )
            }
            is ApiResult.Failure -> _state.update {
                it.copy(authMessage = null, error = result.error.message)
            }
        }
    }

    suspend fun testConnection(): String {
        val activeClient = client
            ?: return "Connection test failed: server client is not connected"
        return when (val result = activeClient.health()) {
            is ApiResult.Success ->
                "Connected to OpenCode ${result.value.version} on port ${result.value.endpointPort}"
            is ApiResult.Failure ->
                "Connection test failed: ${result.error.message}"
        }
    }

    private suspend fun onEvent(event: ServerEvent) {
        if (event.type == "server.connected") {
            // SSE reconnects can miss events; resynchronize instead of
            // replaying or resending an in-flight prompt (F49/F81).
            reconcile()
            return
        }
        val payload = event.payload as? JsonObject ?: return
        val activeSession = _state.value.activeSessionId
        when {
            event.type == "permission.updated" -> {
                val permission = payload["permission"] as? JsonObject ?: return
                val decoded = runCatching {
                    json.decodeFromJsonElement(PermissionRequest.serializer(), permission)
                }.getOrNull() ?: return
                if (decoded.sessionID == activeSession) {
                    val isNew = _state.value.permissions.none { it.id == decoded.id }
                    _state.update {
                        it.copy(
                            permissions = it.permissions
                                .filter { p -> p.id != decoded.id } + decoded,
                            taskStatus = TaskStatus.WAITING_APPROVAL,
                        )
                    }
                    if (isNew) {
                        notifyTask(
                            ChatTaskEventKind.PERMISSION,
                            "Approval needed",
                            decoded.permission,
                            decoded.sessionID,
                        )
                    }
                    maybeAutoAnswer(decoded)
                }
            }
            event.type == "permission.replied" -> {
                val permissionID = payload.string("permissionID") ?: return
                _state.update {
                    it.copy(
                        permissions = it.permissions.filter { p -> p.id != permissionID },
                        taskStatus = if (it.sending) TaskStatus.RUNNING else it.taskStatus,
                    )
                }
            }
            event.type == "session.idle" -> {
                if (payload.string("sessionID") == activeSession) {
                    val wasSending = _state.value.sending
                    _state.update {
                        it.copy(
                            sending = false,
                            taskStatus = if (it.error == null) TaskStatus.COMPLETED else TaskStatus.FAILED,
                        )
                    }
                    if (_state.value.error == null) {
                        reloadChannel.trySend(Unit)
                        if (wasSending) {
                            notifyTask(
                                ChatTaskEventKind.COMPLETED,
                                "Task completed",
                                "The assistant finished responding.",
                                activeSession,
                            )
                        }
                    }
                }
            }
            event.type == "session.error" -> {
                if (payload.string("sessionID") == activeSession) {
                    val description = describeSessionError(payload["error"] as? JsonObject)
                    _state.update {
                        it.copy(
                            sending = false,
                            taskStatus = TaskStatus.FAILED,
                            error = description,
                        )
                    }
                    notifyTask(
                        ChatTaskEventKind.FAILED,
                        "Task failed",
                        description,
                        activeSession,
                    )
                }
            }
            event.type.startsWith("message.") -> {
                val affected = payload.string("sessionID")
                    ?: (payload["info"] as? JsonObject)?.string("sessionID")
                    ?: (payload["part"] as? JsonObject)?.string("sessionID")
                if (affected != null && affected == activeSession) {
                    reloadChannel.trySend(Unit)
                }
            }
            event.type.startsWith("session.") -> {
                val activeClient = client
                if (activeClient != null) {
                    scope.launch { listSessions(activeClient) }
                    if (event.type == "session.deleted") {
                        val affected = payload.string("sessionID")
                            ?: (payload["info"] as? JsonObject)?.string("id")
                        if (affected == activeSession) {
                            _state.update {
                                it.copy(activeSessionId = null, messages = emptyList())
                            }
                            scope.launch { pickLatestSession() }
                        }
                    }
                }
            }
        }
    }

    private fun notifyTask(
        kind: ChatTaskEventKind,
        title: String,
        body: String,
        sessionID: String?,
    ) {
        val listener = taskEvents ?: return
        val sessionTitle = _state.value.sessions
            .firstOrNull { it.id == sessionID }
            ?.title
            ?.takeIf { it.isNotBlank() }
        listener.onTaskEvent(
            ChatTaskEvent(
                kind = kind,
                title = title,
                body = if (sessionTitle == null) body else "$sessionTitle: $body",
                sessionID = sessionID,
            ),
        )
    }

    private fun describeSessionError(error: JsonObject?): String {
        if (error == null) return "The assistant run failed"
        val name = error.string("name") ?: "OpenCode error"
        val data = error["data"] as? JsonObject
        val message = data?.string("message")
        val statusCode = (data?.get("statusCode") as? JsonPrimitive)?.intOrNull
        return buildString {
            append(name)
            if (!message.isNullOrBlank()) append(": ").append(message)
            if (statusCode != null) append(" (HTTP ").append(statusCode).append(')')
        }
    }

    private fun optimisticUserMessage(sessionID: String, text: String): Message =
        Message(
            info = MessageInfo(
                id = "local-${System.currentTimeMillis()}",
                sessionID = sessionID,
                role = "user",
                time = JsonObject(emptyMap()),
            ),
            parts = listOf(
                Part(id = "local-part-$text.hashCode()", sessionID = sessionID, messageID = "local", type = "text", text = text),
            ),
        )

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

}

internal fun messageUsageSummary(info: MessageInfo): String? {
    val tokens = info.tokens
    val usageParts = buildList {
        fun appendCount(label: String, name: String) {
            val count = (tokens?.get(name) as? JsonPrimitive)?.longOrNull
                ?.takeIf { it >= 0L }
                ?: return
            add("$count $label")
        }

        appendCount("in", "input")
        appendCount("out", "output")

        val cache = tokens?.get("cache") as? JsonObject
        val cachedRead = (cache?.get("read") as? JsonPrimitive)?.longOrNull
            ?.takeIf { it >= 0L }
        val cachedWrite = (cache?.get("write") as? JsonPrimitive)?.longOrNull
            ?.takeIf { it >= 0L }
        if (cachedRead != null) add("$cachedRead cached")
        if (cachedWrite != null) add("$cachedWrite cache write")
    }
    val cost = info.cost?.takeIf { it.isFinite() && it >= 0.0 }
    if (usageParts.isEmpty() && cost == null) return null
    return buildList {
        if (usageParts.isNotEmpty()) add(usageParts.joinToString(" · "))
        if (cost != null) add("cost ${"%.4f".format(java.util.Locale.ROOT, cost)}")
    }.joinToString("  |  ")
}

/** Composer-visible model picker data (mirrors /config/providers). */
data class ProviderInfo(
    val id: String,
    val name: String,
    val models: List<ModelInfo>,
)

data class ModelInfo(
    val id: String,
    val name: String,
    val variants: List<String> = emptyList(),
)

data class AgentInfo(
    val id: String,
    val description: String?,
    val mode: String,
)

data class ChatUiState(
    val connected: Boolean = false,
    val directory: String = "",
    val sessions: List<Session> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    val agentError: String? = null,
    val defaultModel: PromptModel? = null,
    val selectedModel: PromptModel? = null,
    val intensity: PromptIntensity = PromptIntensity.AUTO,
    val mode: String = "build",
    val sending: Boolean = false,
    val taskStatus: TaskStatus = TaskStatus.IDLE,
    val permissions: List<PermissionRequest> = emptyList(),
    val error: String? = null,
    val authMessage: String? = null,
    val sessionDiffs: List<SessionFileDiff> = emptyList(),
    val diffLoading: Boolean = false,
    val diffError: String? = null,
)

/** GET /config/providers returning raw text; parsed defensively upstream. */
val providersOperation = GatewayOperation<String>(
    name = "config.providers",
    request = GatewayRequest(
        method = HttpMethod.GET,
        path = "/config/providers",
    ),
    timeoutClass = TimeoutClass.LIST_GET,
    decoder = GatewayResponseDecoder { _, body -> String(body, Charsets.UTF_8) },
)

internal fun configOperation(directory: String): GatewayOperation<String> =
    GatewayOperation(
        name = "config.get",
        request = GatewayRequest(
            method = HttpMethod.GET,
            path = "/config",
            query = mapOf("directory" to directory),
        ),
        timeoutClass = TimeoutClass.LIST_GET,
        decoder = GatewayResponseDecoder { _, body -> String(body, Charsets.UTF_8) },
    )

internal fun agentsOperation(directory: String): GatewayOperation<String> =
    GatewayOperation(
        name = "agent.list",
        request = GatewayRequest(
            method = HttpMethod.GET,
            path = "/agent",
            query = mapOf("directory" to directory),
        ),
        timeoutClass = TimeoutClass.LIST_GET,
        decoder = GatewayResponseDecoder { _, body -> String(body, Charsets.UTF_8) },
    )

internal fun parseAgents(json: Json, raw: String): List<AgentInfo> {
    val values = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray
        ?: throw kotlinx.serialization.SerializationException("Agent list must be a JSON array")
    return values.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if ((obj["hidden"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() == true) {
            return@mapNotNull null
        }
        AgentInfo(
            id = id,
            description = obj.string("description"),
            mode = obj.string("mode") ?: "all",
        )
    }.distinctBy { it.id }
}

internal fun parseProviders(json: Json, raw: String): List<ProviderInfo> {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
    val providers = root["providers"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return providers.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
        val models = (obj["models"] as? JsonObject)?.map { (key, value) ->
            val model = value as? JsonObject
            val name = model?.string("name")?.takeIf { it.isNotBlank() }
                ?: model?.string("id")
                ?: (value as? JsonPrimitive)?.contentOrNull
                ?: ""
            ModelInfo(
                id = key,
                name = name.ifBlank { key },
                variants = model?.variantIds().orEmpty(),
            )
        }?.sortedBy { it.name } ?: emptyList()
        ProviderInfo(id, obj.string("name") ?: id, models)
    }
}

private fun JsonObject.variantIds(): List<String> {
    val variants = this["variants"] ?: return emptyList()
    return when (variants) {
        is JsonObject -> variants.keys.toList()
        is kotlinx.serialization.json.JsonArray -> variants.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull
                is JsonObject -> element.string("id")
                else -> null
            }
        }
        else -> emptyList()
    }.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun parseConfigModel(json: Json, raw: String): PromptModel? {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val value = root.string("model")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val separator = value.indexOf('/')
    if (separator <= 0 || separator == value.lastIndex) return null
    val providerID = value.substring(0, separator)
    val modelID = value.substring(separator + 1)
    return PromptModel(providerID, modelID)
}

internal fun parseDefaultModel(json: Json, raw: String): PromptModel? {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val default = root["default"] as? JsonObject ?: return null
    // Legacy flat shape: {"providerID": "...", "modelID": "..."}.
    val providerID = default.string("providerID") ?: default.string("provider")
    val modelID = default.string("modelID") ?: default.string("model")
    if (providerID != null && modelID != null) return PromptModel(providerID, modelID)
    // Contract shape for GET /config/providers: {"<providerID>": "<modelID>"}.
    val entries = default.entries.filter { (_, value) ->
        val primitive = value as? JsonPrimitive
        primitive != null && primitive.isString && primitive.content.isNotBlank()
    }
    val entry = entries.firstOrNull { it.key == CUSTOM_PROVIDER_ID }
        ?: entries.firstOrNull { it.key == ON_DEVICE_MOCK_PROVIDER_ID }
        ?: entries.firstOrNull()
        ?: return null
    val selectedModelID = (entry.value as? JsonPrimitive)?.contentOrNull ?: return null
    return PromptModel(entry.key, selectedModelID)
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
