package io.github.spectordev9.opencode.features

import io.github.spectordev9.opencode.chat.SharedPrefsServerProfileStore
import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.TimeoutClass
import io.github.spectordev9.opencode.core.gateway.GatewayOperation
import io.github.spectordev9.opencode.core.gateway.GatewayRequest
import io.github.spectordev9.opencode.core.gateway.GatewayResponseDecoder
import io.github.spectordev9.opencode.core.gateway.HttpMethod
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A server-provided skill or reusable workflow (F53). */
data class ServerSkill(
    val name: String,
    val description: String?,
    val location: String,
    val preview: String?,
)

/** An MCP server and its current connection state (F51). */
data class McpServerInfo(
    val name: String,
    val status: String,
    val detail: String? = null,
) {
    val connected: Boolean
        get() = status == "connected"
    val needsAttention: Boolean
        get() = status == "failed" || status == "needs_auth" || status == "needs_client_registration"
}

/** A server command or skill entry (F53). */
data class ServerCommandInfo(
    val name: String,
    val description: String?,
    val source: String?,
    val agent: String?,
)

/** A server-side plugin declared in OpenCode configuration (F55). */
data class ServerPluginInfo(
    val name: String,
    val configured: String,
)

/** A redacted, read-only view of server configuration (F51/F54/F74). */
data class ServerConfigInfo(
    val shell: String? = null,
    val logLevel: String? = null,
    val pluginCount: Int = 0,
    val mcpCount: Int = 0,
    val commandCount: Int = 0,
    val skillPathCount: Int = 0,
    val share: String? = null,
)

data class ServerExtensionsState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val skills: List<ServerSkill> = emptyList(),
    val mcpServers: List<McpServerInfo> = emptyList(),
    val commands: List<ServerCommandInfo> = emptyList(),
    val plugins: List<ServerPluginInfo> = emptyList(),
    val config: ServerConfigInfo? = null,
    val backgroundSubagents: Boolean? = null,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * Loads and mutates OpenCode's extension surface: skills, commands, MCP
 * servers, plugins and the public configuration document (F51, F53, F54, F55,
 * F74). Parsing is kept separate from I/O so it can be tested on the JVM.
 */
