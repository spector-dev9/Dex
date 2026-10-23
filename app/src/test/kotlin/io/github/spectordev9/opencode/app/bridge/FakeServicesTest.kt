package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeCommandNames
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.CommandId
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.SecretRef
import io.github.spectordev9.opencode.core.common.TimeoutClass
import io.github.spectordev9.opencode.core.gateway.GatewayOperation
import io.github.spectordev9.opencode.core.gateway.GatewayRequest
import io.github.spectordev9.opencode.core.gateway.HttpMethod
import io.github.spectordev9.opencode.core.security.LoopbackCredential
import io.github.spectordev9.opencode.core.workspace.Chunk
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.WriteReceipt
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FakeServicesTest {
    @Test
    fun `workspace reads ranges including offsets beyond end of file`() = runBlocking {
        val workspace = FakeWorkspaceService()
        assertIs<ApiResult.Success<WriteReceipt>>(workspace.write("/notes.txt", "abcdef".toByteArray(), Precondition.None))

        val partial = assertIs<ApiResult.Success<Chunk>>(workspace.readRange("/notes.txt", 2, 3)).value
        assertEquals("cde", String(partial.bytes))
        assertFalse(partial.endOfFile)

        val pastEnd = assertIs<ApiResult.Success<Chunk>>(workspace.readRange("/notes.txt", 20, 3)).value
        assertEquals(20L, pastEnd.offset)
        assertEquals(0, pastEnd.bytes.size)
        assertTrue(pastEnd.endOfFile)
    }

    @Test
    fun `workspace enforces content hash preconditions`() = runBlocking {
        val workspace = FakeWorkspaceService()
        workspace.write("/notes.txt", "original".toByteArray(), Precondition.None)

        val result = workspace.write(
            "/notes.txt",
            "replacement".toByteArray(),
            Precondition.Match(sizeBytes = 8, sha256 = "not-the-current-hash"),
        )

        val failure = assertIs<ApiResult.Failure>(result)
        assertEquals(ErrorCode.E_CONFLICT, failure.error.code)
        assertEquals("original", String(assertIs<ApiResult.Success<Chunk>>(workspace.readRange("/notes.txt", 0, 32)).value.bytes))
    }

    @Test
    fun `fake loopback auth emits and verifies a basic authorization header`() = runBlocking {
        val auth = FakeLoopbackAuth()
        val credential = assertIs<ApiResult.Success<LoopbackCredential>>(auth.provision(SecretRef("test"))).value
        val header = auth.authorizationHeader(credential)

        assertTrue(header.startsWith("Basic "))
        assertTrue(auth.verify(header, credential))
        assertFalse(auth.verify("Basic b3BlbmNvZGU6d3Jvbmc=", credential))
        assertFalse(auth.verify("not-basic", credential))
    }

    @Test
    fun `fake gateway delegates typed response decoding`() = runBlocking {
        val gateway = FakeGateway()
        val operation = GatewayOperation(
            name = BridgeCommandNames.RUNTIME_STATUS,
            request = GatewayRequest(method = HttpMethod.GET, path = "/global/health"),
            timeoutClass = TimeoutClass.HEALTH,
            decoder = { status, body ->
                check(status == 200)
                String(body)
            },
        )

        val result = assertIs<ApiResult.Success<String>>(gateway.call(operation))
        assertEquals("ready", result.value)
    }

    @Test
    fun `fake supervisor reflects stop and restart transitions`() = runBlocking {
        val supervisor = FakeServerSupervisor()

        assertEquals(
            io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.HEALTHY,
            supervisor.state.value.status,
        )
        assertIs<ApiResult.Success<*>>(supervisor.stop())
        assertEquals(
            io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.STOPPED,
            supervisor.state.value.status,
        )
        assertIs<ApiResult.Success<*>>(supervisor.restart())
        assertEquals(
            io.github.spectordev9.opencode.core.supervisor.ServerStateStatus.HEALTHY,
            supervisor.state.value.status,
        )
    }

    @Test
    fun `bridge handler serves runtime status and workspace commands`() = runBlocking {
        val handler = AppBridgeCommandHandler()
        val status = handler.handle(
            CommandEnvelope(
                id = CommandId("status-1"),
                name = BridgeCommandNames.RUNTIME_STATUS,
            ),
        )
        val statusValue = assertIs<Map<*, *>>(assertIs<ResultEnvelope.Ok<*>>(status).value)
        assertEquals("ready", statusValue["status"])

        val write = handler.handle(
            CommandEnvelope(
                id = CommandId("write-1"),
                name = BridgeCommandNames.WORKSPACE_WRITE,
                args = mapOf("path" to "/notes.txt", "content" to "hello"),
            ),
        )
        assertIs<ResultEnvelope.Ok<*>>(write)

        val read = handler.handle(
            CommandEnvelope(
                id = CommandId("read-1"),
                name = BridgeCommandNames.WORKSPACE_READ_RANGE,
                args = mapOf("path" to "/notes.txt", "offset" to 1, "length" to 3),
            ),
        )
        val chunk = assertIs<Chunk>(assertIs<ResultEnvelope.Ok<*>>(read).value)
        assertEquals("ell", String(chunk.bytes))
    }

    @Test
    fun `bridge handler preserves service errors`() = runBlocking {
        val result = AppBridgeCommandHandler().handle(
            CommandEnvelope(
                id = CommandId("missing-file"),
                name = BridgeCommandNames.WORKSPACE_READ_RANGE,
                args = mapOf("path" to "/missing", "offset" to 0, "length" to 10),
            ),
        )

        val error = assertIs<ResultEnvelope.Error>(result)
        assertEquals(ErrorCode.E_PATH_DENIED, error.error.code)
    }
}
