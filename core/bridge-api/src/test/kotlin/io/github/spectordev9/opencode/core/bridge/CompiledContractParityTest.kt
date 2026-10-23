package io.github.spectordev9.opencode.core.bridge

import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

private const val CORE = "io.github.spectordev9.opencode.core"

private fun repoRoot(): Path = Path.of(System.getProperty("repoRoot"))

private fun read(relative: String): String =
    Files.readString(repoRoot().resolve(relative))

private fun check(condition: Boolean, message: String) {
    if (!condition) error(message)
}

private fun staticStrings(type: Class<*>): Map<String, String> =
    type.declaredFields
        .filter {
            Modifier.isStatic(it.modifiers) &&
                it.type == String::class.java &&
                it.name != "INSTANCE"
        }
        .associate { it.name to it.get(null) as String }

private fun enumValues(type: Class<*>): List<Enum<*>> =
    type.enumConstants.map { it as Enum<*> }

private fun verifyCommands() {
    val namesType = Class.forName("$CORE.bridge.BridgeCommandNames")
    val instance = namesType.getField("INSTANCE").get(null)
    @Suppress("UNCHECKED_CAST")
    val all = namesType.getMethod("getAll").invoke(instance) as Set<String>
    val constants = staticStrings(namesType).values.toSet()

    check(all == constants, "BridgeCommandNames.all does not match its constants")

    val spec = read("docs/spec.md")
    val commandBlock = spec.substringAfter("**Commands:**").substringBefore("**Events:**")
    val specCommands = Regex(
        """\b(?:runtime|session|workspace|git|terminal|settings)\.[A-Za-z]+\b""",
    ).findAll(commandBlock).map { it.value }.toSet()

    check(specCommands == all, "compiled commands differ from spec 4.3: $specCommands vs $all")
    check(all.size == 47, "expected 47 bridge commands, found ${all.size}")
}

private fun verifyProtocol() {
    val ts = read("ui/src/bridge/protocol.ts")
    val bridgeType = Class.forName("$CORE.bridge.BridgeProtocol")

    val tsVersion = Regex("""export const PROTOCOL_VERSION = (\d+) as const;""")
        .find(ts)?.groupValues?.get(1)?.toInt()
    check(tsVersion != null, "TypeScript protocol version is missing")
    check(bridgeType.getField("VERSION").getInt(null) == tsVersion, "protocol versions differ")

    val tsErrorBlock = ts.substringAfter("export type ErrorCode =").substringBefore(";")
    val tsErrors = Regex(""""(E_[A-Z_]+)"""").findAll(tsErrorBlock).map { it.groupValues[1] }.toList()
    val errorType = Class.forName("$CORE.common.ErrorCode")
    val ktErrors = enumValues(errorType).map { it.name }
    check(tsErrors == ktErrors, "error code order/names differ: $tsErrors vs $ktErrors")

    val tsRetryableBlock = ts
        .substringAfter("export const RETRYABLE: Readonly<Record<ErrorCode, boolean>> = {")
        .substringBefore("};")
    val tsRetryable = Regex("""\b(E_[A-Z_]+):\s*(true|false)""")
        .findAll(tsRetryableBlock)
        .associate { it.groupValues[1] to it.groupValues[2].toBooleanStrict() }
    val ktRetryable = enumValues(errorType).associate { enumValue ->
        val retryable = enumValue.javaClass.getMethod("getRetryable").invoke(enumValue) as Boolean
        enumValue.name to retryable
    }
    check(tsRetryable == ktRetryable, "retryable flags differ: $tsRetryable vs $ktRetryable")

    val tsTimeouts = Regex("""(\w+):\s*([\d_]+)""")
        .findAll(ts.substringAfter("export const TIMEOUTS = {").substringBefore("} as const;"))
        .associate { it.groupValues[1] to it.groupValues[2].replace("_", "").toLong() }
    val timeoutType = Class.forName("$CORE.common.OperationTimeouts")
    val timeoutFields = timeoutType.declaredFields
        .filter {
            Modifier.isStatic(it.modifiers) &&
                it.type == Long::class.javaPrimitiveType
        }
        .associate { it.name to it.getLong(null) }
    val timeoutNames = mapOf(
        "health" to "HEALTH_MILLIS",
        "listGet" to "LIST_GET_MILLIS",
        "fileRead" to "FILE_READ_MILLIS",
        "promptAsync" to "PROMPT_ASYNC_MILLIS",
        "abort" to "ABORT_MILLIS",
        "sseIdleWatchdog" to "SSE_IDLE_MILLIS",
        "gitNetwork" to "GIT_NETWORK_MILLIS",
    )
    for ((tsName, ktName) in timeoutNames) {
        check(tsTimeouts[tsName] == timeoutFields[ktName], "timeout $tsName differs")
    }

    val tsTopics = Regex(""""[a-z.]+"""")
        .findAll(ts.substringAfter("export type EventTopic =").substringBefore(";"))
        .map { it.value.trim('"') }
        .toList()
    val topicType = Class.forName("$CORE.bridge.EventTopic")
    val ktTopics = enumValues(topicType).map { topic ->
        topic.javaClass.getMethod("getWireName").invoke(topic) as String
    }
    check(tsTopics == ktTopics, "event topics differ: $tsTopics vs $ktTopics")

    for ((tsType, ktName) in listOf(
        "cmd" to "TYPE_COMMAND",
        "res" to "TYPE_RESULT",
        "evt" to "TYPE_EVENT",
    )) {
        check(
            bridgeType.getField(ktName).get(null) == tsType,
            "envelope type $ktName differs",
        )
        check(ts.contains("type: \"$tsType\""), "TypeScript envelope type $tsType is missing")
    }
}

fun main() {
    verifyCommands()
    verifyProtocol()
    println("compiled contract parity: 47 commands, protocol v1, errors, timeouts and topics match")
}
