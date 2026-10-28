package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.HttpMethod
import io.github.spectordev9.opencode.core.common.TimeoutClass
import io.github.spectordev9.opencode.core.gateway.GatewayOperation
import io.github.spectordev9.opencode.core.gateway.GatewayRequest
import io.github.spectordev9.opencode.core.gateway.GatewayResponseDecoder
import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.FileEntryType
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Workspace file browser for the supervised server (Files tab).
 *
 * Composition mirrors the reference client FilesView: a path breadcrumb, a
 * flat directory listing and a read-only content preview.
 */
class FilesController(
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var client: OpenCodeClient? = null
    private var directory: String = ""

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state

    fun attach(client: OpenCodeClient, directory: String) {
        this.client = client
        this.directory = directory
        _state.update {
            FilesUiState(connected = true, rootDirectory = directory)
        }
        scope.launch { list(currentPath = "") }
    }

    fun detach() {
        client = null
        _state.update { FilesUiState(connected = false) }
    }

    suspend fun open(entry: FileEntry) {
        when (entry.type) {
            FileEntryType.DIRECTORY -> list(entry.path)
            FileEntryType.FILE -> preview(entry)
        }
    }

    suspend fun goUp() {
        val path = _state.value.currentPath
        if (path.isBlank()) return
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        list(parent)
    }

    suspend fun reload() {
        list(_state.value.currentPath)
    }

    suspend fun closePreview() {
        _state.update { it.copy(previewPath = null, previewText = null) }
    }

    private suspend fun list(currentPath: String) {
        val activeClient = client ?: return
        mutex.withLock {
            val result = activeClient.listFiles(currentPath, directory)
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        entries = result.value.sortedWith(
                            compareByDescending<FileEntry> { e -> e.type == FileEntryType.DIRECTORY }
                                .thenBy { e -> e.name.lowercase() },
                        ),
                        currentPath = currentPath,
                        error = null,
                        loading = false,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(error = result.error.message, loading = false)
                }
            }
        }
    }

    private suspend fun preview(entry: FileEntry) {
        val activeClient = client ?: return
        _state.update { it.copy(previewPath = entry.path, previewText = null, loading = true) }
        val result = activeClient.call(
            GatewayOperation<String>(
                name = "file.content",
                request = GatewayRequest(
                    method = HttpMethod.GET,
                    path = "/file/content",
                    query = mapOf("directory" to directory, "path" to entry.path),
                ),
                timeoutClass = TimeoutClass.FILE_READ,
                decoder = GatewayResponseDecoder { _, body -> String(body, Charsets.UTF_8) },
            ),
        )
        when (result) {
            is ApiResult.Success -> {
                val text = extractText(result.value)
                _state.update {
                    if (it.previewPath == entry.path) {
                        it.copy(previewText = text, loading = false, error = null)
                    } else {
                        it
                    }
                }
            }
            is ApiResult.Failure -> _state.update {
                if (it.previewPath == entry.path) {
                    it.copy(previewText = "", loading = false, error = result.error.message)
                } else {
                    it
                }
            }
        }
    }

    private fun extractText(raw: String): String {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("{")) return raw
        val json = Json { isLenient = true; ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return raw
        val content = (root["content"] as? JsonPrimitive)?.contentOrNull
        if (content != null) return content
        return raw
    }
}

data class FilesUiState(
    val connected: Boolean = false,
    val rootDirectory: String = "",
    val currentPath: String = "",
    val entries: List<FileEntry> = emptyList(),
    val loading: Boolean = false,
    val previewPath: String? = null,
    val previewText: String? = null,
    val error: String? = null,
)
