package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.app.bridge.FakeOpenCodeClient
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.model.SessionTime
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
}

private class InMemoryChatSessionStore(initialSessionID: String?) : ChatSessionStore {
    private val sessions = mutableMapOf("/workspace" to initialSessionID)

    override fun activeSessionId(directory: String): String? = sessions[directory]

    override fun setActiveSessionId(directory: String, sessionID: String?) {
        if (sessionID == null) sessions.remove(directory) else sessions[directory] = sessionID
    }
}
