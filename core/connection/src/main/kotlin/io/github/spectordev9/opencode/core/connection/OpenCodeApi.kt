package io.github.spectordev9.opencode.core.connection

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface OpenCodeApi {
    suspend fun health(): HealthDto
    suspend fun providers(): ProviderCatalogDto
    suspend fun sessions(directory: String): List<SessionDto>
    suspend fun createSession(directory: String, request: CreateSessionRequestDto = CreateSessionRequestDto()): SessionDto
    suspend fun messages(sessionID: String, directory: String): List<MessageEnvelopeDto>
    suspend fun status(directory: String): Map<String, SessionStatusDto>
    suspend fun promptAsync(sessionID: String, directory: String, request: PromptRequestDto)
    suspend fun abort(sessionID: String, directory: String): Boolean
    suspend fun respondPermission(
        permissionID: String,
        directory: String,
        reply: String,
    ): Boolean
    suspend fun replyQuestion(
        requestID: String,
        directory: String,
        answers: List<List<String>>,
    ): Boolean
    suspend fun rejectQuestion(requestID: String, directory: String): Boolean
    suspend fun command(sessionID: String, directory: String, request: CommandRequestDto): MessageEnvelopeDto
    suspend fun openEventStream(directory: String): Response
    fun close()
}

class OpenCodeHttpApi(
    private val endpoint: Endpoint,
    private val clients: HttpClients = HttpClients(endpoint),
) : OpenCodeApi {
    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun health(): HealthDto =
        execute(Request.Builder().url(endpoint.url("global/health")).get().build()) { body ->
            ConnectionJson.decode(HealthDto.serializer(), body).also {
                if (!it.healthy) throw ServerNotReady("health endpoint reported unhealthy")
            }
        }

    override suspend fun providers(): ProviderCatalogDto =
        execute(Request.Builder().url(endpoint.url("config/providers")).get().build()) { body ->
            ConnectionJson.decode(ProviderCatalogDto.serializer(), body).also {
                if (it.providers.isEmpty()) throw NoProviderConfigured()
            }
        }

    override suspend fun sessions(directory: String): List<SessionDto> =
        execute(Request.Builder().url(endpoint.url("session", directory)).get().build()) { body ->
            ConnectionJson.decode(ListSerializer(SessionDto.serializer()), body)
        }

    override suspend fun createSession(
        directory: String,
        request: CreateSessionRequestDto,
    ): SessionDto {
        val body = ConnectionJson.encode(CreateSessionRequestDto.serializer(), request)
        return execute(
            Request.Builder()
                .url(endpoint.url("session", directory))
                .post(body.toRequestBody(jsonMediaType))
                .build(),
        ) { raw -> ConnectionJson.decode(SessionDto.serializer(), raw) }
    }

    override suspend fun messages(sessionID: String, directory: String): List<MessageEnvelopeDto> =
        execute(
            Request.Builder().url(endpoint.url("session/$sessionID/message", directory)).get().build(),
        ) { body -> ConnectionJson.decode(ListSerializer(MessageEnvelopeDto.serializer()), body) }

    override suspend fun status(directory: String): Map<String, SessionStatusDto> =
        execute(Request.Builder().url(endpoint.url("session/status", directory)).get().build()) { body ->
            ConnectionJson.decode(
                MapSerializer(String.serializer(), SessionStatusDto.serializer()),
                body,
            )
        }

    override suspend fun promptAsync(
        sessionID: String,
        directory: String,
        request: PromptRequestDto,
    ) {
        val body = ConnectionJson.encode(PromptRequestDto.serializer(), request)
        executeUnit(
            Request.Builder()
                .url(endpoint.url("session/$sessionID/prompt_async", directory))
                .post(body.toRequestBody(jsonMediaType))
                .build(),
        )
    }

    override suspend fun abort(sessionID: String, directory: String): Boolean =
        execute(
            Request.Builder()
                .url(endpoint.url("session/$sessionID/abort", directory))
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        ) { body -> ConnectionJson.decode(Boolean.serializer(), body) }

    override suspend fun respondPermission(
        permissionID: String,
        directory: String,
        reply: String,
    ): Boolean {
        val body = ConnectionJson.encode(PermissionReplyDto.serializer(), PermissionReplyDto(reply))
        return execute(
            Request.Builder()
                .url(endpoint.url("permission/$permissionID/reply", directory))
                .post(body.toRequestBody(jsonMediaType))
                .build(),
        ) { raw -> ConnectionJson.decode(Boolean.serializer(), raw) }
    }

    override suspend fun replyQuestion(
        requestID: String,
        directory: String,
        answers: List<List<String>>,
    ): Boolean {
        val body = ConnectionJson.encode(QuestionReplyDto.serializer(), QuestionReplyDto(answers))
        return execute(
            Request.Builder()
                .url(endpoint.url("question/$requestID/reply", directory))
                .post(body.toRequestBody(jsonMediaType))
                .build(),
        ) { raw -> ConnectionJson.decode(Boolean.serializer(), raw) }
    }

    override suspend fun rejectQuestion(requestID: String, directory: String): Boolean =
        execute(
            Request.Builder()
                .url(endpoint.url("question/$requestID/reject", directory))
                .post(ByteArray(0).toRequestBody(null))
                .build(),
        ) { raw -> ConnectionJson.decode(Boolean.serializer(), raw) }

    override suspend fun command(
        sessionID: String,
        directory: String,
        request: CommandRequestDto,
    ): MessageEnvelopeDto {
        val body = ConnectionJson.encode(CommandRequestDto.serializer(), request)
        return execute(
            Request.Builder()
                .url(endpoint.url("session/$sessionID/command", directory))
                .post(body.toRequestBody(jsonMediaType))
                .build(),
        ) { raw -> ConnectionJson.decode(MessageEnvelopeDto.serializer(), raw) }
    }

    override suspend fun openEventStream(directory: String): Response =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint.url("event", directory))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            val response = try {
                clients.sse.newCall(request).execute()
            } catch (error: IOException) {
                throw ErrorMapping.fromThrowable(error)
            }
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                response.close()
                throw ErrorMapping.fromStatus(response.code, body)
            }
            response
        }

    override fun close() {
        clients.close()
    }

    private suspend fun <T> execute(request: Request, decode: (String) -> T): T =
        withContext(Dispatchers.IO) {
            val response = try {
                clients.rest.newCall(request).execute()
            } catch (error: IOException) {
                throw ErrorMapping.fromThrowable(error)
            }
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw ErrorMapping.fromStatus(it.code, body)
                decode(body)
            }
        }

    private suspend fun executeUnit(request: Request) {
        withContext(Dispatchers.IO) {
            val response = try {
                clients.rest.newCall(request).execute()
            } catch (error: IOException) {
                throw ErrorMapping.fromThrowable(error)
            }
            response.use {
                if (!it.isSuccessful) {
                    throw ErrorMapping.fromStatus(it.code, it.body?.string().orEmpty())
                }
            }
        }
    }
}
