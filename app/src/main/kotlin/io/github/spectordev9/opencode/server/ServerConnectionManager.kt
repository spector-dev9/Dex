package io.github.spectordev9.opencode.server

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.gateway.GatewayHealth
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface ServerTarget {
    data object Local : ServerTarget

    data class Remote(
        val profileName: String,
        val directory: String,
    ) : ServerTarget
}

enum class ServerConnectionStatus {
    DISCONNECTED,
    CONNECTED,
    RECONNECTING,
}

data class ServerConnectionSnapshot(
    val target: ServerTarget? = null,
    val endpoint: String? = null,
    val directory: String? = null,
    val version: String? = null,
    val status: ServerConnectionStatus = ServerConnectionStatus.DISCONNECTED,
    val reason: String? = null,
)

class ServerConnectionManager(
    private val scope: CoroutineScope,
    private val clientFactory: (endpoint: String, password: String) -> OpenCodeClient,
    private val onAttached: (OpenCodeClient, directory: String, target: ServerTarget) -> Unit,
    private val onDetached: (ServerTarget) -> Unit,
    private val healthIntervalMillis: Long = REMOTE_HEALTH_INTERVAL_MILLIS,
    private val failureThreshold: Int = REMOTE_FAILURE_THRESHOLD,
) {
    private data class Attachment(
        val client: OpenCodeClient,
        val endpoint: String,
        val password: String,
        var directory: String,
        val target: ServerTarget,
        var version: String?,
        var attached: Boolean = true,
    )

    private val lock = Any()
    private var attachment: Attachment? = null
    private var monitorJob: Job? = null
    private var operationVersion = 0L
    private var attachmentGeneration = 0L
    private var pendingRemoteProfile: String? = null
    private var terminalFailure = false

    private val _state = MutableStateFlow(ServerConnectionSnapshot())
    val state: StateFlow<ServerConnectionSnapshot> = _state.asStateFlow()

    val client: OpenCodeClient?
        get() = synchronized(lock) { attachment?.takeIf { it.attached }?.client }

    val activeRemoteProfile: String?
        get() = synchronized(lock) {
            (attachment?.target as? ServerTarget.Remote)?.profileName ?: pendingRemoteProfile
        }

    val isRemote: Boolean
        get() = synchronized(lock) {
            attachment?.target is ServerTarget.Remote || pendingRemoteProfile != null
        }

    fun connectLocal(
        endpoint: String,
        password: String,
        directory: String,
        version: String? = null,
    ): Boolean {
        var previous: Attachment? = null
        val attached = synchronized(lock) {
            val current = attachment
            if (current?.target is ServerTarget.Remote || pendingRemoteProfile != null) {
                return@synchronized false
            }
            if (current != null &&
                current.target is ServerTarget.Local &&
                current.endpoint == endpoint &&
                current.password == password &&
                current.directory == directory &&
                current.attached
            ) {
                if (current.version != version) {
                    current.version = version
                    _state.value = current.snapshot(ServerConnectionStatus.CONNECTED)
                }
                return@synchronized false
            }

            operationVersion++
            attachmentGeneration++
            pendingRemoteProfile = null
            terminalFailure = false
            cancelMonitorLocked()
            previous = current
            if (current?.attached == true) onDetached(current.target)

            val next = Attachment(
                client = clientFactory(endpoint, password),
                endpoint = endpoint,
                password = password,
                directory = directory,
                target = ServerTarget.Local,
                version = version,
            )
            attachment = next
            onAttached(next.client, directory, next.target)
            _state.value = next.snapshot(ServerConnectionStatus.CONNECTED)
            true
        }
        previous?.closeClientIfReplacedBy(attachment)
        return attached
    }

    suspend fun connectRemote(
        profileName: String,
        endpoint: String,
        password: String,
        directory: String,
    ): ApiResult<GatewayHealth> {
        val operation = synchronized(lock) {
            operationVersion++
            pendingRemoteProfile = profileName
            operationVersion
        }
        val candidate = try {
            clientFactory(endpoint, password)
        } catch (cancelled: CancellationException) {
            clearPending(operation)
            throw cancelled
        } catch (failure: Exception) {
            clearPending(operation)
            return ApiResult.Failure(
                ApiError(
                    ErrorCode.E_RUNTIME_DOWN,
                    failure.message ?: "OpenCode server is unreachable",
                ),
            )
        }
        val health = try {
            candidate.health()
        } catch (cancelled: CancellationException) {
            clearPending(operation)
            candidate.closeQuietly()
            throw cancelled
        } catch (failure: Exception) {
            ApiResult.Failure(
                ApiError(
                    ErrorCode.E_RUNTIME_DOWN,
                    failure.message ?: "OpenCode server is unreachable",
                ),
            )
        }
        val remoteVersion = when (health) {
            is ApiResult.Success -> health.value.version
            is ApiResult.Failure -> {
                clearPending(operation)
                candidate.closeQuietly()
                return health
            }
        }

        var previous: Attachment? = null
        var accepted = false
        synchronized(lock) {
            if (operation != operationVersion) return@synchronized
            cancelMonitorLocked()
            pendingRemoteProfile = null
            terminalFailure = false
            previous = attachment
            previous?.let { if (it.attached) onDetached(it.target) }

            val next = Attachment(
                client = candidate,
                endpoint = endpoint,
                password = password,
                directory = directory,
                target = ServerTarget.Remote(profileName, directory),
                version = remoteVersion,
            )
            attachment = next
            attachmentGeneration++
            onAttached(candidate, directory, next.target)
            _state.value = next.snapshot(ServerConnectionStatus.CONNECTED)
            monitorJob = startRemoteMonitorLocked(next, attachmentGeneration)
            accepted = true
        }
        if (!accepted) {
            candidate.closeQuietly()
            return ApiResult.Failure(ApiError(ErrorCode.E_CANCELLED, "Connection attempt was superseded"))
        }
        previous?.closeClientIfReplacedBy(attachment)
        return health
    }

    fun updateLocalDirectory(directory: String): Boolean = synchronized(lock) {
        val current = attachment ?: return@synchronized false
        if (current.target !is ServerTarget.Local) return@synchronized false
        if (pendingRemoteProfile != null) return@synchronized false
        if (current.directory == directory) return@synchronized false
        operationVersion++
        current.directory = directory
        onAttached(current.client, directory, current.target)
        _state.value = current.snapshot(ServerConnectionStatus.CONNECTED)
        true
    }

    fun disconnect(reason: String? = null): ServerTarget? {
        var previous: Attachment? = null
        val target = synchronized(lock) {
            operationVersion++
            attachmentGeneration++
            pendingRemoteProfile = null
            terminalFailure = false
            cancelMonitorLocked()
            previous = attachment
            attachment = null
            previous?.let { if (it.attached) onDetached(it.target) }
            _state.value = ServerConnectionSnapshot(
                status = ServerConnectionStatus.DISCONNECTED,
                reason = reason,
            )
            previous?.target
        }
        previous?.client?.closeQuietly()
        return target
    }

    fun close() {
        disconnect("Application stopped")
    }

    fun pauseRemoteMonitoring() {
        synchronized(lock) {
            if (attachment?.target is ServerTarget.Remote) cancelMonitorLocked()
        }
    }

    fun checkRemoteNow() {
        synchronized(lock) {
            val current = attachment ?: return@synchronized
            if (current.target !is ServerTarget.Remote || terminalFailure) return@synchronized
            cancelMonitorLocked()
            monitorJob = startRemoteMonitorLocked(
                monitored = current,
                generation = attachmentGeneration,
                initialDelayMillis = 0,
            )
        }
    }

    private fun startRemoteMonitorLocked(
        monitored: Attachment,
        generation: Long,
        initialDelayMillis: Long = healthIntervalMillis,
    ): Job =
        scope.launch {
            var failures = 0
            var nextDelayMillis = initialDelayMillis
            while (isActive) {
                if (nextDelayMillis > 0) delay(nextDelayMillis)
                nextDelayMillis = healthIntervalMillis
                val current = synchronized(lock) {
                    if (attachmentGeneration != generation || attachment !== monitored) {
                        return@launch
                    }
                    monitored
                }
                val health = try {
                    current.client.health()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    ApiResult.Failure(
                        ApiError(
                            ErrorCode.E_RUNTIME_DOWN,
                            failure.message ?: "OpenCode server is unreachable",
                        ),
                    )
                }
                if (!isActive) break

                val stop = synchronized(lock) {
                    if (attachmentGeneration != generation || attachment !== monitored) {
                        return@synchronized true
                    }
                    when (health) {
                        is ApiResult.Success -> {
                            failures = 0
                            monitored.version = health.value.version
                            if (!monitored.attached) {
                                monitored.attached = true
                                onAttached(monitored.client, monitored.directory, monitored.target)
                            }
                            _state.value = monitored.snapshot(ServerConnectionStatus.CONNECTED)
                            false
                        }
                        is ApiResult.Failure -> {
                            failures++
                            val reason = health.error.message ?: "Remote server is unreachable"
                            if (health.error.code == ErrorCode.E_AUTH) {
                                attachmentGeneration++
                                terminalFailure = true
                                if (monitored.attached) onDetached(monitored.target)
                                monitored.attached = false
                                _state.value = ServerConnectionSnapshot(
                                    target = monitored.target,
                                    endpoint = monitored.endpoint,
                                    directory = monitored.directory,
                                    version = monitored.version,
                                    status = ServerConnectionStatus.DISCONNECTED,
                                    reason = reason,
                                )
                                true
                            } else {
                                if (failures >= failureThreshold && monitored.attached) {
                                    monitored.attached = false
                                    onDetached(monitored.target)
                                }
                                _state.value = monitored.snapshot(
                                    ServerConnectionStatus.RECONNECTING,
                                    reason,
                                )
                                false
                            }
                        }
                    }
                }
                if (stop) {
                    if (health is ApiResult.Failure && health.error.code == ErrorCode.E_AUTH) {
                        current.client.closeQuietly()
                    }
                    break
                }
            }
        }

    private fun cancelMonitorLocked() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun clearPending(operation: Long) {
        synchronized(lock) {
            if (operation == operationVersion) pendingRemoteProfile = null
        }
    }

    private fun Attachment.snapshot(
        status: ServerConnectionStatus,
        reason: String? = null,
    ): ServerConnectionSnapshot = ServerConnectionSnapshot(
        target = target,
        endpoint = endpoint,
        directory = directory,
        version = version,
        status = status,
        reason = reason,
    )

    private fun Attachment?.closeClientIfReplacedBy(next: Attachment?) {
        if (this == null || next == null || this.client === next.client) return
        this.client.closeQuietly()
    }

    private fun OpenCodeClient.closeQuietly() {
        runCatching { (this as? AutoCloseable)?.close() }
    }

    companion object {
        const val REMOTE_HEALTH_INTERVAL_MILLIS = 5_000L
        const val REMOTE_FAILURE_THRESHOLD = 2
    }
}