class ServerExtensionsController(private val scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val _state = MutableStateFlow(ServerExtensionsState())
    val state: StateFlow<ServerExtensionsState> = _state.asStateFlow()

    @Volatile
    private var client: OpenCodeClient? = null

    @Volatile
    private var directory: String = ""

    fun attach(client: OpenCodeClient, directory: String) {
        this.client = client
        this.directory = directory
        refresh()
    }

    fun detach() {
        client = null
        directory = ""
        _state.value = ServerExtensionsState()
    }

    fun refresh() {
        scope.launch { reload() }
    }

    suspend fun reload() {
        val active = client ?: return
        _state.update { it.copy(loading = true, error = null) }
        val skills = active.call(rawOperation("skill.list", HttpMethod.GET, "/skill"))
        val mcp = active.call(rawOperation("mcp.status", HttpMethod.GET, "/mcp"))
        val commands = active.call(rawOperation("command.list", HttpMethod.GET, "/command"))
        val config = active.call(rawOperation("config.get", HttpMethod.GET, "/config"))
        val capabilities = active.call(
            rawOperation("experimental.capabilities", HttpMethod.GET, "/experimental/capabilities"),
        )
        val failures = listOf(skills, mcp, commands, config, capabilities)
            .filterIsInstance<ApiResult.Failure>()
        val parsedConfig = (config as? ApiResult.Success)?.let { parseServerConfig(json, it.value) }
        val parsedCapabilities = (capabilities as? ApiResult.Success)
            ?.let { parseCapabilities(json, it.value) }
        _state.update {
            it.copy(
                loading = false,
                loaded = true,
                skills = (skills as? ApiResult.Success)?.let { result ->
                    parseSkills(json, result.value)
                } ?: it.skills,
                mcpServers = (mcp as? ApiResult.Success)?.let { result ->
                    parseMcpServers(json, result.value)
                } ?: it.mcpServers,
                commands = (commands as? ApiResult.Success)?.let { result ->
                    parseCommands(json, result.value)
                } ?: it.commands,
                plugins = parsedConfig?.plugins ?: it.plugins,
                config = parsedConfig?.config ?: it.config,
                backgroundSubagents = parsedCapabilities ?: it.backgroundSubagents,
                error = failures.firstOrNull()?.error?.message,
            )
        }
    }

    suspend fun addLocalMcp(
        name: String,
        command: String,
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
    ): ApiResult<Unit> {
        val cleanName = validateMcpName(name)
            ?: return failure("MCP server name is empty or too long")
        val executable = command.trim().takeIf { it.isNotEmpty() }
            ?: return failure("MCP command is required")
        val body = buildJsonObject {
            put("name", cleanName)
            put(
                "config",
                buildJsonObject {
                    put("type", "local")
                    put("command", buildJsonArray {
                        add(JsonPrimitive(executable))
                        arguments.forEach { add(JsonPrimitive(it)) }
                    })
                    if (environment.isNotEmpty()) {
                        put("environment", buildJsonObject {
                            environment.forEach { (key, value) -> put(key, value) }
                        })
                    }
                },
            )
        }
        return mutate("mcp.add", "/mcp", body, "Added MCP server $cleanName")
    }

    suspend fun addRemoteMcp(name: String, url: String): ApiResult<Unit> {
        val cleanName = validateMcpName(name)
            ?: return failure("MCP server name is empty or too long")
        val cleanUrl = SharedPrefsServerProfileStore.validateProfileEndpoint(url)
            ?: return failure("Remote MCP URLs must use HTTPS unless they are loopback")
        val body = buildJsonObject {
            put("name", cleanName)
            put(
                "config",
                buildJsonObject {
                    put("type", "remote")
                    put("url", cleanUrl)
                },
            )
        }
        return mutate("mcp.add", "/mcp", body, "Added MCP server $cleanName")
    }

    suspend fun connectMcp(name: String): ApiResult<Unit> =
        mutateBoolean("mcp.connect", "/mcp/$name/connect", "Connected $name")

    suspend fun disconnectMcp(name: String): ApiResult<Unit> =
        mutateBoolean("mcp.disconnect", "/mcp/$name/disconnect", "Disconnected $name")

    suspend fun saveConfig(raw: String): ApiResult<Unit> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) {
            return failure("Configuration must be JSON smaller than 256 KiB")
        }
        val parsed = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: return failure("Configuration must be a JSON object")
        val active = client ?: return failure("Server is not connected")
        val result = active.call(
            rawOperation(
                name = "config.update",
                method = HttpMethod.PATCH,
                path = "/config",
                body = parsed.toString().toByteArray(Charsets.UTF_8),
            ),
        )
        return when (result) {
            is ApiResult.Success -> {
                val parsedConfig = parseServerConfig(json, result.value)
                _state.update {
                    it.copy(
                        plugins = parsedConfig?.plugins ?: it.plugins,
                        config = parsedConfig?.config ?: it.config,
                        notice = "Server configuration saved",
                        error = null,
                    )
                }
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(error = result.error.message) }
                result
            }
        }
    }

    private suspend fun mutate(
        name: String,
        path: String,
        body: JsonObject,
        notice: String,
    ): ApiResult<Unit> {
        val active = client ?: return failure("Server is not connected")
        val result = active.call(
            rawOperation(
                name = name,
                method = HttpMethod.POST,
                path = path,
                body = json.encodeToString(JsonObject.serializer(), body).toByteArray(Charsets.UTF_8),
            ),
        )
        return when (result) {
            is ApiResult.Success -> {
                _state.update { it.copy(notice = notice, error = null) }
                reload()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(error = result.error.message) }
                result
            }
        }
    }

    private suspend fun mutateBoolean(name: String, path: String, notice: String): ApiResult<Unit> {
        val active = client ?: return failure("Server is not connected")
        val operation = GatewayOperation(
            name = name,
            request = GatewayRequest(
                method = HttpMethod.POST,
                path = path,
                query = directoryQuery(),
            ),
            timeoutClass = TimeoutClass.LIST_GET,
            decoder = GatewayResponseDecoder { _, body ->
                String(body, Charsets.UTF_8).trim() != "false"
            },
        )
        return when (val result = active.call(operation)) {
            is ApiResult.Success -> {
                _state.update { it.copy(notice = notice, error = null) }
                reload()
                ApiResult.Success(Unit)
            }
            is ApiResult.Failure -> {
                _state.update { it.copy(error = result.error.message) }
                result
            }
        }
    }

    private fun rawOperation(
        name: String,
        method: HttpMethod,
        path: String,
        body: ByteArray? = null,
    ): GatewayOperation<String> = GatewayOperation(
        name = name,
        request = GatewayRequest(
            method = method,
            path = path,
            query = directoryQuery(),
            body = body,
            contentType = if (body == null) null else "application/json",
        ),
        timeoutClass = TimeoutClass.LIST_GET,
        decoder = GatewayResponseDecoder { _, bytes -> String(bytes, Charsets.UTF_8) },
    )

    private fun directoryQuery(): Map<String, String> =
        directory.takeIf { it.isNotBlank() }?.let { mapOf("directory" to it) } ?: emptyMap()

    private fun failure(message: String): ApiResult.Failure {
        _state.update { it.copy(error = message) }
        return ApiResult.Failure(ApiError(ErrorCode.E_UPSTREAM, message))
    }

    companion object {
        const val MAX_CONFIG_BYTES = 256 * 1024
        const val MAX_MCP_NAME_LENGTH = 64
        const val MAX_SKILL_PREVIEW_CHARS = 240

        fun validateMcpName(raw: String?): String? {
            val name = raw?.trim() ?: return null
            if (name.isEmpty() || name.length > MAX_MCP_NAME_LENGTH) return null
            if (name.any { it.isWhitespace() || it == '/' || it == '\\' }) return null
            return name
        }
    }
}

