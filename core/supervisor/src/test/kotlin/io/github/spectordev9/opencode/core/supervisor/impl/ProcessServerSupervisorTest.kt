package io.github.spectordev9.opencode.core.supervisor.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.ProcessExit
import io.github.spectordev9.opencode.core.common.ProcessId
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.supervisor.ServerEndpoint
import io.github.spectordev9.opencode.core.supervisor.ServerHealth
import io.github.spectordev9.opencode.core.supervisor.ServerStateStatus
import io.github.spectordev9.opencode.core.supervisor.SupervisorConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class ProcessServerSupervisorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val endpoint = ServerEndpoint("127.0.0.1", 4096)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `start publishes healthy state and stop terminates the process`() = runBlocking {
        val launcher = FakeLauncher(endpoint)
        val supervisor = newSupervisor(launcher)

        val started = supervisor.start()

        assertTrue(started.isSuccess)
        assertEquals(endpoint, (started as ApiResult.Success).value)
        assertEquals(ServerStateStatus.HEALTHY, supervisor.state.value.status)
        val process = launcher.startedProcesses.single()
        assertTrue(process.isAlive)

        assertTrue(supervisor.stop(100).isSuccess)
        assertEquals(ServerStateStatus.STOPPED, supervisor.state.value.status)
        assertFalse(process.isAlive)
    }

    @Test
    fun `process crash is restarted after backoff`() = runBlocking {
        val launcher = FakeLauncher(endpoint)
        val clock = AtomicLong(0)
        val supervisor = newSupervisor(launcher, clock = clock)
        supervisor.start()

        launcher.startedProcesses.single().crash()

        withTimeout(5_000) {
            while (launcher.startedProcesses.size < 2 || supervisor.state.value.status != ServerStateStatus.HEALTHY) {
                yield()
            }
        }
        assertEquals(2, launcher.startedProcesses.size)
        assertTrue(launcher.startedProcesses.last().isAlive)
    }

    @Test
    fun `repeated crashes trip the crash-loop breaker`() = runBlocking {
        val launcher = FakeLauncher(endpoint)
        val clock = AtomicLong(0)
        val supervisor = newSupervisor(
            launcher,
            clock = clock,
            config = SupervisorConfig(crashLoopThreshold = 3, crashLoopWindowMillis = 10_000),
        )
        supervisor.start()

        repeat(3) { index ->
            withTimeout(5_000) {
                while (launcher.startedProcesses.size < index + 1) yield()
            }
            launcher.startedProcesses[index].crash()
            if (index < 2) {
                withTimeout(5_000) {
                    while (launcher.startedProcesses.size < index + 2) yield()
                }
            }
        }

        withTimeout(5_000) {
            while (supervisor.state.value.status != ServerStateStatus.CRASH_LOOP) yield()
        }
        assertEquals(3, launcher.startedProcesses.size)
        assertFalse(launcher.startedProcesses.last().isAlive)
    }

    @Test
    fun `health timeout fails start and terminates the process`() = runBlocking {
        val launcher = FakeLauncher(endpoint).apply { failHealth = true }
        val clock = AtomicLong(0)
        val supervisor = newSupervisor(
            launcher,
            clock = clock,
            config = SupervisorConfig(startupTimeoutMillis = 100),
        )

        val started = supervisor.start()

        assertTrue(started is ApiResult.Failure)
        assertEquals(ErrorCode.E_TIMEOUT, (started as ApiResult.Failure).error.code)
        assertEquals(ServerStateStatus.DEGRADED, supervisor.state.value.status)
        assertFalse(launcher.startedProcesses.single().isAlive)
    }

    private fun newSupervisor(
        launcher: ServerLauncher,
        clock: AtomicLong = AtomicLong(0),
        config: SupervisorConfig = SupervisorConfig(),
    ): ProcessServerSupervisor = ProcessServerSupervisor(
        scope = scope,
        launcher = launcher,
        config = config,
        clock = clock::get,
        sleep = { millis ->
            clock.addAndGet(millis)
            yield()
        },
    )

    private class FakeLauncher(
        private val endpoint: ServerEndpoint,
    ) : ServerLauncher {
        val startedProcesses = mutableListOf<FakeManagedProcess>()
        var failHealth = false

        override suspend fun nextEndpoint(): ApiResult<ServerEndpoint> = ApiResult.Success(endpoint)

        override suspend fun start(endpoint: ServerEndpoint): ApiResult<StartedServer> {
            val process = FakeManagedProcess(PROCESS_COUNT.incrementAndGet())
            startedProcesses += process
            return ApiResult.Success(StartedServer(endpoint, process))
        }

        override suspend fun health(endpoint: ServerEndpoint): ApiResult<ServerHealth> =
            if (failHealth) {
                ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "not ready"))
            } else {
                ApiResult.Success(ServerHealth(version = "1.0.0", endpoint = endpoint))
            }

        private companion object {
            val PROCESS_COUNT = AtomicInteger()
        }
    }

    private class FakeManagedProcess(
        sequence: Int,
    ) : ManagedProcess {
        private val exit = CompletableDeferred<ProcessExit>()

        override val id: ProcessId = ProcessId("process-$sequence")
        override val pid: Long = 10_000L + sequence

        @Volatile
        override var isAlive: Boolean = true
            private set

        override fun signal(signal: Int): ApiResult<Unit> = ApiResult.Success(Unit)

        override suspend fun waitFor(): ProcessExit = exit.await().also { isAlive = false }

        override suspend fun terminate(graceMillis: Long): ApiResult<Unit> {
            if (isAlive) {
                isAlive = false
                exit.complete(ProcessExit(exitCode = 0))
            }
            return ApiResult.Success(Unit)
        }

        fun crash(exitCode: Int = 1) {
            isAlive = false
            exit.complete(ProcessExit(exitCode = exitCode))
        }
    }
}
