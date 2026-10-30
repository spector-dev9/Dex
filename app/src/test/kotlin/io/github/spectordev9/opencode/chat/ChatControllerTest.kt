package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.app.bridge.FakeOpenCodeClient
import io.github.spectordev9.opencode.core.gateway.ServerEvent
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.model.SessionTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatControllerTest {
    @Test
    fun `agent parser exposes visible server agents and excludes hidden entries`() {
        val agents = parseAgents(
            Json,
            """
            [
              {"name":"build","mode":"primary","description":"Build and edit","hidden":false},
              {"name":"review","mode":"subagent","description":"Review code","hidden":false},
              {"name":"internal","mode":"all","hidden":true},
              {"mode":"primary"}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("build", "review"), agents.map { it.id })
        assertEquals("subagent", agents.last().mode)
    }

    @Test
    fun `agent parser rejects an invalid top-level response shape`() {
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            parseAgents(Json, """{"agents":[]}""")
        }
    }

    @Test
    fun `selected agent identifier is sent with the prompt`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient()
        try {
            val controller = ChatController(scope)
            controller.attach(client, "/workspace")
            controller.setMode("review")

            assertTrue(controller.send("Review this change"))
            assertEquals("review", client.lastPrompt?.agent)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `uses a fallback model when provider metadata is unavailable`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient()
        try {
            val controller = ChatController(scope)
            controller.attach(client, "/workspace")
            controller.setModel(null)

            assertTrue(controller.send("Do this verification"))
            assertEquals("opencode-mobile-mock", client.lastPrompt?.model?.providerID)
            assertEquals("mock", client.lastPrompt?.model?.modelID)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `idle events complete the task without resending the prompt`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient()
        try {
            val controller = ChatController(scope)
            controller.attach(client, "/workspace")
            assertTrue(controller.send("Finish this"))
            val sent = client.promptCalls

            client.emitEvent(
                ServerEvent(
                    type = "session.idle",
                    payload = buildJsonObject { put("sessionID", "ses_123") },
                ),
            )
            repeat(50) {
                if (controller.state.value.taskStatus == TaskStatus.COMPLETED) return@repeat
                delay(10)
            }

            assertEquals(TaskStatus.COMPLETED, controller.state.value.taskStatus)
            assertEquals(false, controller.state.value.sending)
            assertEquals(sent, client.promptCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `detach marks an in-flight task interrupted and reconcile never resends`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient()
        try {
            val controller = ChatController(scope)
            controller.attach(client, "/workspace")
            assertTrue(controller.send("Long running task"))
            assertEquals(TaskStatus.RUNNING, controller.state.value.taskStatus)
            val sent = client.promptCalls

            controller.reconcile()
            assertEquals(sent, client.promptCalls)
            assertEquals(TaskStatus.RUNNING, controller.state.value.taskStatus)

            controller.detach()
            assertEquals(TaskStatus.INTERRUPTED, controller.state.value.taskStatus)
            assertEquals(sent, client.promptCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `restores saved session instead of selecting the most recently updated one`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient().apply {
            sessions["ses_recent"] = Session(
                id = "ses_recent",
                slug = "recent",
                projectID = "prj_1",
                directory = "/workspace",
                title = "Recently updated",
                version = "1.19.3",
                time = SessionTime(created = 3, updated = 10),
            )
        }
        val store = InMemoryChatSessionStore("ses_123")
        try {
            val controller = ChatController(scope, store)
            controller.attach(client, "/workspace")

            assertEquals("ses_123", controller.state.value.activeSessionId)

            controller.newSession()
            assertEquals("ses_new", controller.state.value.activeSessionId)
            assertEquals("ses_new", store.activeSessionId("/workspace"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `discards a saved session that is no longer available`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val store = InMemoryChatSessionStore("ses_missing")
        try {
            val controller = ChatController(scope, store)
            controller.attach(FakeOpenCodeClient(), "/workspace")

            assertEquals("ses_123", controller.state.value.activeSessionId)
            assertEquals("ses_123", store.activeSessionId("/workspace"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `config model parser reads provider and model identifiers`() {
        val model = parseConfigModel(Json, """{"model":"opencode-mobile-mock/mock"}""")

        assertEquals("opencode-mobile-mock", model?.providerID)
        assertEquals("mock", model?.modelID)
    }

    @Test
    fun `config model parser keeps slashes inside the model identifier`() {
        val model = parseConfigModel(Json, """{"model":"device/vendor/model-1"}""")

        assertEquals("device", model?.providerID)
        assertEquals("vendor/model-1", model?.modelID)
    }

    @Test
    fun `config model parser ignores unusable model values`() {
        assertEquals(null, parseConfigModel(Json, """{"model":""}"""))
        assertEquals(null, parseConfigModel(Json, """{"model":"missing-provider"}"""))
        assertEquals(null, parseConfigModel(Json, """{"model":"/missing-provider"}"""))
        assertEquals(null, parseConfigModel(Json, """{"model":"device/"}"""))
    }

    @Test
    fun `configured model overrides the first config providers default`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient().apply {
            rawResponses["config.providers"] = """
                {
                  "providers": [
                    {"id":"opencode","name":"OpenCode","models":{"big-pickle":{"name":"Big Pickle"}}},
                    {"id":"opencode-mobile-mock","name":"Mock","models":{"mock":{"name":"Mock"}}}
                  ],
                  "default": {"opencode":"big-pickle","opencode-mobile-mock":"mock"}
                }
            """.trimIndent()
            rawResponses["config.get"] = """{"model":"opencode-mobile-mock/mock"}"""
        }
        try {
            val controller = ChatController(scope)
            controller.attach(client, "/workspace")

            assertEquals("opencode-mobile-mock", controller.state.value.defaultModel?.providerID)
            assertEquals("opencode-mobile-mock", controller.state.value.selectedModel?.providerID)
            assertTrue(controller.send("Use the configured model"))
            assertEquals("opencode-mobile-mock", client.lastPrompt?.model?.providerID)
            assertEquals("mock", client.lastPrompt?.model?.modelID)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `fallback model prefers the custom provider over the built-in provider`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient().apply {
            rawResponses["config.providers"] = """
                {
                  "providers": [
                    {"id":"opencode","name":"OpenCode","models":{"big-pickle":{"name":"Big Pickle"}}},
                    {"id":"device","name":"Device provider","models":{"local-model":{"name":"Local"}}}
                  ]
                }
            """.trimIndent()
        }
        try {
            val controller = ChatController(scope)
            controller.attach(client, "/workspace")

            assertTrue(controller.send("Use the local model"))
            assertEquals("device", client.lastPrompt?.model?.providerID)
            assertEquals("local-model", client.lastPrompt?.model?.modelID)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `default model parser prefers the custom provider in the map`() {
        val model = parseDefaultModel(
            Json,
            """{"default":{"opencode":"big-pickle","device":"local-model"}}""",
        )

        assertEquals("device", model?.providerID)
        assertEquals("local-model", model?.modelID)
    }

    @Test
    fun `default model parser reads the config providers map shape`() {
        val model = parseDefaultModel(
            Json,
            """{"providers":[],"default":{"opencode-mobile-mock":"mock"}}""",
        )

        assertEquals("opencode-mobile-mock", model?.providerID)
        assertEquals("mock", model?.modelID)
    }

    @Test
    fun `default model parser still accepts the flat legacy shape`() {
        val model = parseDefaultModel(
            Json,
            """{"default":{"providerID":"p","modelID":"m"}}""",
        )

        assertEquals("p", model?.providerID)
        assertEquals("m", model?.modelID)
    }

    @Test
    fun `default model parser ignores an unusable default map`() {
        assertEquals(null, parseDefaultModel(Json, """{"providers":[]}"""))
        assertEquals(null, parseDefaultModel(Json, """{"default":{}}"""))
    }
}

private class InMemoryChatSessionStore(initialSessionID: String?) : ChatSessionStore {
    private val sessions = mutableMapOf("/workspace" to initialSessionID)

    override fun activeSessionId(directory: String): String? = sessions[directory]

    override fun setActiveSessionId(directory: String, sessionID: String?) {
        if (sessionID == null) sessions.remove(directory) else sessions[directory] = sessionID
    }
}
