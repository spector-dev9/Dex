package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.HttpMethod
import io.github.spectordev9.opencode.core.common.TimeoutClass
import io.github.spectordev9.opencode.core.gateway.GatewayOperation
import io.github.spectordev9.opencode.core.gateway.GatewayResponseDecoder
import io.github.spectordev9.opencode.core.gateway.ServerEvent
import io.github.spectordev9.opencode.core.gateway.GatewayRequest
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.PermissionRequest
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import io.github.spectordev9.opencode.core.opencode.client.PermissionResponse
import io.github.spectordev9.opencode.core.opencode.client.PromptInput
import io.github.spectordev9.opencode.core.opencode.client.PromptModel
import io.github.spectordev9.opencode.core.opencode.client.PromptPart
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
            pickLatestSession()
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
        _state.update {
            it.copy(
                activeSessionId = sessionID,
                messages = emptyList(),
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
            }
            is ApiResult.Failure -> _state.update {
                it.copy(error = result.error.message)
            }
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

    suspend fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        val model = _state.value.selectedModel ?: _state.value.defaultModel
        val result = activeClient.promptAsync(
            sessionID = sessionID,
            directory = directory,
            prompt = PromptInput(
                parts = listOf(PromptPart(text = trimmed)),
                model = model,
                agent = _state.value.mode,
            ),
        )
        when (result) {
            is ApiResult.Success -> _state.update { it.copy(sending = true, error = null) }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    /** Creates a session first if none is active, then sends [text]. */
    suspend fun sendOrStart(text: String) {
        if (_state.value.activeSessionId == null) {
            val title = text.trim().take(48).ifBlank { "New session" }
            val activeClient = client ?: return
            val created = activeClient.createSession(directory, title = title)
            if (created is ApiResult.Failure) {
                _state.update { it.copy(error = created.error.message) }
                return
            }
            if (created is ApiResult.Success) {
                _state.update {
                    it.copy(
                        sessions = listOf(created.value) + it.sessions,
                        activeSessionId = created.value.id,
                        messages = emptyList(),
                    )
                }
            }
        }
        send(text)
    }

    suspend fun abort() {
        val activeClient = client ?: return
        val sessionID = _state.value.activeSessionId ?: return
        activeClient.abortSession(sessionID, directory)
        _state.update { it.copy(sending = false) }
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
