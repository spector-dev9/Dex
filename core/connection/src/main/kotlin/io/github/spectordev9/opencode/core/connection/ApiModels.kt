package io.github.spectordev9.opencode.core.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class HealthDto(
    val healthy: Boolean = false,
    val version: String = "",
)

@Serializable
data class SessionTimeDto(
    val created: Long = 0,
    val updated: Long = 0,
)

@Serializable
data class ModelRefDto(
    val id: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val slug: String = "",
    val projectID: String = "",
    val directory: String = "",
    val parentID: String? = null,
    val title: String = "",
    val version: String = "",
    val time: SessionTimeDto = SessionTimeDto(),
    val agent: String? = null,
    val model: ModelRefDto? = null,
)

@Serializable
data class MessageTimeDto(
    val created: Long = 0,
    val completed: Long? = null,
)

@Serializable
data class MessageErrorDto(
    val name: String = "",
    val data: JsonObject? = null,
)

@Serializable
data class MessageInfoDto(
    val id: String,
    val sessionID: String = "",
    val role: String = "",
    val time: MessageTimeDto = MessageTimeDto(),
    val error: MessageErrorDto? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val agent: String? = null,
    val cost: Double = 0.0,
)

@Serializable
data class PartTimeDto(
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
data class PartDto(
    val id: String = "",
    val sessionID: String = "",
    val messageID: String = "",
    val type: String = "",
    val text: String? = null,
    val time: PartTimeDto? = null,
    val state: JsonObject? = null,
)

@Serializable
data class MessageEnvelopeDto(
    val info: MessageInfoDto,
    val parts: List<PartDto> = emptyList(),
)

@Serializable
data class SessionStatusDto(
    val type: String = "idle",
    val attempt: Int? = null,
    val message: String? = null,
    val next: Long? = null,
)

@Serializable
data class ProviderDto(
    val id: String,
    val name: String = "",
    val models: Map<String, JsonObject> = emptyMap(),
)

@Serializable
data class ProviderCatalogDto(
    val providers: List<ProviderDto> = emptyList(),
    val default: Map<String, String> = emptyMap(),
)

@Serializable
data class EventDto(
    val id: String = "",
    val type: String = "",
    val properties: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CreateSessionRequestDto(
    val title: String? = null,
    val agent: String? = null,
    val model: ModelRefDto? = null,
)

@Serializable
data class PromptPartDto(
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
)

@Serializable
data class PromptModelDto(
    val providerID: String,
    val modelID: String,
)

@Serializable
data class PromptRequestDto(
    val parts: List<PromptPartDto>,
    val model: PromptModelDto? = null,
    val agent: String? = null,
    val messageID: String? = null,
    val noReply: Boolean? = null,
    val system: String? = null,
    val variant: String? = null,
)

@Serializable
data class CommandRequestDto(
    val command: String,
    val arguments: String = "",
    val agent: String? = null,
    val model: PromptModelDto? = null,
    val variant: String? = null,
)

@Serializable
data class PermissionReplyDto(
    val reply: String,
)

@Serializable
data class QuestionReplyDto(
    val answers: List<List<String>>,
)
