package io.github.spectordev9.opencode.server

import io.github.spectordev9.opencode.app.bridge.FakeOpenCodeClient
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerConnectionManagerTest {
    @Test
    fun `local connection is idempotent and reattaches on directory change`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeOpenCodeClient()
        val attached = mutableListOf<Pair<String, ServerTarget>>()
        val detached = mutableListOf<ServerTarget>()
        val manager = ServerConnectionManager(
            scope = scope,
            clientFactory = { _, _ -> client },
            onAttached = { _, directory, target -> attached += directory to target },
            onDetached = { detached += it },
            healthIntervalMillis = 10,
        )
        try {
            assertTrue(manager.connectLocal("http://127.0.0.1:4097", "secret", "/workspace", "1.19.3"))
            assertFalse(manager.connectLocal("http://127.0.0.1:4097", "secret", "/workspace", "1.19.3"))
            assertEquals(1, attached.size)

            assertTrue(manager.updateLocalDirectory("/workspace/project"))
            assertEquals("/workspace/project", attached.last().first)

            manager.disconnect("test")
            assertNull(manager.client)
            assertEquals(ServerTarget.Local, detached.single())
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun `failed remote connection keeps the local attachment`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val local = FakeOpenCodeClient()
        val remote = FakeOpenCodeClient().apply {
            healthResult = ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "offline"))
        }
        var factoryClient: OpenCodeClient = local
        val manager = ServerConnectionManager(
            scope = scope,
            clientFactory = { _, _ -> factoryClient },
            onAttached = { _, _, _ -> },
            onDetached = { },
            healthIntervalMillis = 10,
        )
        try {
            assertTrue(manager.connectLocal("http://127.0.0.1:4097", "secret", "/workspace"))
            factoryClient = remote

            val result = manager.connectRemote("remote", "https://remote.test", "token", "/remote")

            assertTrue(result is ApiResult.Failure)
            assertSame(local, manager.client)
            assertNull(manager.activeRemoteProfile)
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun `authentication failure stops the remote monitor until disconnect`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val remote = FakeOpenCodeClient()
        val manager = ServerConnectionManager(
            scope = scope,
            clientFactory = { _, _ -> remote },
            onAttached = { _, _, _ -> },
            onDetached = { },
            healthIntervalMillis = 10,
            failureThreshold = 2,
        )
        try {
            assertTrue(
                manager.connectRemote("remote", "https://remote.test", "token", "/remote") is ApiResult.Success,
            )
            remote.healthResult = ApiResult.Failure(ApiError(ErrorCode.E_AUTH, "unauthorized"))
            delay(50)

            assertNull(manager.client)
            assertTrue(manager.isRemote)
            assertEquals(ServerConnectionStatus.DISCONNECTED, manager.state.value.status)

            remote.healthResult = ApiResult.Success(
                io.github.spectordev9.opencode.core.gateway.GatewayHealth("1.19.3", 4097),
            )
            manager.checkRemoteNow()
            delay(30)

            assertNull(manager.client)
            assertTrue(manager.isRemote)
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun `remote connection detaches after failures and reattaches on recovery`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        val remote = FakeOpenCodeClient()
        val attached = mutableListOf<String>()
        val detached = mutableListOf<ServerTarget>()
        val manager = ServerConnectionManager(
            scope = scope,
            clientFactory = { _, _ -> remote },
            onAttached = { _, directory, _ -> attached += directory },
            onDetached = { detached += it },
            healthIntervalMillis = 10,
            failureThreshold = 2,
        )
        try {
            val result = manager.connectRemote("remote", "https://remote.test", "token", "/remote")
            assertTrue(result is ApiResult.Success)
            assertSame(remote, manager.client)
            assertEquals("remote", manager.activeRemoteProfile)

            remote.healthResult = ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "offline"))
            delay(80)
            assertNull(manager.client)
            assertEquals(ServerConnectionStatus.RECONNECTING, manager.state.value.status)

            remote.healthResult = ApiResult.Success(
                io.github.spectordev9.opencode.core.gateway.GatewayHealth("1.19.3", 4097),
            )
            delay(60)

            assertSame(remote, manager.client)
            assertEquals(2, attached.size)
            assertEquals(ServerConnectionStatus.CONNECTED, manager.state.value.status)
            assertEquals(ServerTarget.Remote("remote", "/remote"), detached.single())
        } finally {
            manager.close()
            scope.cancel()
        }
    }
}
