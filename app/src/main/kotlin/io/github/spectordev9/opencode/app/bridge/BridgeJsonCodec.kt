package io.github.spectordev9.opencode.app.bridge

import io.github.spectordev9.opencode.core.bridge.BridgeError
import io.github.spectordev9.opencode.core.bridge.BridgeMessage
import io.github.spectordev9.opencode.core.bridge.BridgeProtocol
import io.github.spectordev9.opencode.core.bridge.CommandEnvelope
import io.github.spectordev9.opencode.core.bridge.EventEnvelope
import io.github.spectordev9.opencode.core.bridge.EventTopic
import io.github.spectordev9.opencode.core.bridge.ResultEnvelope
import io.github.spectordev9.opencode.core.common.CommandId
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.TrashId
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticEvent
import io.github.spectordev9.opencode.core.diagnostics.DiagnosticReport
import io.github.spectordev9.opencode.core.git.GitBranch
import io.github.spectordev9.opencode.core.git.GitChange
import io.github.spectordev9.opencode.core.git.GitCommitResult
import io.github.spectordev9.opencode.core.git.GitDiff
import io.github.spectordev9.opencode.core.git.GitStatus
import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.Message
import io.github.spectordev9.opencode.core.model.Session
import io.github.spectordev9.opencode.core.opencode.client.SessionFileDiff
import io.github.spectordev9.opencode.core.workspace.Chunk
import io.github.spectordev9.opencode.core.workspace.Entry
import io.github.spectordev9.opencode.core.workspace.WriteReceipt
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class BridgeJsonCodec(
    private val json: Json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    },
) {
    fun encodeCommand(command: CommandEnvelope): String =
        json.encodeToString(JsonElement.serializer(), commandToJson(command))

    fun decodeCommand(raw: String): CommandEnvelope {
        val root = parseObject(raw)
        requireVersionAndType(root, BridgeProtocol.TYPE_COMMAND)
        val args = (decodeValue(root["args"] ?: JsonObject(emptyMap())) as? Map<*, *>)
            ?.entries
            ?.associate { it.key.toString() to it.value }
            ?: emptyMap()
        return CommandEnvelope(
            id = CommandId(root.requiredString("id")),
            name = root.requiredString("name"),
            args = args,
        )
    }

    fun encodeResult(result: ResultEnvelope): String =
        json.encodeToString(JsonElement.serializer(), resultToJson(result))

    fun decodeResult(raw: String): ResultEnvelope {
        val root = parseObject(raw)
        requireVersionAndType(root, BridgeProtocol.TYPE_RESULT)
        val id = CommandId(root.requiredString("id"))
        val ok = root["ok"]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("result requires boolean ok")
        return if (ok) {
            ResultEnvelope.Ok(id = id, value = decodeValue(root["value"]))
        } else {
            val error = root["error"]?.jsonObject
                ?: throw IllegalArgumentException("error result requires error")
            val code = ErrorCode.valueOf(error.requiredString("code"))
            val retryable = error["retryable"]?.jsonPrimitive?.booleanOrNull
                ?: throw IllegalArgumentException("bridge error requires retryable")
            require(retryable == code.retryable) { "bridge retryable does not match ${code.name}" }
            ResultEnvelope.Error(
                id = id,
                error = BridgeError(
                    code = code,
                    message = error["message"]?.jsonPrimitive?.contentOrNull,
                    upstreamStatus = error["upstreamStatus"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                ),
            )
        }
    }

    fun encodeEvent(event: EventEnvelope<*>): String =
        json.encodeToString(JsonElement.serializer(), eventToJson(event))

    fun decodeEvent(raw: String): EventEnvelope<Any?> {
        val root = parseObject(raw)
        requireVersionAndType(root, BridgeProtocol.TYPE_EVENT)
        val topicName = root.requiredString("topic")
        val topic = EventTopic.entries.firstOrNull { it.wireName == topicName }
            ?: throw IllegalArgumentException("unknown event topic $topicName")
        return EventEnvelope(
            topic = topic,
            seq = root.requiredLong("seq"),
            ts = root.requiredLong("ts"),
            data = decodeValue(root["data"]),
        )
    }

    fun encodeMessage(message: BridgeMessage): String = when (message) {
        is CommandEnvelope -> encodeCommand(message)
        is ResultEnvelope -> encodeResult(message)
        is EventEnvelope<*> -> encodeEvent(message)
    }

    fun decodeMessage(raw: String): BridgeMessage {
        return when (parseObject(raw).requiredString("type")) {
            BridgeProtocol.TYPE_COMMAND -> decodeCommand(raw)
            BridgeProtocol.TYPE_RESULT -> decodeResult(raw)
            BridgeProtocol.TYPE_EVENT -> decodeEvent(raw)
            else -> throw IllegalArgumentException("unsupported bridge message type")
        }
    }

    fun valueToJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Char -> JsonPrimitive(value.toString())
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name.lowercase(Locale.ROOT))
        is Unit -> JsonObject(emptyMap())
        is TrashId -> JsonPrimitive(value.value)
        is ByteArray -> JsonPrimitive(Base64.getEncoder().encodeToString(value))
        is Session -> json.encodeToJsonElement(Session.serializer(), value)
        is Message -> json.encodeToJsonElement(Message.serializer(), value)
        is FileEntry -> json.encodeToJsonElement(FileEntry.serializer(), value)
        is SessionFileDiff -> json.encodeToJsonElement(SessionFileDiff.serializer(), value)
        is Entry -> entryToJson(value)
        is Chunk -> chunkToJson(value)
        is WriteReceipt -> writeReceiptToJson(value)
        is GitStatus -> gitStatusToJson(value)
        is GitDiff -> gitDiffToJson(value)
        is GitCommitResult -> gitCommitToJson(value)
        is GitBranch -> gitBranchToJson(value)
        is DiagnosticReport -> diagnosticReportToJson(value)
        is DiagnosticEvent -> diagnosticEventToJson(value)
        is Map<*, *> -> buildJsonObject {
            value.forEach { (key, item) -> put(key.toString(), valueToJson(item)) }
        }
        is Iterable<*> -> buildJsonArray { value.forEach { add(valueToJson(it)) } }
        is Array<*> -> buildJsonArray { value.forEach { add(valueToJson(it)) } }
        is BooleanArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        is IntArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        is LongArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        else -> throw IllegalArgumentException("unsupported bridge value ${value::class.qualifiedName}")
    }

    fun decodeValue(value: JsonElement?): Any? = when (value) {
        null, JsonNull -> null
        is JsonObject -> value.entries.associate { it.key to decodeValue(it.value) }
        is JsonArray -> value.map(::decodeValue)
        is JsonPrimitive -> when {
            value.isString -> value.content
            else -> value.booleanOrNull
                ?: value.longOrNull
                ?: value.doubleOrNull
                ?: value.content
        }
    }

    private fun commandToJson(command: CommandEnvelope): JsonObject = buildJsonObject {
        put("v", command.v)
        put("id", command.id.value)
        put("type", command.type)
        put("name", command.name)
        put("args", valueToJson(command.args))
    }

    private fun resultToJson(result: ResultEnvelope): JsonObject = buildJsonObject {
        put("v", result.v)
        put("id", result.id.value)
        put("type", result.type)
        put("ok", result.ok)
        when (result) {
            is ResultEnvelope.Ok<*> -> put("value", valueToJson(result.value))
            is ResultEnvelope.Error -> put(
                "error",
                buildJsonObject {
                    put("code", result.error.code.name)
                    put("retryable", result.error.retryable)
                    result.error.message?.let { put("message", it) }
                    result.error.upstreamStatus?.let { put("upstreamStatus", it) }
                },
            )
        }
    }

    private fun eventToJson(event: EventEnvelope<*>): JsonObject = buildJsonObject {
        put("v", event.v)
        put("type", event.type)
        put("topic", event.topic.wireName)
        put("seq", event.seq)
        put("ts", event.ts)
        put("data", valueToJson(event.data))
    }

    private fun entryToJson(entry: Entry): JsonObject = buildJsonObject {
        put("path", entry.path)
        put("name", entry.name)
        put("kind", entry.kind.name.lowercase(Locale.ROOT))
        put("sizeBytes", entry.sizeBytes)
        put("modifiedAtEpochMillis", entry.modifiedAtEpochMillis)
        entry.symlinkTarget?.let { put("symlinkTarget", it) }
    }

    private fun chunkToJson(chunk: Chunk): JsonObject = buildJsonObject {
        put("offset", chunk.offset)
        put("sizeBytes", chunk.bytes.size)
        put("dataBase64", Base64.getEncoder().encodeToString(chunk.bytes))
        put("endOfFile", chunk.endOfFile)
    }

    private fun writeReceiptToJson(receipt: WriteReceipt): JsonObject = buildJsonObject {
        put("path", receipt.path)
        put("sizeBytes", receipt.sizeBytes)
        put("modifiedAtEpochMillis", receipt.modifiedAtEpochMillis)
        put("sha256", receipt.sha256)
    }

    private fun gitStatusToJson(status: GitStatus): JsonObject = buildJsonObject {
        status.branch?.let { put("branch", it) }
        status.commit?.let { put("commit", it) }
        put("ahead", status.ahead)
        put("behind", status.behind)
        put("changes", valueToJson(status.changes))
    }

    private fun gitDiffToJson(diff: GitDiff): JsonObject = buildJsonObject {
        diff.path?.let { put("path", it) }
        put("patch", diff.patch)
        put("binary", diff.binary)
        put("truncated", diff.truncated)
    }

    private fun gitCommitToJson(commit: GitCommitResult): JsonObject = buildJsonObject {
        put("hash", commit.hash)
        put("subject", commit.subject)
    }

    private fun gitBranchToJson(branch: GitBranch): JsonObject = buildJsonObject {
        put("name", branch.name)
        put("current", branch.current)
        branch.tracking?.let { put("tracking", it) }
        put("ahead", branch.ahead)
        put("behind", branch.behind)
    }

    private fun diagnosticEventToJson(event: DiagnosticEvent): JsonObject = buildJsonObject {
        put("epochMillis", event.epochMillis)
        put("level", event.level.name.lowercase(Locale.ROOT))
        put("category", event.category)
        put("message", event.message)
        put("attributes", valueToJson(event.attributes))
    }

    private fun diagnosticReportToJson(report: DiagnosticReport): JsonObject = buildJsonObject {
        put("generatedAtEpochMillis", report.generatedAtEpochMillis)
        put("appVersion", report.appVersion)
        report.runtimeVersion?.let { put("runtimeVersion", it) }
        report.openCodeVersion?.let { put("openCodeVersion", it) }
        put("events", valueToJson(report.events))
        put("truncated", report.truncated)
    }

    private fun parseObject(raw: String): JsonObject =
        json.parseToJsonElement(raw) as? JsonObject
            ?: throw IllegalArgumentException("bridge message must be a JSON object")

    private fun requireVersionAndType(root: JsonObject, expectedType: String) {
        val version = root["v"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: throw IllegalArgumentException("bridge message requires v")
        require(version == BridgeProtocol.VERSION) { "unsupported bridge version $version" }
        val type = root.requiredString("type")
        require(type == expectedType) { "expected bridge type $expectedType, found $type" }
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("bridge message requires $name")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("bridge message requires $name")
}
