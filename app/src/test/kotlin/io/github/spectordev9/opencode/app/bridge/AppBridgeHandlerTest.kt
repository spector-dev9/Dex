package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeCommandNames
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.CommandId
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.TrashId
import io.github.spectordev9.opencode.core.workspace.Chunk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppBridgeHandlerTest {
    private fun handler(
        workspace: FakeWorkspaceService = FakeWorkspaceService(),
        client: FakeOpenCodeClient = FakeOpenCodeClient(),
    ) = AppBridgeHandler(
        runtime = FakeRuntimeService(),
        supervisor = FakeServerSupervisor(),
        workspace = workspace,
        git = FakeGitService(),
        terminal = FakePtyService(),
        client = client,
        diagnostics = FakeDiagnosticsService(),
        scrubber = FakeLogScrubber(),
    )

    @Test
    fun `runtime status combines runtime and server state`() = runBlocking {
        val result = handler().handle(
            CommandEnvelope(CommandId("status"), BridgeCommandNames.RUNTIME_STATUS),
        )

        val value = assertIs<Map<*, *>>(assertIs<ResultEnvelope.Ok<*>>(result).value)
        assertEquals("ready", value["status"])
        assertEquals("healthy", value["serverStatus"])
        assertEquals(4097, value["endpointPort"])
        assertEquals("1.19.3", value["runtimeVersion"])
    }

    @Test
    fun `runtime start returns the supervised endpoint`() = runBlocking {
        val result = handler().handle(
            CommandEnvelope(CommandId("start"), BridgeCommandNames.RUNTIME_START),
        )

        val value = assertIs<Map<*, *>>(assertIs<ResultEnvelope.Ok<*>>(result).value)
        assertEquals("ready", value["status"])
        assertEquals("127.0.0.1", value["endpointHost"])
        assertEquals(4097, value["endpointPort"])
    }

    @Test
    fun `workspace commands write read move and restore`() = runBlocking {
        val handler = handler()

        assertIs<ResultEnvelope.Ok<*>>(
            handler.handle(
                CommandEnvelope(
                    CommandId("write"),
                    BridgeCommandNames.WORKSPACE_WRITE,
                    mapOf("path" to "/notes.txt", "content" to "hello"),
                ),
            ),
        )
        val read = handler.handle(
            CommandEnvelope(
                CommandId("read"),
                BridgeCommandNames.WORKSPACE_READ_RANGE,
                mapOf("path" to "/notes.txt", "offset" to 1, "length" to 3),
            ),
        )
        val chunk = assertIs<Chunk>(assertIs<ResultEnvelope.Ok<*>>(read).value)
        assertEquals("ell", String(chunk.bytes))

        assertIs<ResultEnvelope.Ok<*>>(
            handler.handle(
                CommandEnvelope(
                    CommandId("move"),
                    BridgeCommandNames.WORKSPACE_MOVE,
                    mapOf("from" to "/notes.txt", "to" to "/moved.txt"),
                ),
            ),
        )
        val trash = handler.handle(
            CommandEnvelope(
                CommandId("trash"),
                BridgeCommandNames.WORKSPACE_TRASH,
                mapOf("path" to "/moved.txt"),
            ),
        )
        val trashId = assertIs<TrashId>(assertIs<ResultEnvelope.Ok<*>>(trash).value).value
        val restored = handler.handle(
            CommandEnvelope(
                CommandId("restore"),
                BridgeCommandNames.WORKSPACE_RESTORE,
                mapOf("id" to trashId),
            ),
        )
        assertIs<ResultEnvelope.Ok<*>>(restored)
    }

    @Test
    fun `workspace write rejects a stale precondition`() = runBlocking {
        val workspace = FakeWorkspaceService()
        val handler = handler(workspace)
        handler.handle(
            CommandEnvelope(
                CommandId("write"),
                BridgeCommandNames.WORKSPACE_WRITE,
                mapOf("path" to "/notes.txt", "content" to "original"),
            ),
        )

        val result = handler.handle(
            CommandEnvelope(
                CommandId("stale"),
                BridgeCommandNames.WORKSPACE_WRITE,
                mapOf(
                    "path" to "/notes.txt",
                    "content" to "replacement",
                    "expect" to mapOf(
                        "sizeBytes" to 8,
                        "modifiedAtEpochMillis" to 1,
                        "sha256" to "not-the-current-hash",
                    ),
                ),
            ),
        )

        assertEquals(ErrorCode.E_CONFLICT, assertIs<ResultEnvelope.Error>(result).error.code)
    }

    @Test
    fun `git commands return typed results`() = runBlocking {
        val handler = handler()
        val status = handler.handle(
            CommandEnvelope(
                CommandId("git-status"),
                BridgeCommandNames.GIT_STATUS,
                mapOf("repositoryPath" to "/repo"),
            ),
        )
        val statusValue = assertIs<io.github.spectordev9.opencode.core.git.GitStatus>(
            assertIs<ResultEnvelope.Ok<*>>(status).value,
        )
        assertEquals("main", statusValue.branch)

        val commit = handler.handle(
            CommandEnvelope(
                CommandId("git-commit"),
                BridgeCommandNames.GIT_COMMIT,
                mapOf("repositoryPath" to "/repo", "message" to "message", "paths" to listOf("README.md")),
            ),
        )
        val commitValue = assertIs<io.github.spectordev9.opencode.core.git.GitCommitResult>(
            assertIs<ResultEnvelope.Ok<*>>(commit).value,
        )
        assertEquals("message", commitValue.subject)
    }

    @Test
    fun `terminal commands share a session registry`() = runBlocking {
        val handler = handler()
        val opened = handler.handle(
            CommandEnvelope(
                CommandId("open"),
                BridgeCommandNames.TERMINAL_OPEN,
                mapOf(
                    "executable" to "/bin/sh",
                    "workingDirectory" to "/workspace",
                    "columns" to 100,
                    "rows" to 40,
                ),
            ),
        )
        val ptyId = assertIs<Map<*, *>>(assertIs<ResultEnvelope.Ok<*>>(opened).value)["ptyId"].toString()

        assertIs<ResultEnvelope.Ok<*>>(
            handler.handle(
                CommandEnvelope(
                    CommandId("write"),
                    BridgeCommandNames.TERMINAL_WRITE,
                    mapOf("ptyId" to ptyId, "data" to "echo hello\n"),
                ),
            ),
        )
        assertIs<ResultEnvelope.Ok<*>>(
            handler.handle(
                CommandEnvelope(
                    CommandId("resize"),
                    BridgeCommandNames.TERMINAL_RESIZE,
                    mapOf("ptyId" to ptyId, "columns" to 80, "rows" to 24),
                ),
            ),
        )
        assertIs<ResultEnvelope.Ok<*>>(
            handler.handle(
                CommandEnvelope(CommandId("close"), BridgeCommandNames.TERMINAL_CLOSE, mapOf("ptyId" to ptyId)),
            ),
        )

        val missing = handler.handle(
            CommandEnvelope(CommandId("write-again"), BridgeCommandNames.TERMINAL_WRITE, mapOf("ptyId" to ptyId)),
        )
        assertEquals(ErrorCode.E_CONFLICT, assertIs<ResultEnvelope.Error>(missing).error.code)
    }

    @Test
    fun `session commands are dispatched to the gateway client`() = runBlocking {
        val client = FakeOpenCodeClient()
        val handler = handler(client = client)

        val listed = handler.handle(
            CommandEnvelope(
                CommandId("sessions"),
                BridgeCommandNames.SESSION_LIST,
                mapOf("directory" to "/workspace"),
            ),
        )
        val sessions = assertIs<List<*>>(assertIs<ResultEnvelope.Ok<*>>(listed).value)
        assertEquals("ses_123", assertIs<io.github.spectordev9.opencode.core.model.Session>(sessions.single()).id)

        val created = handler.handle(
            CommandEnvelope(
                CommandId("create"),
                BridgeCommandNames.SESSION_CREATE,
                mapOf("directory" to "/workspace", "title" to "New work"),
            ),
        )
        assertEquals(
            "New work",
            assertIs<io.github.spectordev9.opencode.core.model.Session>(
                assertIs<ResultEnvelope.Ok<*>>(created).value,
            ).title,
        )

        assertIs<ResultEnvelope.Ok<*>>(
            handler.handle(
                CommandEnvelope(
                    CommandId("prompt"),
                    BridgeCommandNames.SESSION_PROMPT_ASYNC,
                    mapOf(
                        "directory" to "/workspace",
                        "sessionId" to "ses_123",
                        "prompt" to "hello",
                        "model" to mapOf("providerID" to "provider", "modelID" to "model"),
                    ),
                ),
            ),
        )
        assertEquals("hello", client.lastPrompt?.parts?.single()?.text)
        assertEquals("provider", client.lastPrompt?.model?.providerID)

        assertIs<ResultEnvelope.Ok<*>>(
            handler.handle(
                CommandEnvelope(
                    CommandId("permission"),
                    BridgeCommandNames.SESSION_PERMISSION_REPLY,
                    mapOf(
                        "directory" to "/workspace",
                        "sessionId" to "ses_123",
                        "permissionId" to "per_123",
                        "response" to "once",
                    ),
                ),
            ),
        )
        assertEquals("per_123", client.lastPermission?.first)
    }

    @Test
    fun `commands outside the G1 composition are explicit`() = runBlocking {
        val result = handler().handle(
            CommandEnvelope(CommandId("settings"), BridgeCommandNames.SETTINGS_GET, mapOf("key" to "theme")),
        )

        val error = assertIs<ResultEnvelope.Error>(result)
        assertEquals(ErrorCode.E_UNSUPPORTED_VERSION, error.error.code)
        assertTrue(error.error.message.orEmpty().contains("not implemented"))
    }
}
