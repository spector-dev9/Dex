package io.github.spectordev9.opencode.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class ChatRepository(
    private val api: OpenCodeApi,
    private val directory: String,
    private val scope: CoroutineScope,
    private val log: ConnectionLog = ConnectionLog(),
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val eventStream = EventStream(
        api = api,
        scope = scope,
        log = log,
        onEvent = { event -> applyEvent(event) },
        onReconcile = { reconcile() },
        onError = { error -> onStreamError(error) },
    )

    fun start() {
        scope.launch { connect() }
    }

    suspend fun connect() {
        _state.update { it.copy(phase = ConnectionPhase.Connecting, error = null) }
        try {
            api.health()
            val providers = api.providers()
            var sessions = api.sessions(directory)
            val selected = sessions.maxByOrNull { it.time.updated }
                ?: api.createSession(directory, CreateSessionRequestDto(title = "Dex")).also {
                    sessions = sessions + it
                }
            _state.update {
                it.copy(
                    phase = ConnectionPhase.Connected,
                    providers = providers,
                    sessions = sessions.sortedByDescending { session -> session.time.updated },
                    sessionID = selected.id,
                    error = null,
                )
            }
            loadSession(selected.id)
            eventStream.start(directory)
        } catch (error: ConnectionError) {
            fail(error)
        }
    }

    suspend fun refreshSessions() {
        try {
            val sessions = api.sessions(directory)
            _state.update { it.copy(sessions = sessions.sortedByDescending { session -> session.time.updated }) }
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun openSession(sessionID: String) {
        _state.update {
            it.copy(
                sessionID = sessionID,
                messages = emptyList(),
                status = SessionStatusDto(),
                permissions = emptyList(),
                questions = emptyList(),
                phase = ConnectionPhase.Connecting,
                error = null,
            )
        }
        try {
            loadSession(sessionID)
        } catch (error: ConnectionError) {
            fail(error)
        }
    }

    suspend fun refreshMessages() {
        val sessionID = _state.value.sessionID ?: return
        try {
            loadSession(sessionID)
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun sendPrompt(text: String) {
        if (text.isBlank()) return
        try {
            val sessionID = ensureSession()
            val default = _state.value.providers?.default?.entries?.firstOrNull()
            val request = PromptRequestDto(
                parts = listOf(PromptPartDto(type = "text", text = text)),
                model = default?.let { PromptModelDto(providerID = it.key, modelID = it.value) },
            )
            api.promptAsync(sessionID, directory, request)
            loadSession(sessionID)
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun abort() {
        val sessionID = _state.value.sessionID ?: return
        try {
            api.abort(sessionID, directory)
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun replyPermission(permissionID: String, reply: String) {
        try {
            api.respondPermission(permissionID, directory, reply)
            mutex.withLock {
                _state.update { it.copy(permissions = it.permissions.filterNot { permission -> permission.id == permissionID }) }
            }
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun replyQuestion(requestID: String, answers: List<List<String>>) {
        try {
            api.replyQuestion(requestID, directory, answers)
            mutex.withLock {
                _state.update { it.copy(questions = it.questions.filterNot { question -> question.id == requestID }) }
            }
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun rejectQuestion(requestID: String) {
        try {
            api.rejectQuestion(requestID, directory)
            mutex.withLock {
                _state.update { it.copy(questions = it.questions.filterNot { question -> question.id == requestID }) }
            }
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun reconcile() {
        val sessionID = _state.value.sessionID
        if (sessionID == null) {
            refreshSessions()
            return
        }
        try {
            val status = api.status(directory)
            val messages = api.messages(sessionID, directory)
            mutex.withLock {
                _state.update { current ->
                    if (current.sessionID != sessionID) {
                        current
                    } else {
                        current.copy(
                            messages = messages,
                            status = status[sessionID] ?: SessionStatusDto(),
                            phase = ConnectionPhase.Connected,
                            error = null,
                        )
                    }
                }
            }
        } catch (error: ConnectionError) {
            onStreamError(error)
        }
    }

    suspend fun applyEvent(event: EventDto) {
        mutex.withLock {
            _state.update { current ->
                val reduced = reduceEvent(current, event)
                reduced.copy(
                    phase = if (event.type == "session.error") reduced.phase else ConnectionPhase.Connected,
                    lastEventID = event.id.ifEmpty { current.lastEventID },
                )
            }
        }
    }

    fun stop() {
        eventStream.stop()
    }

    fun close() {
        stop()
        api.close()
    }

    internal fun reduceEvent(state: ChatState, event: EventDto): ChatState {
        val properties = event.properties
        return when (event.type) {
            "server.connected" -> state.copy(phase = ConnectionPhase.Connected, error = null)
            "session.created", "session.updated" -> {
                val info = properties.obj("info") ?: return state
                val session = runCatching {
                    ConnectionJson.decode(SessionDto.serializer(), info)
                }.getOrNull() ?: return state
                state.copy(
                    sessions = upsertSession(state.sessions, session),
                    sessionID = if (event.type == "session.created" && state.sessionID == null) session.id else state.sessionID,
                )
            }
            "session.deleted" -> {
                val info = properties.obj("info") ?: return state
                val session = runCatching {
                    ConnectionJson.decode(SessionDto.serializer(), info)
                }.getOrNull() ?: return state
                if (state.sessionID == session.id) {
                    state.copy(
                        sessions = state.sessions.filterNot { it.id == session.id },
                        sessionID = null,
                        messages = emptyList(),
                        status = SessionStatusDto(),
                    )
                } else {
                    state.copy(sessions = state.sessions.filterNot { it.id == session.id })
                }
            }
            "message.updated" -> {
                val info = properties.obj("info") ?: return state
                val message = runCatching {
                    ConnectionJson.decode(MessageInfoDto.serializer(), info)
                }.getOrNull() ?: return state
                val sessionID = message.sessionID.ifEmpty { properties.string("sessionID").orEmpty() }
                if (!state.matches(sessionID)) return state
                state.copy(messages = upsertMessage(state.messages, message.copy(sessionID = sessionID)))
            }
            "message.part.updated" -> {
                val part = properties.obj("part")?.let { element ->
                    runCatching { ConnectionJson.decode(PartDto.serializer(), element) }.getOrNull()
                } ?: return state
                val sessionID = part.sessionID.ifEmpty { properties.string("sessionID").orEmpty() }
                if (!state.matches(sessionID)) return state
                state.copy(messages = upsertPart(state.messages, part.copy(sessionID = sessionID)))
            }
            "message.part.delta" -> {
                val sessionID = properties.string("sessionID").orEmpty()
                if (!state.matches(sessionID)) return state
                if (properties.string("field") != "text") return state
                val messageID = properties.string("messageID") ?: return state
                val partID = properties.string("partID") ?: return state
                val delta = properties.string("delta") ?: return state
                state.copy(
                    messages = appendTextDelta(
                        messages = state.messages,
                        sessionID = sessionID,
                        messageID = messageID,
                        partID = partID,
                        delta = delta,
                    ),
                )
            }
            "message.removed" -> {
                val sessionID = properties.string("sessionID").orEmpty()
                if (!state.matches(sessionID)) return state
                val messageID = properties.string("messageID") ?: return state
                state.copy(messages = state.messages.filterNot { it.info.id == messageID })
            }
            "message.part.removed" -> {
                val sessionID = properties.string("sessionID").orEmpty()
                if (!state.matches(sessionID)) return state
                val messageID = properties.string("messageID") ?: return state
                val partID = properties.string("partID") ?: return state
                state.copy(
                    messages = state.messages.map { envelope ->
                        if (envelope.info.id != messageID) {
                            envelope
                        } else {
                            envelope.copy(parts = envelope.parts.filterNot { it.id == partID })
                        }
                    },
                )
            }
            "session.status" -> {
                val sessionID = properties.string("sessionID").orEmpty()
                if (!state.matches(sessionID)) return state
                val status = properties.obj("status")?.let { element ->
                    runCatching { ConnectionJson.decode(SessionStatusDto.serializer(), element) }.getOrNull()
                } ?: return state
                state.copy(status = status)
            }
            "session.idle" -> {
                val sessionID = properties.string("sessionID").orEmpty()
                if (!state.matches(sessionID)) return state
                state.copy(status = SessionStatusDto(type = "idle"))
            }
            "session.error" -> {
                val sessionID = properties.string("sessionID").orEmpty()
                if (!state.matches(sessionID)) return state
                val message = properties.obj("error")
                    ?.obj("data")
                    ?.string("message")
                    ?: properties.obj("error")?.string("name")
                    ?: "session error"
                state.copy(error = ServerError(ErrorMapping.redact(message)))
            }
            "permission.asked", "permission.v2.asked" -> {
                val sessionID = properties.string("sessionID") ?: return state
                if (!state.matches(sessionID)) return state
                val id = properties.string("id") ?: return state
                val permission = properties.string("permission")
                    ?: properties.string("action")
                    ?: ""
                val patterns = (properties["patterns"] as? JsonArray ?: properties["resources"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?: emptyList()
                val pending = PendingPermission(
                    id = id,
                    sessionID = sessionID,
                    permission = permission,
                    patterns = patterns,
                    metadata = properties.obj("metadata"),
                )
                state.copy(permissions = state.permissions.filterNot { it.id == id } + pending)
            }
            "permission.replied", "permission.v2.replied" -> {
                val requestID = properties.string("requestID") ?: return state
                state.copy(permissions = state.permissions.filterNot { it.id == requestID })
            }
            "question.asked", "question.v2.asked" -> {
                val sessionID = properties.string("sessionID") ?: return state
                if (!state.matches(sessionID)) return state
                val id = properties.string("id") ?: return state
                val questions = properties["questions"] as? JsonArray ?: JsonArray(emptyList())
                val pending = PendingQuestion(id = id, sessionID = sessionID, questions = questions)
                state.copy(questions = state.questions.filterNot { it.id == id } + pending)
            }
            "question.replied", "question.v2.replied", "question.rejected", "question.v2.rejected" -> {
                val requestID = properties.string("requestID") ?: return state
                state.copy(questions = state.questions.filterNot { it.id == requestID })
            }
            else -> state
        }
    }

    private suspend fun loadSession(sessionID: String) {
        val messages = api.messages(sessionID, directory)
        val status = api.status(directory)[sessionID] ?: SessionStatusDto()
        mutex.withLock {
            _state.update { current ->
                if (current.sessionID != sessionID) {
                    current
                } else {
                    current.copy(
                        messages = messages,
                        status = status,
                        phase = ConnectionPhase.Connected,
                        error = null,
                    )
                }
            }
        }
    }

    private suspend fun ensureSession(): String {
        _state.value.sessionID?.let { return it }
        val session = api.createSession(directory, CreateSessionRequestDto(title = "Dex"))
        _state.update {
            it.copy(
                sessions = upsertSession(it.sessions, session),
                sessionID = session.id,
            )
        }
        return session.id
    }

    private fun fail(error: ConnectionError) {
        log.add("Connection failed: ${error.message}")
        _state.update { it.copy(phase = ConnectionPhase.Failed, error = error) }
    }

    private fun onStreamError(error: ConnectionError) {
        log.add("Connection degraded: ${error.message}")
        _state.update {
            it.copy(
                phase = if (it.phase == ConnectionPhase.Failed) ConnectionPhase.Failed else ConnectionPhase.Reconnecting,
                error = error,
            )
        }
    }

    private fun ChatState.matches(sessionID: String): Boolean =
        sessionID.isEmpty() || this.sessionID == null || this.sessionID == sessionID

    private fun upsertSession(sessions: List<SessionDto>, session: SessionDto): List<SessionDto> =
        (sessions.filterNot { it.id == session.id } + session)
            .sortedByDescending { it.time.updated }

    private fun upsertMessage(messages: List<MessageEnvelopeDto>, info: MessageInfoDto): List<MessageEnvelopeDto> {
        val index = messages.indexOfFirst { it.info.id == info.id }
        return if (index < 0) {
            messages + MessageEnvelopeDto(info = info)
        } else {
            messages.toMutableList().also { it[index] = it[index].copy(info = info) }
        }
    }

    private fun upsertPart(messages: List<MessageEnvelopeDto>, part: PartDto): List<MessageEnvelopeDto> {
        val messageIndex = messages.indexOfFirst { it.info.id == part.messageID }
        if (messageIndex < 0) {
            val info = MessageInfoDto(id = part.messageID, sessionID = part.sessionID)
            return messages + MessageEnvelopeDto(info = info, parts = listOf(part))
        }
        val envelope = messages[messageIndex]
        val parts = envelope.parts.toMutableList()
        val partIndex = parts.indexOfFirst { it.id == part.id }
        if (partIndex < 0) {
            parts += part
        } else {
            parts[partIndex] = part
        }
        return messages.toMutableList().also { it[messageIndex] = envelope.copy(parts = parts) }
    }

    private fun appendTextDelta(
        messages: List<MessageEnvelopeDto>,
        sessionID: String,
        messageID: String,
        partID: String,
        delta: String,
    ): List<MessageEnvelopeDto> {
        val messageIndex = messages.indexOfFirst { it.info.id == messageID }
        if (messageIndex < 0) {
            val info = MessageInfoDto(id = messageID, sessionID = sessionID)
            val part = PartDto(
                id = partID,
                sessionID = sessionID,
                messageID = messageID,
                type = "text",
                text = delta,
            )
            return messages + MessageEnvelopeDto(info = info, parts = listOf(part))
        }
        val envelope = messages[messageIndex]
        val parts = envelope.parts.toMutableList()
        val partIndex = parts.indexOfFirst { it.id == partID }
        if (partIndex < 0) {
            parts += PartDto(
                id = partID,
                sessionID = sessionID,
                messageID = messageID,
                type = "text",
                text = delta,
            )
        } else {
            val existing = parts[partIndex]
            parts[partIndex] = existing.copy(text = (existing.text ?: "") + delta)
        }
        return messages.toMutableList().also { it[messageIndex] = envelope.copy(parts = parts) }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
