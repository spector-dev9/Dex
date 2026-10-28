package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val SESSION_EXPORT_FORMAT = "opencode-mobile-session"
internal const val SESSION_EXPORT_SCHEMA_VERSION = 1
internal const val MAX_SESSION_EXPORT_BYTES = 20 * 1024 * 1024

internal fun encodeSessionExport(
    session: Session,
    messages: List<Message>,
    exportedAtEpochMillis: Long,
): String {
    val exportJson = Json {
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }
    val root = buildJsonObject {
        put("format", SESSION_EXPORT_FORMAT)
        put("schemaVersion", SESSION_EXPORT_SCHEMA_VERSION)
        put("exportedAtEpochMillis", exportedAtEpochMillis)
        put("session", exportJson.encodeToJsonElement(Session.serializer(), session))
        put(
            "messages",
            exportJson.encodeToJsonElement(ListSerializer(Message.serializer()), messages),
        )
    }
    return exportJson.encodeToString(root).also { content ->
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_SESSION_EXPORT_BYTES) {
            "Session export exceeds the 20 MiB limit"
        }
    }
}
