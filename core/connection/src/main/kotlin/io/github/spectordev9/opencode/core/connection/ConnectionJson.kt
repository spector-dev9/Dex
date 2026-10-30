package io.github.spectordev9.opencode.core.connection

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

object ConnectionJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = false
        coerceInputValues = false
    }

    fun <T> decode(serializer: KSerializer<T>, raw: String): T =
        try {
            json.decodeFromString(serializer, raw)
        } catch (error: SerializationException) {
            throw ParseError(ErrorMapping.redact(error.message ?: "invalid JSON"), error)
        } catch (error: IllegalArgumentException) {
            throw ParseError(ErrorMapping.redact(error.message ?: "invalid JSON"), error)
        }

    fun <T> decode(serializer: KSerializer<T>, element: JsonElement): T =
        try {
            json.decodeFromJsonElement(serializer, element)
        } catch (error: SerializationException) {
            throw ParseError(ErrorMapping.redact(error.message ?: "invalid JSON"), error)
        } catch (error: IllegalArgumentException) {
            throw ParseError(ErrorMapping.redact(error.message ?: "invalid JSON"), error)
        }

    fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    fun <T> encodeElement(serializer: KSerializer<T>, value: T): JsonElement =
        json.encodeToJsonElement(serializer, value)
}
