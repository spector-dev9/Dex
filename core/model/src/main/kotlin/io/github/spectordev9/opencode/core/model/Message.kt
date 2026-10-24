package io.github.spectordev9.opencode.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Message(
    val info: MessageInfo,
    val parts: List<Part>,
)

@Serializable
data class MessageInfo(
    val id: String,
    val sessionID: String,
    val role: String,
    val time: JsonObject,
    val agent: String? = null,
    val model: JsonObject? = null,
    val parentID: String? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val mode: String? = null,
    val path: JsonObject? = null,
    val cost: Double? = null,
    val tokens: JsonObject? = null,
    val finish: String? = null,
)

@Serializable
data class Part(
    val id: String,
    val sessionID: String,
    val messageID: String,
    val type: String,
    val text: String? = null,
    val synthetic: Boolean? = null,
    val ignored: Boolean? = null,
    val callID: String? = null,
    val tool: String? = null,
    val state: JsonObject? = null,
    val time: JsonObject? = null,
    val metadata: JsonObject? = null,
    val source: JsonObject? = null,
    val filename: String? = null,
    val url: String? = null,
    val mime: String? = null,
    val size: Long? = null,
)
