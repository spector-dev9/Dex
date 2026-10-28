package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.FileEntryType
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import io.github.spectordev9.opencode.core.workspace.EntryKind
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.WorkspaceService
import io.github.spectordev9.opencode.core.workspace.Entry
import io.github.spectordev9.opencode.core.workspace.impl.FileWorkspaceService
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Workspace file browser for the supervised server (Files tab).
 *
 * Composition mirrors the reference client FilesView: a path breadcrumb, a
 * flat directory listing and a conflict-aware text editor.
 */
class FilesController(
    private val scope: CoroutineScope,
) {
    private val maxEditableFileBytes = 2L * 1024 * 1024
    private val mutex = Mutex()
    private var client: OpenCodeClient? = null
    private var workspace: WorkspaceService? = null
    private var directory: String = ""
    private val searchGeneration = AtomicInteger()

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state

    fun attach(client: OpenCodeClient, directory: String) {
        searchGeneration.incrementAndGet()
        this.client = client
        this.directory = directory
        workspace = FileWorkspaceService(Path.of(directory))
        _state.update {
            FilesUiState(connected = true, rootDirectory = directory)
        }
        scope.launch { list(currentPath = "") }
    }

    fun detach() {
        searchGeneration.incrementAndGet()
        client = null
        workspace = null
        _state.update { FilesUiState(connected = false) }
    }

    suspend fun createFile(name: String) {
        val target = childPath(name) ?: return
        mutateWorkspace { service ->
            when (val result = service.write(target, ByteArray(0), Precondition.None)) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.Failure -> result
            }
        }
    }

    suspend fun createDirectory(name: String) {
        val target = childPath(name) ?: return
        mutateWorkspace { service ->
            when (val result = service.mkdir(target)) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.Failure -> result
            }
        }
    }

    suspend fun rename(path: String, newName: String) {
        val normalizedName = newName.trim()
        if (normalizedName.isEmpty() || '/' in normalizedName || '\\' in normalizedName) {
            _state.update { it.copy(error = "Enter a valid name without path separators") }
            return
        }
        val parent = path.trimEnd('/').substringBeforeLast('/', "")
        val target = if (parent.isEmpty()) normalizedName else "$parent/$normalizedName"
        mutateWorkspace { service ->
            when (val result = service.rename(path, target)) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.Failure -> result
            }
        }
    }

    suspend fun trash(path: String) {
        mutateWorkspace { service ->
            when (val result = service.trash(path)) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.Failure -> result
            }
        }
    }

    suspend fun search(query: String, extension: String = "") {
        val generation = searchGeneration.incrementAndGet()
        val service = workspace
        val normalizedQuery = query.trim()
        val normalizedExtension = extension.trim().removePrefix(".").lowercase()
        if (service == null) {
            _state.update { it.copy(error = "Workspace service is unavailable") }
            return
        }
        if (normalizedQuery.isEmpty()) {
            _state.update {
                it.copy(
                    searchQuery = "",
                    searchResults = emptyList(),
                    searching = false,
                    searchTruncated = false,
                    searchSkippedFiles = 0,
                )
            }
            return
        }
        _state.update {
            it.copy(
                searchQuery = normalizedQuery,
                searchResults = emptyList(),
                searching = true,
                error = null,
            )
        }
        val nameMatches = when (val result = service.search(
            path = _state.value.currentPath.ifBlank { "/" },
            query = normalizedQuery,
            limit = MAX_SEARCH_RESULTS,
        )) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                if (generation == searchGeneration.get()) {
                    _state.update { it.copy(searching = false, error = result.error.message) }
                }
                return
            }
        }
        val fileMatches = mutableListOf<FileSearchResult>()
        var scannedBytes = 0L
        var skippedFiles = 0
        val queue = ArrayDeque<String>().apply { add(_state.value.currentPath.ifBlank { "/" }) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_FILES && scannedBytes < MAX_SEARCH_BYTES) {
            if (generation != searchGeneration.get()) return
            val directoryPath = queue.removeFirst()
            val entries = when (val result = service.list(directoryPath)) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> {
                    if (generation == searchGeneration.get()) {
                        _state.update { it.copy(searching = false, error = result.error.message) }
                    }
                    return
                }
            }
            for (entry in entries) {
                when (entry.kind) {
                    EntryKind.DIRECTORY -> queue.addLast(entry.path)
                    EntryKind.FILE -> {
                        visited++
                        val fileName = entry.name
                        if (normalizedExtension.isNotEmpty() &&
                            fileName.substringAfterLast('.', "").lowercase() != normalizedExtension
                        ) {
                            continue
                        }
                        if (entry.sizeBytes > MAX_SEARCH_FILE_BYTES ||
                            scannedBytes + entry.sizeBytes > MAX_SEARCH_BYTES
                        ) {
                            skippedFiles++
                            continue
                        }
                        when (val read = readFile(service, entry)) {
                            is ApiResult.Success -> {
                                val bytes = read.value
                                scannedBytes += bytes.size
                                val content = decodeUtf8(bytes)
                                if (content == null) {
                                    skippedFiles++
                                } else {
                                    content.lineSequence().forEachIndexed { index, line ->
                                        if (line.contains(normalizedQuery, ignoreCase = true)) {
                                            fileMatches += FileSearchResult(
                                                path = entry.path.removePrefix("/"),
                                                line = index + 1,
                                                snippet = line.trim().take(MAX_SEARCH_SNIPPET_CHARS),
                                            )
                                        }
                                    }
                                }
                            }
                            is ApiResult.Failure -> {
                                if (generation == searchGeneration.get()) {
                                    _state.update {
                                        it.copy(searching = false, error = read.error.message)
                                    }
                                }
                                return
                            }
                        }
                    }
                    EntryKind.SYMLINK, EntryKind.OTHER -> Unit
                }
                if (visited >= MAX_SEARCH_FILES || scannedBytes >= MAX_SEARCH_BYTES) break
            }
        }
        val results = (
            nameMatches.filter {
                normalizedExtension.isEmpty() ||
                    it.name.substringAfterLast('.', "").lowercase() == normalizedExtension
            }.map {
                FileSearchResult(
                    path = it.path.removePrefix("/"),
                    isDirectory = it.kind == EntryKind.DIRECTORY,
                )
            } + fileMatches
            )
            .distinctBy { it.path to it.line }
            .take(MAX_SEARCH_RESULTS)
        _state.update {
            if (generation == searchGeneration.get()) {
                it.copy(
                    searchResults = results.map { result ->
                        if (result.path.startsWith("/")) result.copy(path = result.path.removePrefix("/")) else result
                    },
                    searching = false,
                    searchTruncated = queue.isNotEmpty() || visited >= MAX_SEARCH_FILES ||
                        scannedBytes >= MAX_SEARCH_BYTES,
                    searchSkippedFiles = skippedFiles,
                    error = null,
                )
            } else {
                it
            }
        }
    }

    fun clearSearch() {
        searchGeneration.incrementAndGet()
        _state.update {
            it.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searching = false,
                searchTruncated = false,
                searchSkippedFiles = 0,
            )
        }
    }

    private suspend fun readFile(service: WorkspaceService, entry: Entry): ApiResult<ByteArray> {
        val output = ByteArrayOutputStream(entry.sizeBytes.toInt())
        var offset = 0L
        while (offset < entry.sizeBytes) {
            when (val result = service.readRange(entry.path, offset, READ_CHUNK_BYTES)) {
                is ApiResult.Success -> {
                    if (result.value.bytes.isEmpty() && !result.value.endOfFile) {
                        return ApiResult.Failure(
                            io.github.spectordev9.opencode.core.common.ApiError(
                                io.github.spectordev9.opencode.core.common.ErrorCode.E_UPSTREAM,
                                "Workspace returned an incomplete file read",
                            ),
                        )
                    }
                    output.write(result.value.bytes)
                    offset += result.value.bytes.size
                    if (result.value.endOfFile) break
                }
                is ApiResult.Failure -> return result
            }
        }
        return ApiResult.Success(output.toByteArray())
    }

    private fun decodeUtf8(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * Tree support (F11): expand a directory in place by fetching its
     * children without moving the browse root, or collapse it again.
     */
    suspend fun toggleExpand(entry: FileEntry) {
        val activeClient = client ?: return
        if (entry.type != FileEntryType.DIRECTORY) return
        val wasExpanded = entry.path in _state.value.expanded
        if (wasExpanded) {
            _state.update { it.copy(expanded = it.expanded - entry.path) }
            return
        }
        if (entry.path in _state.value.children) {
            _state.update { it.copy(expanded = it.expanded + entry.path) }
            return
        }
        _state.update { it.copy(loadingDirs = it.loadingDirs + entry.path) }
        val result = activeClient.listFiles(entry.path, directory)
        when (result) {
            is ApiResult.Success -> _state.update {
                it.copy(
                    children = it.children + (entry.path to result.value.sortedWith(
                        compareByDescending<FileEntry> { e -> e.type == FileEntryType.DIRECTORY }
                            .thenBy { e -> e.name.lowercase() },
                    )),
                    expanded = it.expanded + entry.path,
                    loadingDirs = it.loadingDirs - entry.path,
                    error = null,
                )
            }

            is ApiResult.Failure -> _state.update {
                it.copy(
                    error = result.error.message,
                    loadingDirs = it.loadingDirs - entry.path,
                )
            }
        }
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
        _state.update {
            it.copy(
                previewPath = null,
                previewText = null,
                previewOriginalText = null,
                previewOriginalSize = null,
                previewOriginalModifiedAt = null,
                previewOriginalSha256 = null,
                editingPreview = false,
            )
        }
    }

    fun editPreview() {
        _state.update {
            if (it.previewText == null) it else it.copy(editingPreview = true, error = null)
        }
    }

    fun updatePreviewText(text: String) {
        _state.update { if (it.editingPreview) it.copy(previewText = text) else it }
    }

    fun cancelPreviewEdit() {
        _state.update {
            it.copy(previewText = it.previewOriginalText, editingPreview = false, error = null)
        }
    }

    suspend fun savePreviewEdit() {
        val current = _state.value
        val path = current.previewPath ?: return
        val text = current.previewText ?: return
        val size = current.previewOriginalSize ?: return
        val modifiedAt = current.previewOriginalModifiedAt ?: return
        val expectedSha256 = current.previewOriginalSha256 ?: return
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.size > maxEditableFileBytes) {
            _state.update { it.copy(error = "Edited file exceeds the mobile editor size limit") }
            return
        }
        val service = workspace
        if (service == null) {
            _state.update { it.copy(error = "Workspace service is unavailable") }
            return
        }
        when (
            val result = service.write(
                path = path,
                data = data,
                expect = Precondition.Match(
                    sizeBytes = size,
                    modifiedAtEpochMillis = modifiedAt,
                    sha256 = expectedSha256,
                ),
            )
        ) {
            is ApiResult.Success -> {
                val newHash = sha256(data)
                _state.update {
                    if (it.previewPath == path) {
                        it.copy(
                            previewOriginalText = text,
                            previewOriginalSize = result.value.sizeBytes,
                            previewOriginalModifiedAt = result.value.modifiedAtEpochMillis,
                            previewOriginalSha256 = newHash,
                            editingPreview = false,
                            error = null,
                        )
                    } else {
                        it
                    }
                }
                reload()
            }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
    }

    private fun childPath(name: String): String? {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty() || '/' in normalizedName || '\\' in normalizedName) {
            _state.update { it.copy(error = "Enter a valid name without path separators") }
            return null
        }
        val parent = _state.value.currentPath.trim('/')
        return if (parent.isEmpty()) normalizedName else "$parent/$normalizedName"
    }

    private suspend fun mutateWorkspace(
        operation: suspend (WorkspaceService) -> ApiResult<Unit>,
    ) {
        val service = workspace
        if (service == null) {
            _state.update { it.copy(error = "Workspace service is unavailable") }
            return
        }
        when (val result = operation(service)) {
            is ApiResult.Success -> {
                _state.update { it.copy(error = null) }
                reload()
            }
            is ApiResult.Failure -> _state.update { it.copy(error = result.error.message) }
        }
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
                    // Navigation replaces the visible tree; stale expansions
                    // of other directories are not meaningful here.
                    expanded = emptySet(),
                    children = emptyMap(),
                    loadingDirs = emptySet(),
                )
            }
                is ApiResult.Failure -> _state.update {
                    it.copy(error = result.error.message, loading = false)
                }
            }
        }
    }

    private suspend fun preview(entry: FileEntry) {
        val service = workspace ?: return
        _state.update {
            it.copy(
                previewPath = entry.path,
                previewText = null,
                previewOriginalText = null,
                previewOriginalSize = null,
                previewOriginalModifiedAt = null,
                previewOriginalSha256 = null,
                editingPreview = false,
                loading = true,
                error = null,
            )
        }
        val stat = when (val result = service.stat(entry.path)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                setPreviewFailure(entry.path, result.error.message)
                return
            }
        }
        if (stat.kind != EntryKind.FILE) {
            setPreviewFailure(entry.path, "Only regular files can be opened in the editor")
            return
        }
        if (stat.sizeBytes > maxEditableFileBytes) {
            setPreviewFailure(entry.path, "File is too large to edit in the mobile editor")
            return
        }
        val output = ByteArrayOutputStream(stat.sizeBytes.toInt())
        var offset = 0L
        while (offset < stat.sizeBytes) {
            when (val result = service.readRange(entry.path, offset, READ_CHUNK_BYTES)) {
                is ApiResult.Success -> {
                    if (result.value.bytes.isEmpty() && !result.value.endOfFile) {
                        setPreviewFailure(entry.path, "Workspace returned an incomplete file read")
                        return
                    }
                    output.write(result.value.bytes)
                    offset += result.value.bytes.size
                    if (result.value.endOfFile) break
                }
                is ApiResult.Failure -> {
                    setPreviewFailure(entry.path, result.error.message)
                    return
                }
            }
        }
        val bytes = output.toByteArray()
        if (bytes.any { it == 0.toByte() }) {
            setPreviewFailure(entry.path, "Binary files cannot be edited as text")
            return
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            setPreviewFailure(entry.path, "File is not valid UTF-8 text and cannot be edited safely")
            return
        }
        val afterRead = when (val result = service.stat(entry.path)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> {
                setPreviewFailure(entry.path, result.error.message)
                return
            }
        }
        if (afterRead.sizeBytes != stat.sizeBytes || afterRead.modifiedAtEpochMillis != stat.modifiedAtEpochMillis) {
            setPreviewFailure(entry.path, "File changed while it was being opened; reopen it before editing")
            return
        }
        _state.update {
            if (it.previewPath == entry.path) {
                it.copy(
                    previewText = text,
                    previewOriginalText = text,
                    previewOriginalSize = stat.sizeBytes,
                    previewOriginalModifiedAt = stat.modifiedAtEpochMillis,
                    previewOriginalSha256 = sha256(bytes),
                    loading = false,
                    error = null,
                )
            } else {
                it
            }
        }
    }

    private fun setPreviewFailure(path: String, message: String?) {
        _state.update {
            if (it.previewPath == path) {
                it.copy(previewText = null, loading = false, error = message ?: "File could not be opened")
            } else {
                it
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
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
    val previewOriginalText: String? = null,
    val previewOriginalSize: Long? = null,
    val previewOriginalModifiedAt: Long? = null,
    val previewOriginalSha256: String? = null,
    val editingPreview: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<FileSearchResult> = emptyList(),
    val searching: Boolean = false,
    val searchTruncated: Boolean = false,
    val searchSkippedFiles: Int = 0,
    val error: String? = null,
    /** Directories currently expanded in the inline tree (F11). */
    val expanded: Set<String> = emptySet(),
    /** Fetched children of expanded directories, keyed by directory path. */
    val children: Map<String, List<FileEntry>> = emptyMap(),
    /** Directories with an in-flight children fetch. */
    val loadingDirs: Set<String> = emptySet(),
)

data class FileSearchResult(
    val path: String,
    val line: Int? = null,
    val snippet: String? = null,
    val isDirectory: Boolean = false,
)

private const val MAX_SEARCH_RESULTS = 100
private const val MAX_SEARCH_FILES = 2_000
private const val MAX_SEARCH_FILE_BYTES = 512 * 1024L
private const val MAX_SEARCH_BYTES = 16 * 1024 * 1024L
private const val MAX_SEARCH_SNIPPET_CHARS = 180

/** One flattened row of the file explorer tree. */
data class FileTreeRow(
    val entry: FileEntry,
    val depth: Int,
)

/** Depth-first flattening of expanded directories into renderable rows. */
fun flattenFileTree(entries: List<FileEntry>, state: FilesUiState): List<FileTreeRow> {
    val rows = mutableListOf<FileTreeRow>()
    fun walk(list: List<FileEntry>, depth: Int) {
        list.forEach { entry ->
            rows += FileTreeRow(entry, depth)
            if (entry.type == FileEntryType.DIRECTORY && entry.path in state.expanded) {
                val kids = state.children[entry.path].orEmpty()
                if (kids.isNotEmpty()) walk(kids, depth + 1)
            }
        }
    }
    walk(entries, 0)
    return rows
}
