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
import io.github.spectordev9.opencode.core.opencode.client.PermissionResponse
import io.github.spectordev9.opencode.core.opencode.client.PromptInput
import io.github.spectordev9.opencode.core.opencode.client.PromptModel
import io.github.spectordev9.opencode.core.opencode.client.PromptPart
import io.github.spectordev9.opencode.core.opencode.client.SessionFileDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

const val MAX_PROMPT_ATTACHMENTS = 5
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
class ChatController(
    private val scope: CoroutineScope,
    private val sessionStore: ChatSessionStore? = null,
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

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state

    /** Attach to a healthy server endpoint. Replaces any previous client. */
    fun attach(client: OpenCodeClient, directory: String) {
        eventsJob?.cancel()
        eventsJob = null
        reloadJob?.cancel()
        reloadJob = null
        this.client = client
        this.directory = directory
        _state.update {
            ChatUiState(
                connected = true,
                directory = directory,
                // carry composer mode across reconnects
                mode = it.mode,
            )
        }
        eventsJob = scope.launch {
            try {
                client.events(directory).collect { event -> onEvent(event) }
            } catch (_: Throwable) {
                // Server went away; the supervisor state watcher will detach.
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
        client = null
        _state.update { ChatUiState(mode = it.mode) }
    }

    private suspend fun refresh() {
        val activeClient = client ?: return
        runCatching { listSessions(activeClient) }
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
        _state.update { it.copy(selectedModel = model) }
    }

    private suspend fun loadProviders(activeClient: OpenCodeClient) {
        val result = activeClient.call(providersOperation)
        if (result is ApiResult.Success) {
            val providers = parseProviders(json, result.value)
            val default = parseDefaultModel(json, result.value)
            _state.update {
                it.copy(
                    providers = providers,
                    defaultModel = default,
                    selectedModel = it.selectedModel ?: default,
                )
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
        val model = _state.value.selectedModel ?: _state.value.defaultModel
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
            ),
        )
        when (result) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        sending = true,
                        error = null,
                        // Optimistic echo; replaced when the server round-trips.
                        messages = if (trimmed.isEmpty()) it.messages else {
                            it.messages + optimisticUserMessage(sessionID, trimmed)
                        },
                    )
                }
                return true
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(error = result.error.message) }
                return false
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
                    current.copy(sending = false, error = null)
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

    suspend fun answerPermission(permissionID: String, response: PermissionResponse) {
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        val result = activeClient.replyPermission(sessionID, permissionID, directory, response)
        if (result is ApiResult.Success) {
            _state.update {
                it.copy(permissions = it.permissions.filter { p -> p.id != permissionID })
            }
        }
    }

    /**
     * Stores an API key for [providerID] on the server (PUT /auth) so real
     * provider profiles can route prompts once the D4 remote-profile work
     * enables them.
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

    private suspend fun onEvent(event: ServerEvent) {
        val payload = event.payload as? JsonObject ?: return
        val activeSession = _state.value.activeSessionId
        when {
            event.type == "permission.updated" -> {
                val permission = payload["permission"] as? JsonObject ?: return
                val decoded = runCatching {
                    json.decodeFromJsonElement(PermissionRequest.serializer(), permission)
                }.getOrNull() ?: return
                if (decoded.sessionID == activeSession) {
                    _state.update {
                        it.copy(
                            permissions = it.permissions
                                .filter { p -> p.id != decoded.id } + decoded,
                        )
                    }
                }
            }
            event.type == "permission.replied" -> {
                val permissionID = payload.string("permissionID") ?: return
                _state.update {
                    it.copy(permissions = it.permissions.filter { p -> p.id != permissionID })
                }
            }
            event.type == "session.idle" -> {
                if (payload.string("sessionID") == activeSession) {
                    _state.update { it.copy(sending = false) }
                }
            }
            event.type == "session.error" -> {
                if (payload.string("sessionID") == activeSession) {
                    _state.update { it.copy(sending = false) }
                }
            }
            event.type.startsWith("message.") -> {
                val affected = payload.string("sessionID")
                    ?: (payload["info"] as? JsonObject)?.string("id")
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

/** Composer-visible model picker data (mirrors /config/providers). */
data class ProviderInfo(
    val id: String,
    val name: String,
    val models: List<ModelInfo>,
)

data class ModelInfo(
    val id: String,
    val name: String,
)

data class ChatUiState(
    val connected: Boolean = false,
    val directory: String = "",
    val sessions: List<Session> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<Message> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
    val defaultModel: PromptModel? = null,
    val selectedModel: PromptModel? = null,
    val mode: String = "build",
    val sending: Boolean = false,
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

internal fun parseProviders(json: Json, raw: String): List<ProviderInfo> {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
    val providers = root["providers"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return providers.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
        val models = (obj["models"] as? JsonObject)?.mapValues { (_, value) ->
            (value as? JsonObject)?.string("name")?.takeIf { it.isNotBlank() }
                ?: (value as? JsonObject)?.string("id")
                ?: (value as? JsonPrimitive)?.contentOrNull
                ?: ""
        }?.map { (key, name) -> ModelInfo(key, name.ifBlank { key }) }?.sortedBy { it.name } ?: emptyList()
        ProviderInfo(id, obj.string("name") ?: id, models)
    }
}

internal fun parseDefaultModel(json: Json, raw: String): PromptModel? {
    val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val default = root["default"] as? JsonObject ?: return null
    val providerID = default.string("providerID") ?: default.string("provider") ?: return null
    val modelID = default.string("modelID") ?: default.string("model") ?: return null
    return PromptModel(providerID, modelID)
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
