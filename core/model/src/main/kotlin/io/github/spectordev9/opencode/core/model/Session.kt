package io.github.spectordev9.opencode.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Session(
    val id: String,
    val slug: String,
    val projectID: String,
    val directory: String,
    val title: String,
    val version: String,
    val time: SessionTime,
    val workspaceID: String? = null,
    val path: String? = null,
    val parentID: String? = null,
    val summary: JsonElement? = null,
    val cost: Double? = null,
    val tokens: JsonElement? = null,
    val share: JsonElement? = null,
    val agent: String? = null,
    val model: JsonElement? = null,
    val metadata: JsonObject? = null,
    val permission: JsonElement? = null,
    val revert: JsonElement? = null,
)

@Serializable
data class SessionTime(
    val created: Long,
    val updated: Long,
    val archived: Long? = null,
)
