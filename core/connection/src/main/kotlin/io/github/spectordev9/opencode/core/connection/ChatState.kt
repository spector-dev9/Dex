package io.github.spectordev9.opencode.core.connection

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class ConnectionPhase {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Failed,
}

data class PendingPermission(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: JsonObject? = null,
)

data class PendingQuestion(
    val id: String,
    val sessionID: String,
    val questions: JsonArray = JsonArray(emptyList()),
)

data class ChatState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val sessions: List<SessionDto> = emptyList(),
    val sessionID: String? = null,
    val messages: List<MessageEnvelopeDto> = emptyList(),
    val status: SessionStatusDto = SessionStatusDto(),
    val permissions: List<PendingPermission> = emptyList(),
    val questions: List<PendingQuestion> = emptyList(),
    val providers: ProviderCatalogDto? = null,
    val error: ConnectionError? = null,
    val lastEventID: String? = null,
) {
    val activeSession: SessionDto?
        get() = sessions.firstOrNull { it.id == sessionID }
}