internal fun parseSkills(json: Json, raw: String): List<ServerSkill> {
    val values = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
        ?: return emptyList()
    return values.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val location = obj.string("location")?.takeIf { it.isNotBlank() } ?: "unknown"
        ServerSkill(
            name = name,
            description = obj.string("description")?.takeIf { it.isNotBlank() },
            location = location,
            preview = obj.string("content")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(ServerExtensionsController.MAX_SKILL_PREVIEW_CHARS)
                ?.takeIf { it.isNotEmpty() },
        )
    }.distinctBy { it.name }.sortedBy { it.name.lowercase() }
}

internal fun parseMcpServers(json: Json, raw: String): List<McpServerInfo> {
    val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return emptyList()
    return root.mapNotNull { (name, element) ->
        val status = (element as? JsonObject)?.string("status") ?: return@mapNotNull null
        McpServerInfo(
            name = name,
            status = status,
            detail = (element as? JsonObject)?.string("error"),
        )
    }.sortedBy { it.name.lowercase() }
}

internal fun parseCommands(json: Json, raw: String): List<ServerCommandInfo> {
    val values = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
        ?: return emptyList()
    return values.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ServerCommandInfo(
            name = name,
            description = obj.string("description")?.takeIf { it.isNotBlank() },
            source = obj.string("source"),
            agent = obj.string("agent"),
        )
    }.distinctBy { it.name }.sortedBy { it.name.lowercase() }
}

internal data class ParsedServerConfig(
    val config: ServerConfigInfo,
    val plugins: List<ServerPluginInfo>,
)

internal fun parseServerConfig(json: Json, raw: String): ParsedServerConfig? {
    val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val plugins = (root["plugin"] as? JsonArray)?.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { value ->
                ServerPluginInfo(name = pluginName(value), configured = value)
            }
            is JsonArray -> element.firstOrNull()
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { value -> ServerPluginInfo(name = pluginName(value), configured = value) }
            else -> null
        }
    } ?: emptyList()
    val skills = root["skills"] as? JsonObject
    val config = ServerConfigInfo(
        shell = root.string("shell"),
        logLevel = root.string("logLevel"),
        pluginCount = plugins.size,
        mcpCount = (root["mcp"] as? JsonObject)?.size ?: 0,
        commandCount = (root["command"] as? JsonObject)?.size ?: 0,
        skillPathCount = (skills?.get("paths") as? JsonArray)?.size ?: 0,
        share = root.string("share"),
    )
    return ParsedServerConfig(config, plugins)
}

internal fun parseCapabilities(json: Json, raw: String): Boolean? {
    val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    return (root["backgroundSubagents"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}

private fun pluginName(value: String): String =
    value.substringAfterLast('/').substringAfterLast(':').ifBlank { value }.take(80)

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
