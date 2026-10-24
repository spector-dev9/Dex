package io.github.spectordev9.opencode.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String>,
    val metadata: JsonObject,
    val always: List<String>,
    val tool: JsonObject? = null,
)
