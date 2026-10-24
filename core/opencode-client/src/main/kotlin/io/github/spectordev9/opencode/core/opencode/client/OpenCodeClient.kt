package io.github.spectordev9.opencode.core.opencode.client

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.gateway.OpenCodeGateway
import io.github.spectordev9.opencode.core.gateway.ServerEvent
import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface OpenCodeClient : OpenCodeGateway {
    suspend fun listSessions(directory: String): ApiResult<List<Session>>

    suspend fun getSession(sessionID: String, directory: String): ApiResult<Session>

    suspend fun listMessages(sessionID: String, directory: String): ApiResult<List<Message>>

    suspend fun listFiles(path: String, directory: String): ApiResult<List<FileEntry>>

    suspend fun promptAsync(
        sessionID: String,
        directory: String,
        prompt: PromptInput,
    ): ApiResult<Unit>
}

@Serializable
data class PromptInput(
    val parts: List<PromptPart>,
)

@Serializable
data class PromptPart(
    val type: String = "text",
    val text: String,
)

@Serializable
internal data class HealthResponse(
    val healthy: Boolean,
    val version: String,
)
