package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val SESSION_EXPORT_FORMAT = "opencode-mobile-session"
internal const val SESSION_EXPORT_SCHEMA_VERSION = 1
internal const val MAX_SESSION_EXPORT_BYTES = 20 * 1024 * 1024
internal const val MAX_IMPORTED_MESSAGES = 10_000

/** A validated import candidate; never restored to the server implicitly. */
internal data class ImportedSession(
    val session: Session,
    val messages: List<Message>,
    val exportedAtEpochMillis: Long,
    val schemaVersion: Int,
    val sourceSummary: String,
)

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

/**
 * Parses and validates an exported session (F45). The result is a local
 * review artifact only: callers must not push arbitrary session JSON to the
 * server because server-side identifiers and parts are not guaranteed to be
 * restorable.
 */
internal fun decodeSessionExport(raw: String): Result<ImportedSession> = runCatching {
    val bytes = raw.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_SESSION_EXPORT_BYTES) { "Session import exceeds the 20 MiB limit" }
    val importJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    val root = importJson.parseToJsonElement(raw).jsonObject
    val format = root["format"]?.jsonPrimitive?.content
    require(format == SESSION_EXPORT_FORMAT) { "Unsupported session export format" }
    val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("Missing session export schema version")
    require(schemaVersion == SESSION_EXPORT_SCHEMA_VERSION) {
        "Unsupported session export schema version $schemaVersion"
    }
    val exportedAt = root["exportedAtEpochMillis"]?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("Missing session export timestamp")
    require(exportedAt > 0L) { "Session export timestamp must be positive" }
    val sessionObject = root["session"]?.jsonObject
        ?: throw IllegalArgumentException("Missing session payload")
    val session = importJson.decodeFromJsonElement(Session.serializer(), sessionObject)
    require(session.id.isNotBlank() && session.id.length <= 256) { "Invalid session identifier" }
    val messageElements = root["messages"]?.jsonArray
        ?: throw IllegalArgumentException("Missing session messages")
    require(messageElements.size <= MAX_IMPORTED_MESSAGES) {
        "Session import has more than $MAX_IMPORTED_MESSAGES messages"
    }
    val messages = messageElements.map { element ->
        importJson.decodeFromJsonElement(Message.serializer(), element)
    }
    require(messages.all { it.info.sessionID == session.id }) {
        "Session import contains messages from another session"
    }
    require(messages.all { it.info.id.isNotBlank() }) { "Session import contains blank message identifiers" }
    require(messages.flatMap { it.parts }.all { it.id.isNotBlank() }) {
        "Session import contains blank part identifiers"
    }
    ImportedSession(
        session = session,
        messages = messages,
        exportedAtEpochMillis = exportedAt,
        schemaVersion = schemaVersion,
        sourceSummary = "${session.title.ifBlank { session.slug }} " +
            "(${messages.size} message${if (messages.size == 1) "" else "s"})",
    )
}
