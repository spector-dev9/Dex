package io.github.spectordev9.opencode.core.opencode.client

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.gateway.OpenCodeGateway
import io.github.spectordev9.opencode.core.gateway.ServerEvent
import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.Session
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface OpenCodeClient : OpenCodeGateway {
    suspend fun listSessions(directory: String): ApiResult<List<Session>>

    suspend fun getSession(sessionID: String, directory: String): ApiResult<Session>

    suspend fun createSession(
        directory: String,
        title: String? = null,
        parentID: String? = null,
        agent: String? = null,
        model: SessionModelRef? = null,
    ): ApiResult<Session>

    suspend fun updateSession(
        sessionID: String,
        directory: String,
        title: String? = null,
        archived: Boolean? = null,
    ): ApiResult<Session>

    suspend fun deleteSession(sessionID: String, directory: String): ApiResult<Boolean>

    suspend fun sessionStatuses(directory: String): ApiResult<JsonObject>

    suspend fun forkSession(
        sessionID: String,
        directory: String,
        messageID: String? = null,
    ): ApiResult<Session>

    suspend fun abortSession(sessionID: String, directory: String): ApiResult<Boolean>

    suspend fun revertSession(
        sessionID: String,
        directory: String,
        messageID: String,
        partID: String? = null,
    ): ApiResult<Session>

    suspend fun unrevertSession(sessionID: String, directory: String): ApiResult<Session>

    suspend fun summarizeSession(
        sessionID: String,
        directory: String,
        providerID: String,
        modelID: String,
        auto: Boolean = false,
    ): ApiResult<Boolean>

    suspend fun sessionDiff(
        sessionID: String,
        directory: String,
    ): ApiResult<List<SessionFileDiff>>

    suspend fun listMessages(sessionID: String, directory: String): ApiResult<List<Message>>

    suspend fun promptAsync(
        sessionID: String,
        directory: String,
        prompt: PromptInput,
    ): ApiResult<Unit>

    suspend fun runCommand(
        sessionID: String,
        directory: String,
        command: String,
        arguments: String,
        agent: String? = null,
        model: PromptModel? = null,
        messageID: String? = null,
    ): ApiResult<Message>

    suspend fun runShell(
        sessionID: String,
        directory: String,
        command: String,
        agent: String,
        model: PromptModel? = null,
        messageID: String? = null,
    ): ApiResult<Message>

    suspend fun replyPermission(
        sessionID: String,
        permissionID: String,
        directory: String,
        response: PermissionResponse,
    ): ApiResult<Boolean>

    suspend fun listFiles(path: String, directory: String): ApiResult<List<FileEntry>>
}

@Serializable
data class SessionModelRef(
    val id: String,
    val providerID: String,
    val variant: String? = null,
)

@Serializable
data class SessionFileDiff(
    val file: String? = null,
    val patch: String? = null,
    val additions: Double,
    val deletions: Double,
    val status: String? = null,
)

@Serializable
enum class PermissionResponse {
    @SerialName("once")
    ONCE,

    @SerialName("always")
    ALWAYS,

    @SerialName("reject")
    REJECT,
}

@Serializable
data class PromptInput(
    val parts: List<PromptPart>,
    val messageID: String? = null,
    val model: PromptModel? = null,
    val agent: String? = null,
    val noReply: Boolean? = null,
    val system: String? = null,
    val variant: String? = null,
)

@Serializable
data class PromptPart(
    val type: String = "text",
    val text: String,
)

@Serializable
data class PromptModel(
    val providerID: String,
    val modelID: String,
)

@Serializable
internal data class HealthResponse(
    val healthy: Boolean,
    val version: String,
)
