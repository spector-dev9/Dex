package io.github.spectordev9.opencode.core.bridge

import io.github.spectordev9.opencode.core.common.CommandId
import io.github.spectordev9.opencode.core.common.ErrorCode

object BridgeProtocol {
    const val VERSION: Int = 1
    const val TYPE_COMMAND: String = "cmd"
    const val TYPE_RESULT: String = "res"
    const val TYPE_EVENT: String = "evt"
}

sealed interface BridgeMessage {
    val v: Int
}

data class CommandEnvelope(
    val id: CommandId,
    val name: String,
    val args: Map<String, Any?> = emptyMap(),
    override val v: Int = BridgeProtocol.VERSION,
    val type: String = BridgeProtocol.TYPE_COMMAND,
) : BridgeMessage {
    init {
        require(name.isNotBlank()) { "command name must not be blank" }
        require(v == BridgeProtocol.VERSION) { "unsupported bridge version" }
        require(type == BridgeProtocol.TYPE_COMMAND) { "invalid command envelope type" }
    }
}

data class BridgeError(
    val code: ErrorCode,
    val message: String? = null,
    val upstreamStatus: Int? = null,
) {
    val retryable: Boolean
        get() = code.retryable
}

sealed interface ResultEnvelope : BridgeMessage {
    val id: CommandId
    val type: String
    val ok: Boolean

    data class Ok<T>(
        override val id: CommandId,
        val value: T,
        override val v: Int = BridgeProtocol.VERSION,
        override val type: String = BridgeProtocol.TYPE_RESULT,
        override val ok: Boolean = true,
    ) : ResultEnvelope {
        init {
            require(v == BridgeProtocol.VERSION) { "unsupported bridge version" }
            require(type == BridgeProtocol.TYPE_RESULT) { "invalid result envelope type" }
            require(ok) { "ok result must set ok=true" }
        }
    }

    data class Error(
        override val id: CommandId,
        val error: BridgeError,
        override val v: Int = BridgeProtocol.VERSION,
        override val type: String = BridgeProtocol.TYPE_RESULT,
        override val ok: Boolean = false,
    ) : ResultEnvelope {
        init {
            require(v == BridgeProtocol.VERSION) { "unsupported bridge version" }
            require(type == BridgeProtocol.TYPE_RESULT) { "invalid result envelope type" }
            require(!ok) { "error result must set ok=false" }
        }
    }
}

enum class EventTopic(val wireName: String) {
    RUNTIME_STATE("runtime.state"),
    SERVER_STATE("server.state"),
    SESSION_EVENT("session.event"),
    PERMISSION_REQUESTED("permission.requested"),
    TERMINAL_DATA("terminal.data"),
    WORKSPACE_CHANGED("workspace.changed"),
    DIAGNOSTICS_ALERT("diagnostics.alert"),
}

data class EventEnvelope<T>(
    val topic: EventTopic,
    val seq: Long,
    val ts: Long,
    val data: T,
    override val v: Int = BridgeProtocol.VERSION,
    val type: String = BridgeProtocol.TYPE_EVENT,
) : BridgeMessage {
    init {
        require(v == BridgeProtocol.VERSION) { "unsupported bridge version" }
        require(type == BridgeProtocol.TYPE_EVENT) { "invalid event envelope type" }
        require(seq >= 0) { "seq must not be negative" }
        require(ts >= 0) { "ts must not be negative" }
    }
}
