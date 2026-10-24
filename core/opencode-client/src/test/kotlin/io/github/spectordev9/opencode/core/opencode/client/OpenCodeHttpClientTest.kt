package io.github.spectordev9.opencode.core.opencode.client

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
}
