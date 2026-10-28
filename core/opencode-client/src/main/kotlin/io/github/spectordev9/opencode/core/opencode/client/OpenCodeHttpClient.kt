package io.github.spectordev9.opencode.core.opencode.client

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.OperationTimeouts
import io.github.spectordev9.opencode.core.common.TimeoutClass
import io.github.spectordev9.opencode.core.gateway.GatewayOperation
import io.github.spectordev9.opencode.core.gateway.GatewayRequest
import io.github.spectordev9.opencode.core.gateway.GatewayResponseDecoder
import io.github.spectordev9.opencode.core.gateway.GatewayHealth
import io.github.spectordev9.opencode.core.gateway.HttpMethod
import io.github.spectordev9.opencode.core.gateway.ServerEvent
import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

class OpenCodeHttpClient(
    baseUrl: String,
    password: String,
    httpClient: OkHttpClient = OkHttpClient(),
    private val random: Random = Random.Default,
) : OpenCodeClient {
    private val baseUrl: HttpUrl = baseUrl.toHttpUrl()
    private val httpClient = httpClient.newBuilder()
        .readTimeout(OperationTimeouts.SSE_IDLE_MILLIS, TimeUnit.MILLISECONDS)
        .build()
    private val eventSourceFactory = EventSources.createFactory(this.httpClient)
    private val authorization = Credentials.basic("opencode", password, StandardCharsets.UTF_8)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    init {
        require(this.baseUrl.scheme == "http" || this.baseUrl.scheme == "https") {
            "baseUrl must use HTTP or HTTPS"
        }
        require(this.baseUrl.encodedPath == "/") {
            "baseUrl must not contain a path"
        }
        require(password.isNotEmpty()) { "password must not be empty" }
    }

    override suspend fun <T> call(operation: GatewayOperation<T>): ApiResult<T> {
        val request = request(operation.request)
        val timeoutMillis = OperationTimeouts.defaultPolicy(operation.timeoutClass).timeoutMillis
        return try {
            val response = execute(request, timeoutMillis)
            if (response.code !in 200..299) {
                return ApiResult.Failure(httpError(response.code))
            }
            ApiResult.Success(operation.decoder.decode(response.code, response.body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (tooLarge: ResponseBodyTooLargeException) {
            ApiResult.Failure(
                ApiError(
                    ErrorCode.E_TOO_LARGE,
                    "OpenCode response exceeds the ${tooLarge.limitBytes / ResponseBodySizeGuard.MEBIBYTE} MiB limit",
                ),
            )
        } catch (timeout: SocketTimeoutException) {
            ApiResult.Failure(ApiError(ErrorCode.E_TIMEOUT, "OpenCode request timed out"))
        } catch (io: IOException) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "OpenCode server is unreachable"))
        } catch (invalidResponse: SerializationException) {
            ApiResult.Failure(
                ApiError(
                    ErrorCode.E_UPSTREAM,
                    "OpenCode returned an invalid response for ${operation.name}",
                ),
            )
        }
    }

    override suspend fun listSessions(directory: String): ApiResult<List<Session>> =
        call(
            operation(
                "session.list",
                HttpMethod.GET,
                path("session"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                listDecoder(Session.serializer()),
            ),
        )

    override suspend fun getSession(sessionID: String, directory: String): ApiResult<Session> =
        call(
            operation(
                "session.get",
                HttpMethod.GET,
                path("session", sessionID),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Session.serializer()),
            ),
        )

    override suspend fun createSession(
        directory: String,
        title: String?,
        parentID: String?,
        agent: String?,
        model: SessionModelRef?,
    ): ApiResult<Session> {
        val body = buildJsonObject {
            title?.let { put("title", it) }
            parentID?.let { put("parentID", it) }
            agent?.let { put("agent", it) }
            model?.let { ref ->
                put(
                    "model",
                    buildJsonObject {
                        put("id", ref.id)
                        put("providerID", ref.providerID)
                        ref.variant?.let { put("variant", it) }
                    },
                )
            }
        }
        return call(
            operation(
                "session.create",
                HttpMethod.POST,
                path("session"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Session.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun updateSession(
        sessionID: String,
        directory: String,
        title: String?,
        archived: Boolean?,
    ): ApiResult<Session> {
        val body = buildJsonObject {
            title?.let { put("title", it) }
            archived?.let { isArchived ->
                put(
                    "time",
                    buildJsonObject {
                        put("archived", if (isArchived) System.currentTimeMillis() else 0L)
                    },
                )
            }
        }
        return call(
            operation(
                "session.update",
                HttpMethod.PATCH,
                path("session", sessionID),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Session.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun deleteSession(sessionID: String, directory: String): ApiResult<Boolean> =
        call(
            operation(
                "session.delete",
                HttpMethod.DELETE,
                path("session", sessionID),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Boolean.serializer()),
            ),
        )

    override suspend fun sessionStatuses(directory: String): ApiResult<JsonObject> =
        call(
            operation(
                "session.status",
                HttpMethod.GET,
                path("session", "status"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(JsonObject.serializer()),
            ),
        )

    override suspend fun forkSession(
        sessionID: String,
        directory: String,
        messageID: String?,
    ): ApiResult<Session> {
        val body = buildJsonObject {
            messageID?.let { put("messageID", it) }
        }
        return call(
            operation(
                "session.fork",
                HttpMethod.POST,
                path("session", sessionID, "fork"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Session.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun abortSession(sessionID: String, directory: String): ApiResult<Boolean> =
        call(
            operation(
                "session.abort",
                HttpMethod.POST,
                path("session", sessionID, "abort"),
                mapOf("directory" to directory),
                TimeoutClass.ABORT,
                decoder(Boolean.serializer()),
            ),
        )

    override suspend fun revertSession(
        sessionID: String,
        directory: String,
        messageID: String,
        partID: String?,
    ): ApiResult<Session> {
        val body = buildJsonObject {
            put("messageID", messageID)
            partID?.let { put("partID", it) }
        }
        return call(
            operation(
                "session.revert",
                HttpMethod.POST,
                path("session", sessionID, "revert"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Session.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun unrevertSession(sessionID: String, directory: String): ApiResult<Session> =
        call(
            operation(
                "session.unrevert",
                HttpMethod.POST,
                path("session", sessionID, "unrevert"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Session.serializer()),
            ),
        )

    override suspend fun summarizeSession(
        sessionID: String,
        directory: String,
        providerID: String,
        modelID: String,
        auto: Boolean,
    ): ApiResult<Boolean> {
        val body = buildJsonObject {
            put("providerID", providerID)
            put("modelID", modelID)
            put("auto", auto)
        }
        return call(
            operation(
                "session.summarize",
                HttpMethod.POST,
                path("session", sessionID, "summarize"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Boolean.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun sessionDiff(
        sessionID: String,
        directory: String,
    ): ApiResult<List<SessionFileDiff>> =
        call(
            operation(
                "session.diff",
                HttpMethod.GET,
                path("session", sessionID, "diff"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                listDecoder(SessionFileDiff.serializer()),
            ),
        )

    override suspend fun listMessages(sessionID: String, directory: String): ApiResult<List<Message>> =
        call(
            operation(
                "session.messages",
                HttpMethod.GET,
                path("session", sessionID, "message"),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                listDecoder(Message.serializer()),
            ),
        )

    override suspend fun listFiles(path: String, directory: String): ApiResult<List<FileEntry>> =
        call(
            operation(
                "file.list",
                HttpMethod.GET,
                path("file"),
                mapOf("directory" to directory, "path" to path),
                TimeoutClass.LIST_GET,
                listDecoder(FileEntry.serializer()),
            ),
        )

    override suspend fun promptAsync(
        sessionID: String,
        directory: String,
        prompt: PromptInput,
    ): ApiResult<Unit> {
        val body = encode(prompt, PromptInput.serializer())
        return call(
            operation(
                "session.promptAsync",
                HttpMethod.POST,
                path("session", sessionID, "prompt_async"),
                mapOf("directory" to directory),
                TimeoutClass.PROMPT_ASYNC,
                GatewayResponseDecoder { _, _ -> Unit },
                body = body,
                contentType = "application/json",
            ),
        )
    }

    override suspend fun runCommand(
        sessionID: String,
        directory: String,
        command: String,
        arguments: String,
        agent: String?,
        model: PromptModel?,
        messageID: String?,
    ): ApiResult<Message> {
        val body = buildJsonObject {
            put("command", command)
            put("arguments", arguments)
            messageID?.let { put("messageID", it) }
            agent?.let { put("agent", it) }
            model?.let { ref ->
                put(
                    "model",
                    buildJsonObject {
                        put("providerID", ref.providerID)
                        put("modelID", ref.modelID)
                    },
                )
            }
        }
        return call(
            operation(
                "session.command",
                HttpMethod.POST,
                path("session", sessionID, "command"),
                mapOf("directory" to directory),
                TimeoutClass.PROMPT_ASYNC,
                decoder(Message.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun runShell(
        sessionID: String,
        directory: String,
        command: String,
        agent: String,
        model: PromptModel?,
        messageID: String?,
    ): ApiResult<Message> {
        val body = buildJsonObject {
            put("command", command)
            put("agent", agent)
            messageID?.let { put("messageID", it) }
            model?.let { ref ->
                put(
                    "model",
                    buildJsonObject {
                        put("providerID", ref.providerID)
                        put("modelID", ref.modelID)
                    },
                )
            }
        }
        return call(
            operation(
                "session.shell",
                HttpMethod.POST,
                path("session", sessionID, "shell"),
                mapOf("directory" to directory),
                TimeoutClass.PROMPT_ASYNC,
                decoder(Message.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun replyPermission(
        sessionID: String,
        permissionID: String,
        directory: String,
        response: PermissionResponse,
    ): ApiResult<Boolean> {
        val body = buildJsonObject {
            put("response", response.wireName)
        }
        return call(
            operation(
                "session.permissionReply",
                HttpMethod.POST,
                path("session", sessionID, "permissions", permissionID),
                mapOf("directory" to directory),
                TimeoutClass.LIST_GET,
                decoder(Boolean.serializer()),
                body = encode(body, JsonObject.serializer()),
                contentType = "application/json",
            ),
        )
    }

    override suspend fun health(): ApiResult<GatewayHealth> =
        call(
            operation(
                "server.health",
                HttpMethod.GET,
                path("global", "health"),
                timeoutClass = TimeoutClass.HEALTH,
                responseDecoder = decoder(HealthResponse.serializer()),
            ),
        ).map { health ->
            GatewayHealth(
                version = health.version,
                endpointPort = baseUrl.port,
            )
        }

    override fun events(directory: String?): Flow<ServerEvent> = callbackFlow {
        val attempt = AtomicInteger(0)
        val request = request(
            GatewayRequest(
                method = HttpMethod.GET,
                path = path("event"),
                query = directory?.let { mapOf("directory" to it) }.orEmpty(),
            ),
            accept = "text/event-stream",
        )
        val worker = launch {
            while (currentCoroutineContext().isActive) {
                val reconnect = CompletableDeferred<Boolean>()
                val source = eventSourceFactory.newEventSource(
                    request,
                    object : EventSourceListener() {
                        override fun onOpen(eventSource: EventSource, response: Response) {
                            attempt.set(0)
                        }

                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            val event = try {
                                parseEvent(data, directory)
                            } catch (invalidEvent: SerializationException) {
                                close(
                                    OpenCodeClientException(
                                        ApiError(
                                            ErrorCode.E_UPSTREAM,
                                            "OpenCode returned an invalid event",
                                        ),
                                    ),
                                )
                                reconnect.complete(false)
                                return
                            }
                            if (trySend(event).isFailure) {
                                close(IllegalStateException("OpenCode event consumer cannot keep up"))
                                reconnect.complete(false)
                            }
                        }

                        override fun onClosed(eventSource: EventSource) {
                            reconnect.complete(true)
                        }

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            val statusCode = response?.code
                            if (statusCode != null && statusCode in 400..499) {
                                close(OpenCodeClientException(httpError(statusCode)))
                                reconnect.complete(false)
                            } else {
                                reconnect.complete(true)
                            }
                        }
                    },
                )
                val shouldReconnect = reconnect.await()
                source.cancel()
                if (!shouldReconnect || !currentCoroutineContext().isActive) break
                val retry = attempt.getAndIncrement()
                val cap = minOf(30_000L, 500L * (1L shl retry.coerceAtMost(16)))
                delay((random.nextDouble() * cap).toLong())
            }
        }
        awaitClose { worker.cancel() }
    }

    private fun parseEvent(data: String, directory: String?): ServerEvent {
        val root = json.parseToJsonElement(data) as? JsonObject
            ?: throw SerializationException("Event must be a JSON object")
        fun stringField(name: String): String? =
            (root[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

        val id = stringField("id")
        val type = stringField("type")
        if (id.isNullOrBlank() || type.isNullOrBlank()) {
            throw SerializationException("Event requires id and type")
        }
        val properties = when (val value = root["properties"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> value
            else -> throw SerializationException("Event properties must be an object")
        }
        return ServerEvent(
            type = type,
            directory = directory,
            payload = properties,
            id = id,
        )
    }

    private fun request(
        gatewayRequest: GatewayRequest,
        accept: String = "application/json",
        body: okhttp3.RequestBody? = gatewayRequest.body?.toRequestBody(
            gatewayRequest.contentType?.toMediaType(),
        ),
    ): Request {
        val urlBuilder = baseUrl.newBuilder().encodedPath(gatewayRequest.path).query(null)
        gatewayRequest.query.forEach { (name, value) ->
            urlBuilder.addQueryParameter(name, value)
        }
        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", authorization)
            .header("Accept", accept)
        val requestBody =
            if (body == null && gatewayRequest.method in setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH)) {
                ByteArray(0).toRequestBody(null)
            } else {
                body
            }
        return requestBuilder.method(gatewayRequest.method.name, requestBody).build()
    }

    private fun path(vararg segments: String): String =
        baseUrl.newBuilder()
            .encodedPath("/")
            .apply { segments.forEach(::addPathSegment) }
            .build()
            .encodedPath

    private suspend fun execute(request: Request, timeoutMillis: Long?): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            if (timeoutMillis != null) {
                call.timeout().timeout(timeoutMillis, TimeUnit.MILLISECONDS)
            }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                HttpResponse(
                                    code = it.code,
                                    body = if (it.code in 200..299) {
                                        it.body?.let { body ->
                                            ResponseBodySizeGuard.read(
                                                body.byteStream(),
                                                body.contentLength(),
                                                ResponseBodySizeGuard.limitForPath(request.url.encodedPath),
                                            )
                                        } ?: ByteArray(0)
                                    } else {
                                        ByteArray(0)
                                    },
                                )
                            }
                            if (continuation.isActive) continuation.resume(result)
                        } catch (failure: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(failure)
                        }
                    }
                },
            )
        }

    private fun httpError(statusCode: Int): ApiError = when (statusCode) {
        401 -> ApiError(ErrorCode.E_AUTH, "OpenCode authentication failed (HTTP $statusCode)", statusCode)
        403 -> ApiError(ErrorCode.E_PATH_DENIED, "OpenCode denied the request (HTTP $statusCode)", statusCode)
        408, 504 -> ApiError(ErrorCode.E_TIMEOUT, "OpenCode request timed out (HTTP $statusCode)", statusCode)
        else -> ApiError(ErrorCode.E_UPSTREAM, "OpenCode request failed (HTTP $statusCode)", statusCode)
    }

    private fun <T> operation(
        name: String,
        method: HttpMethod,
        path: String,
        query: Map<String, String> = emptyMap(),
        timeoutClass: TimeoutClass,
        responseDecoder: GatewayResponseDecoder<T>,
        body: ByteArray? = null,
        contentType: String? = null,
    ): GatewayOperation<T> =
        GatewayOperation(
            name = name,
            request = GatewayRequest(
                method = method,
                path = path,
                query = query,
                body = body,
                contentType = contentType,
            ),
            timeoutClass = timeoutClass,
            decoder = responseDecoder,
        )

    private fun <T> decoder(serializer: KSerializer<T>) =
        GatewayResponseDecoder<T> { _, body ->
            json.decodeFromString(serializer, body.toString(StandardCharsets.UTF_8))
        }

    private fun <T> encode(value: T, serializer: KSerializer<T>): ByteArray =
        json.encodeToString(serializer, value).encodeToByteArray()

    private fun <T> listDecoder(serializer: kotlinx.serialization.KSerializer<T>) =
        decoder(ListSerializer(serializer))

    private fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> =
        when (this) {
            is ApiResult.Success -> ApiResult.Success(transform(value))
            is ApiResult.Failure -> this
        }

    private val PermissionResponse.wireName: String
        get() = when (this) {
            PermissionResponse.ONCE -> "once"
            PermissionResponse.ALWAYS -> "always"
            PermissionResponse.REJECT -> "reject"
        }

    private data class HttpResponse(
        val code: Int,
        val body: ByteArray,
    )

}

class OpenCodeClientException(
    val apiError: ApiError,
) : IOException(apiError.message)
