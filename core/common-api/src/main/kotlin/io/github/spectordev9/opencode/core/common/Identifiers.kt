package io.github.spectordev9.opencode.core.common

@JvmInline
value class RuntimeId(val value: String) {
    init {
        require(value.isNotBlank()) { "RuntimeId must not be blank" }
    }
}

@JvmInline
value class CommandId(val value: String) {
    init {
        require(value.isNotBlank()) { "CommandId must not be blank" }
    }
}

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "SessionId must not be blank" }
    }
}

@JvmInline
value class ProcessId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProcessId must not be blank" }
    }
}

@JvmInline
value class PtyId(val value: String) {
    init {
        require(value.isNotBlank()) { "PtyId must not be blank" }
    }
}

@JvmInline
value class TrashId(val value: String) {
    init {
        require(value.isNotBlank()) { "TrashId must not be blank" }
    }
}

@JvmInline
value class SecretRef(val value: String) {
    init {
        require(value.isNotBlank()) { "SecretRef must not be blank" }
    }
}
