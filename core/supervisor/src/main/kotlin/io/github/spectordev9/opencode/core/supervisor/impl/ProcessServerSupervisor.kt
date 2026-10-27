package io.github.spectordev9.opencode.core.supervisor.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.supervisor.ServerEndpoint
import io.github.spectordev9.opencode.core.supervisor.ServerHealth
import io.github.spectordev9.opencode.core.supervisor.ServerState
import io.github.spectordev9.opencode.core.supervisor.ServerStateStatus
import io.github.spectordev9.opencode.core.supervisor.ServerSupervisor
import io.github.spectordev9.opencode.core.supervisor.SupervisorConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class StartedServer(
    val endpoint: ServerEndpoint,
    val process: ManagedProcess,
)

interface ServerLauncher {
    suspend fun nextEndpoint(): ApiResult<ServerEndpoint>

    suspend fun start(endpoint: ServerEndpoint): ApiResult<StartedServer>

    suspend fun health(endpoint: ServerEndpoint): ApiResult<ServerHealth>
}

class ProcessServerSupervisor(
    private val scope: CoroutineScope,
    private val launcher: ServerLauncher,
    private val config: SupervisorConfig = SupervisorConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ServerSupervisor {
    private val stateFlow = MutableStateFlow(ServerState(status = ServerStateStatus.STOPPED))
    private val mutex = Mutex()
    private val crashTimes = ArrayDeque<Long>()

    private var process: ManagedProcess? = null
    private var endpoint: ServerEndpoint? = null
    private var monitorJob: Job? = null

    @Volatile
    private var stopping = false

    override val state: StateFlow<ServerState> = stateFlow.asStateFlow()

    override suspend fun start(): ApiResult<ServerEndpoint> = mutex.withLock {
        val current = stateFlow.value
        val active = process
        if (current.status == ServerStateStatus.HEALTHY && active?.isAlive == true) {
            return@withLock ApiResult.Success(requireNotNull(current.endpoint))
        }

        stopping = false
        crashTimes.clear()
        monitorJob?.cancel()
        monitorJob = null
        active?.let { previous ->
            if (previous.isAlive) previous.terminate(config.stopGraceMillis)
        }
        process = null
        endpoint = null

        when (val started = startLocked()) {
            is ApiResult.Failure -> started
            is ApiResult.Success -> {
                monitorJob = scope.launch { supervise(started.value.process) }
                ApiResult.Success(started.value.endpoint)
            }
        }
    }

    override suspend fun stop(graceMillis: Long): ApiResult<Unit> {
        stopping = true
        val monitor = monitorJob
        monitorJob = null
        monitor?.cancelAndJoin()

        val active = mutex.withLock {
            val current = process
            process = null
            endpoint = null
            crashTimes.clear()
            current
        }
        val terminated = active?.terminate(graceMillis) ?: ApiResult.Success(Unit)
        stateFlow.value = when (terminated) {
            is ApiResult.Success -> ServerState(status = ServerStateStatus.STOPPED)
            is ApiResult.Failure -> ServerState(
                status = ServerStateStatus.DEGRADED,
                reason = terminated.error.message ?: "server process could not be terminated",
            )
        }
        return terminated
    }

    override suspend fun restart(): ApiResult<ServerEndpoint> {
        when (val stopped = stop(config.stopGraceMillis)) {
            is ApiResult.Failure -> return stopped
            is ApiResult.Success -> Unit
        }
        return start()
    }

    private suspend fun startLocked(): ApiResult<StartedServer> {
        stateFlow.value = ServerState(status = ServerStateStatus.STARTING)
        val endpoint = when (val result = launcher.nextEndpoint()) {
            is ApiResult.Failure -> {
                stateFlow.value = ServerState(
                    status = ServerStateStatus.STOPPED,
                    reason = result.error.message ?: "server endpoint could not be allocated",
                )
                return result
            }
            is ApiResult.Success -> result.value
        }

        val started = when (val result = launcher.start(endpoint)) {
            is ApiResult.Failure -> {
                stateFlow.value = ServerState(
                    status = ServerStateStatus.STOPPED,
                    endpoint = endpoint,
                    reason = result.error.message ?: "server process could not be started",
                )
                return result
            }
            is ApiResult.Success -> result.value
        }
        process = started.process
        this.endpoint = started.endpoint

        return when (val health = awaitHealth(started.endpoint)) {
            is ApiResult.Failure -> {
                started.process.terminate(config.stopGraceMillis)
                process = null
                stateFlow.value = ServerState(
                    status = ServerStateStatus.DEGRADED,
                    endpoint = started.endpoint,
                    reason = health.error.message ?: "server did not become healthy",
                )
                health
            }
            is ApiResult.Success -> {
                stateFlow.value = ServerState(
                    status = ServerStateStatus.HEALTHY,
                    endpoint = started.endpoint,
                    version = health.value.version,
                )
                ApiResult.Success(started)
            }
        }
    }

    private suspend fun awaitHealth(endpoint: ServerEndpoint): ApiResult<ServerHealth> {
        val deadline = clock() + config.startupTimeoutMillis
        var lastError: ApiError? = null
        while (currentCoroutineContext().isActive) {
            val result = withTimeoutOrNull(config.healthTimeoutMillis) { launcher.health(endpoint) }
                ?: ApiResult.Failure(ApiError(ErrorCode.E_TIMEOUT, "server health check timed out"))
            when (result) {
                is ApiResult.Success -> return result
                is ApiResult.Failure -> lastError = result.error
            }
            val remaining = deadline - clock()
            if (remaining <= 0) {
                return ApiResult.Failure(
                    ApiError(
                        code = ErrorCode.E_TIMEOUT,
                        message = lastError?.message ?: "server health check timed out",
                        upstreamStatus = lastError?.upstreamStatus,
                    ),
                )
            }
            sleep(minOf(HEALTH_POLL_MILLIS, remaining))
        }
        throw CancellationException("supervisor start cancelled")
    }

    private suspend fun supervise(initial: ManagedProcess) {
        var current = initial
        while (currentCoroutineContext().isActive && !stopping) {
            val exit = current.waitFor()
            if (stopping) return

            val backoff = recordCrash(clock())
            if (backoff == null) {
                stateFlow.value = ServerState(
                    status = ServerStateStatus.CRASH_LOOP,
                    endpoint = endpoint,
                    reason = "server crashed ${config.crashLoopThreshold} times within " +
                        "${config.crashLoopWindowMillis} ms",
                )
                return
            }

            stateFlow.value = ServerState(
                status = ServerStateStatus.BACKOFF,
                endpoint = endpoint,
                backoffUntilEpochMillis = clock() + backoff,
                reason = "server exited (exit=${exit.exitCode}, signal=${exit.signal})",
            )
            sleep(backoff)
            if (stopping) return

            val restarted = mutex.withLock {
                if (stopping) null else startLocked()
            } ?: return
            when (restarted) {
                is ApiResult.Success -> current = restarted.value.process
                is ApiResult.Failure -> {
                    stateFlow.value = ServerState(
                        status = ServerStateStatus.DEGRADED,
                        endpoint = endpoint,
                        reason = restarted.error.message ?: "server restart failed",
                    )
                    return
                }
            }
        }
    }

    private fun recordCrash(now: Long): Long? {
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > config.crashLoopWindowMillis) {
            crashTimes.removeFirst()
        }
        crashTimes.addLast(now)
        if (crashTimes.size >= config.crashLoopThreshold) return null

        var backoff = config.initialBackoffMillis
        repeat((crashTimes.size - 1).coerceAtMost(MAX_BACKOFF_DOUBLINGS)) {
            backoff = minOf(backoff * 2, config.maxBackoffMillis)
        }
        return backoff
    }

    private companion object {
        const val HEALTH_POLL_MILLIS = 250L
        const val MAX_BACKOFF_DOUBLINGS = 16
    }
}
