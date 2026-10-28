package io.github.spectordev9.opencode.core.opencode.client

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OpenCodeHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OpenCodeHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OpenCodeHttpClient(server.url("/").toString(), "test-password")
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `prompt async uses pinned route basic auth and directory scope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.promptAsync(
            sessionID = "ses_123",
            directory = "/workspace with spaces",
            prompt = PromptInput(parts = listOf(PromptPart(text = "hello"))),
        )
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8())

        assertTrue(result is ApiResult.Success)
        assertEquals("POST", request.method)
        assertEquals("/session/ses_123/prompt_async", request.requestUrl?.encodedPath)
        assertEquals(
            "/workspace with spaces",
            request.requestUrl?.queryParameter("directory"),
        )
        assertEquals(Credentials.basic("opencode", "test-password"), request.getHeader("Authorization"))
        assertEquals(
            "hello",
            body.jsonObject["parts"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `prompt async serializes OpenCode file parts`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.promptAsync(
            sessionID = "ses_123",
            directory = "/workspace",
            prompt = PromptInput(
                parts = listOf(
                    PromptPart(
                        type = "file",
                        mime = "image/png",
                        filename = "screenshot.png",
                        url = "data:image/png;base64,aGVsbG8=",
                    ),
                ),
            ),
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
        val filePart = body.jsonObject["parts"]!!.jsonArray.single().jsonObject

        assertTrue(result is ApiResult.Success)
        assertEquals("file", filePart["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", filePart["mime"]!!.jsonPrimitive.content)
        assertEquals("screenshot.png", filePart["filename"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,aGVsbG8=", filePart["url"]!!.jsonPrimitive.content)
        assertTrue("text" !in filePart)
    }

    @Test
    fun `abort session preserves a negative server acknowledgement`() = runBlocking {
        server.enqueue(MockResponse().setBody("false"))

        val result = client.abortSession("ses_123", "/workspace")
        val request = server.takeRequest()

        assertEquals(ApiResult.Success(false), result)
        assertEquals("POST", request.method)
        assertEquals("/session/ses_123/abort", request.requestUrl?.encodedPath)
        assertEquals("/workspace", request.requestUrl?.queryParameter("directory"))
    }

    @Test
    fun `session create update summarize and permission use pinned routes`() = runBlocking {
        val sessionBody = """
            {
              "id":"ses_123","slug":"sample","projectID":"prj_1",
              "directory":"/workspace","title":"Created","version":"1.19.3",
              "time":{"created":1,"updated":2}
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(sessionBody))
        server.enqueue(MockResponse().setBody(sessionBody))
        server.enqueue(MockResponse().setBody("true"))
        server.enqueue(MockResponse().setBody("true"))

        assertTrue(
            client.createSession(
                directory = "/workspace",
                title = "Created",
                agent = "build",
                model = SessionModelRef(id = "model", providerID = "provider"),
            ) is ApiResult.Success,
        )
        val create = server.takeRequest()
        val createBody = Json.parseToJsonElement(create.body.readUtf8()).jsonObject
        assertEquals("POST", create.method)
        assertEquals("/session", create.requestUrl?.encodedPath)
        assertEquals("/workspace", create.requestUrl?.queryParameter("directory"))
        assertEquals("Created", createBody["title"]!!.jsonPrimitive.content)
        assertEquals("provider", createBody["model"]!!.jsonObject["providerID"]!!.jsonPrimitive.content)

        assertTrue(
            client.updateSession("ses_123", "/workspace", title = "Renamed", archived = true) is ApiResult.Success,
        )
        val update = server.takeRequest()
        val updateBody = Json.parseToJsonElement(update.body.readUtf8()).jsonObject
        assertEquals("PATCH", update.method)
        assertEquals("/session/ses_123", update.requestUrl?.encodedPath)
        assertEquals("Renamed", updateBody["title"]!!.jsonPrimitive.content)
        assertTrue(updateBody["time"]!!.jsonObject["archived"]!!.jsonPrimitive.content.toLong() > 0L)

        assertTrue(
            client.summarizeSession(
                sessionID = "ses_123",
                directory = "/workspace",
                providerID = "provider",
                modelID = "model",
                auto = true,
            ) is ApiResult.Success,
        )
        val summarize = server.takeRequest()
        val summarizeBody = Json.parseToJsonElement(summarize.body.readUtf8()).jsonObject
        assertEquals("/session/ses_123/summarize", summarize.requestUrl?.encodedPath)
        assertEquals("provider", summarizeBody["providerID"]!!.jsonPrimitive.content)
        assertEquals("model", summarizeBody["modelID"]!!.jsonPrimitive.content)
        assertTrue(summarizeBody["auto"]!!.jsonPrimitive.content.toBoolean())

        assertTrue(
            client.replyPermission(
                sessionID = "ses_123",
                permissionID = "per_123",
                directory = "/workspace",
                response = PermissionResponse.ALWAYS,
            ) is ApiResult.Success,
        )
        val permission = server.takeRequest()
        val permissionBody = Json.parseToJsonElement(permission.body.readUtf8()).jsonObject
        assertEquals("/session/ses_123/permissions/per_123", permission.requestUrl?.encodedPath)
        assertEquals("always", permissionBody["response"]!!.jsonPrimitive.content)
    }

    @Test
    fun `decodes pinned session and file models`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [{
                      "id":"ses_123","slug":"sample","projectID":"prj_1",
                      "directory":"/workspace","title":"Sample","version":"1.19.3",
                      "time":{"created":1,"updated":2}
                    }]
                    """.trimIndent(),
                ),
        )

        val result = client.listSessions("/workspace")
        val request = server.takeRequest()

        val sessions = when (result) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw AssertionError(result.error.toString())
        }
        assertEquals("ses_123", sessions.single().id)
        assertEquals("/session", request.requestUrl?.encodedPath)
        assertEquals("/workspace", request.requestUrl?.queryParameter("directory"))
    }

    @Test
    fun `maps authentication failures without exposing credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret body"))

        val result = client.listSessions("/workspace")
        val error = when (result) {
            is ApiResult.Failure -> result.error
            is ApiResult.Success -> throw AssertionError("expected authentication failure")
        }

        assertEquals(ErrorCode.E_AUTH, error.code)
        assertTrue(error.message.orEmpty().contains("authentication"))
        assertTrue(!error.message.orEmpty().contains("test-password"))
    }

    @Test
    fun `maps an oversized chunked response to a typed size error`() = runBlocking {
        val body = " ".repeat((ResponseBodySizeGuard.DEFAULT_MAX_BYTES + 1).toInt())
        server.enqueue(MockResponse().setChunkedBody(body, 8 * 1024))

        val result = client.listSessions("/workspace")
        server.takeRequest()
        val error = when (result) {
            is ApiResult.Failure -> result.error
            is ApiResult.Success -> throw AssertionError("expected oversized-response failure")
        }

        assertEquals(ErrorCode.E_TOO_LARGE, error.code)
    }

    @Test
    fun `event flow authenticates and decodes server connected event`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"id\":\"evt_conn\",\"type\":\"server.connected\",\"properties\":{}}\n\n",
                ),
        )

        val event = client.events("/workspace").first()
        val request = server.takeRequest()

        assertEquals("server.connected", event.type)
        assertEquals("evt_conn", event.id)
        assertEquals("/workspace", event.directory)
        assertEquals("/event", request.requestUrl?.encodedPath)
        assertEquals("/workspace", request.requestUrl?.queryParameter("directory"))
        assertEquals(Credentials.basic("opencode", "test-password"), request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun `event stream accepts an event larger than the JSON response limit`() = runBlocking {
        val payload = "x".repeat((ResponseBodySizeGuard.DEFAULT_MAX_BYTES + 1).toInt())
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(
                    """data: {"id":"evt_large","type":"test.large","properties":{"content":"$payload"}}""" +
                        "\n\n",
                    8 * 1024,
                ),
        )

        val event = client.events().first()

        assertEquals(payload, (event.payload["content"] as? JsonPrimitive)?.content)
    }
}
