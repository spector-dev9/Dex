package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.git.GitBranch
import io.github.spectordev9.opencode.core.git.GitChange
import io.github.spectordev9.opencode.core.git.GitCommit
import io.github.spectordev9.opencode.core.git.GitDiff
import io.github.spectordev9.opencode.core.git.GitService
import io.github.spectordev9.opencode.core.git.GitStatus
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.WorkspaceService
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GitUiState(
    val connected: Boolean = false,
    val repositoryPath: String = "",
    val initialized: Boolean = false,
    val status: GitStatus? = null,
    val branches: List<GitBranch> = emptyList(),
    val history: List<GitCommit> = emptyList(),
    val diff: GitDiff? = null,
    val diffTitle: String? = null,
    val busy: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val conflictPath: String? = null,
    val conflictText: String? = null,
    val conflictBlocks: List<ConflictBlock> = emptyList(),
)

/** One `<<<<<<<` conflict hunk within a conflicted file. */
data class ConflictBlock(
    val index: Int,
    val startLine: Int,
    val endLine: Int,
    val oursLabel: String,
    val theirsLabel: String,
    val ours: List<String>,
    val theirs: List<String>,
)

enum class ConflictKeep { OURS, THEIRS, BOTH }

private const val READ_CHUNK_BYTES = 64 * 1024
private const val MAX_CONFLICT_FILE_BYTES = 1 * 1024 * 1024

/**
 * UI state driver for the Git tab (F31–F35).
 *
 * Delegates all repository mutations to [GitService] and re-queries status,
 * branches and history after every successful mutation so the UI never
 * desyncs from the repository.
 */
class GitController(
    private val scope: CoroutineScope,
    private val git: GitService,
    private val repositoryPath: String,
    private val workspace: WorkspaceService? = null,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(GitUiState(repositoryPath = repositoryPath))
    val state: StateFlow<GitUiState> = _state

    init {
        _state.update { it.copy(connected = true) }
        scope.launch { refresh() }
    }

    /** Re-queries status, branches and history in one pass. */
    fun refresh() {
        scope.launch {
            _state.update { it.copy(refreshing = true) }
            val status = git.status(repositoryPath)
            when (status) {
                is ApiResult.Failure -> {
                    // "not a git repository" means the workspace has no repo yet.
                    val uninitialized = status.error.code ==
                        io.github.spectordev9.opencode.core.common.ErrorCode.E_PATH_DENIED
                    _state.update {
                        if (uninitialized) {
                            it.copy(
                                refreshing = false,
                                initialized = false,
                                status = null,
                                branches = emptyList(),
                                history = emptyList(),
                                diff = null,
                                error = null,
                            )
                        } else {
                            it.copy(refreshing = false, error = describe(status.error))
                        }
                    }
                    return@launch
                }

                is ApiResult.Success -> {
                    val branches = git.branches(repositoryPath)
                    val history = git.history(repositoryPath)
                    _state.update {
                        it.copy(
                            refreshing = false,
                            initialized = true,
                            status = status.value,
                            branches = (branches as? ApiResult.Success)?.value ?: it.branches,
                            history = (history as? ApiResult.Success)?.value ?: it.history,
                            error = when {
                                branches is ApiResult.Failure -> describe(branches.error)
                                history is ApiResult.Failure -> describe(history.error)
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }

    suspend fun initializeRepository() {
        mutate {
            when (val result = git.initialize(repositoryPath)) {
                is ApiResult.Success -> notice("Repository initialized")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun stage(paths: List<String>) {
        mutate {
            when (val result = git.stage(repositoryPath, paths)) {
                is ApiResult.Success ->
                    notice("Staged ${paths.size} ${if (paths.size == 1) "file" else "files"}")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun stageAll() = stage(state.value.status?.changes?.map(GitChange::path).orEmpty())

    suspend fun unstage(paths: List<String>) {
        mutate {
            when (val result = git.unstage(repositoryPath, paths)) {
                is ApiResult.Success ->
                    notice("Unstaged ${paths.size} ${if (paths.size == 1) "file" else "files"}")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun discard(paths: List<String>) {
        mutate {
            when (val result = git.discardChanges(repositoryPath, paths)) {
                is ApiResult.Success ->
                    notice("Discarded changes for ${paths.size} ${if (paths.size == 1) "file" else "files"}")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun commit(message: String, paths: List<String> = emptyList()) {
        mutate {
            when (val result = git.commit(repositoryPath, message, paths)) {
                is ApiResult.Success -> notice("Committed ${result.value.hash}")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun checkout(ref: String) {
        mutate {
            when (val result = git.checkout(repositoryPath, ref)) {
                is ApiResult.Success -> notice("Checked out $ref")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun fetch(remote: String? = null) {
        mutate {
            when (val result = git.fetch(repositoryPath, remote)) {
                is ApiResult.Success -> notice("Fetched from ${remote ?: "all remotes"}")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun pull(remote: String? = null, branch: String? = null) {
        mutate {
            when (val result = git.pull(repositoryPath, remote, branch)) {
                is ApiResult.Success -> notice("Pulled from ${remote ?: "upstream"}")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun push(remote: String? = null, branch: String? = null) {
        mutate {
            when (val result = git.push(repositoryPath, remote, branch)) {
                is ApiResult.Success -> notice("Pushed to ${remote ?: "upstream"}")

                is ApiResult.Failure -> fail(result.error)
            }
        }
    }

    suspend fun showWorkingDiff(path: String, staged: Boolean) {
        mutex.withLock {
            when (val result = git.diff(repositoryPath, path = path, staged = staged)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        diff = result.value,
                        diffTitle = "${if (staged) "Staged" else "Unstaged"} · $path",
                        error = null,
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(error = describe(result.error))
                }
            }
        }
    }

    suspend fun showRevisionDiff(from: String, to: String) {
        mutex.withLock {
            when (val result = git.diffRevision(repositoryPath, from, to)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        diff = result.value,
                        diffTitle = "$from → $to",
                        error = null,
                    )
                }

                is ApiResult.Failure -> _state.update {
                    it.copy(error = describe(result.error))
                }
            }
        }
    }

    fun clearDiff() {
        _state.update { it.copy(diff = null, diffTitle = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // F36: conflict resolution.

    /** Opens a conflicted file and parses its `<<<<<<<` hunks for editing. */
    suspend fun openConflict(path: String) {
        val service = workspace ?: run {
            _state.update { it.copy(error = "Workspace service is unavailable") }
            return
        }
        mutex.withLock {
            val text = readWholeFile(service, path)
            if (text == null) {
                _state.update { it.copy(error = "Could not read $path") }
                return
            }
            _state.update {
                it.copy(
                    conflictPath = path,
                    conflictText = text,
                    conflictBlocks = parseConflictBlocks(text),
                )
            }
        }
    }

    fun closeConflict() {
        _state.update {
            it.copy(conflictPath = null, conflictText = null, conflictBlocks = emptyList())
        }
    }

    /** Replaces a conflict hunk with the chosen side (or both). */
    fun resolveBlock(index: Int, keep: ConflictKeep) {
        val text = _state.value.conflictText ?: return
        val block = _state.value.conflictBlocks.firstOrNull { it.index == index } ?: return
        val replacement = when (keep) {
            ConflictKeep.OURS -> block.ours
            ConflictKeep.THEIRS -> block.theirs
            ConflictKeep.BOTH -> block.ours + block.theirs
        }
        val lines = text.split('\n').toMutableList()
        val kept = lines.take(block.startLine).toList()
        val newLines = kept + replacement + lines.drop(block.endLine + 1)
        setText(newLines.joinToString("\n"))
    }

    /** Manual raw-text editing of the conflicted file (F36 "editing tools"). */
    fun editConflictText(newText: String) {
        setText(newText)
    }

    /** Persists the resolved text, stages the file and clears the editor. */
    fun saveResolved() {
        val path = _state.value.conflictPath ?: return
        val text = _state.value.conflictText ?: return
        if (_state.value.conflictBlocks.isNotEmpty()) {
            _state.update { it.copy(error = "Resolve all conflict markers first") }
            return
        }
        scope.launch {
            mutex.withLock {
                val service = workspace
                if (service == null) {
                    _state.update { it.copy(error = "Workspace service is unavailable") }
                    return@withLock
                }
                when (
                    val written =
                        service.write(path, text.toByteArray(Charsets.UTF_8), Precondition.None)
                ) {
                    is ApiResult.Failure -> {
                        _state.update { it.copy(error = describe(written.error)) }
                        return@withLock
                    }

                    is ApiResult.Success -> Unit
                }
                when (val staged = git.stage(repositoryPath, listOf(path))) {
                    is ApiResult.Failure -> {
                        _state.update { it.copy(error = describe(staged.error)) }
                        return@withLock
                    }

                    is ApiResult.Success -> Unit
                }
                _state.update {
                    it.copy(
                        conflictPath = null,
                        conflictText = null,
                        conflictBlocks = emptyList(),
                        notice = "Resolved and staged $path",
                    )
                }
                refresh()
            }
        }
    }

    private fun setText(newText: String) {
        _state.update {
            it.copy(conflictText = newText, conflictBlocks = parseConflictBlocks(newText))
        }
    }

    private suspend fun readWholeFile(service: WorkspaceService, path: String): String? {
        val output = ByteArrayOutputStream()
        var offset = 0L
        while (output.size() < MAX_CONFLICT_FILE_BYTES) {
            when (val chunk = service.readRange(path, offset, READ_CHUNK_BYTES)) {
                is ApiResult.Success -> {
                    output.write(chunk.value.bytes)
                    offset += chunk.value.bytes.size
                    if (chunk.value.endOfFile) {
                        return output.toString(Charsets.UTF_8)
                    }
                    if (chunk.value.bytes.isEmpty()) return null
                }

                is ApiResult.Failure -> return null
            }
        }
        return null
    }

    private fun parseConflictBlocks(text: String): List<ConflictBlock> {
        val lines = text.split('\n')
        val blocks = mutableListOf<ConflictBlock>()
        var index = 0
        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("<<<<<<< ")) {
                i++
                continue
            }
            val oursLabel = lines[i].removePrefix("<<<<<<< ").trim()
            val start = i
            var separator = -1
            var end = -1
            var j = i + 1
            while (j < lines.size && separator < 0 && end < 0) {
                when {
                    lines[j] == "=======" -> separator = j
                    lines[j].startsWith(">>>>>>> ") && separator >= 0 -> end = j
                    lines[j].startsWith("<<<<<<< ") -> break // nested/unbalanced; stop
                }
                j++
            }
            if (separator >= 0 && end >= 0) {
                blocks += ConflictBlock(
                    index = index++,
                    startLine = start,
                    endLine = end,
                    oursLabel = oursLabel.ifEmpty { "ours" },
                    theirsLabel = lines[end].removePrefix(">>>>>>> ").trim().ifEmpty { "theirs" },
                    ours = lines.subList(start + 1, separator).toList(),
                    theirs = lines.subList(separator + 1, end).toList(),
                )
                i = end + 1
            } else {
                i++
            }
        }
        return blocks
    }

    private fun mutate(block: suspend () -> MutationOutcome) {
        scope.launch {
            mutex.withLock {
                _state.update { it.copy(busy = true) }
                val outcome = block()
                _state.update {
                    it.copy(
                        busy = false,
                        error = outcome.error,
                        notice = outcome.notice ?: it.notice,
                    )
                }
                if (outcome.success) refresh()
            }
        }
    }

    private class MutationOutcome(
        val success: Boolean,
        val notice: String? = null,
        val error: String? = null,
    )

    private fun ok() = MutationOutcome(success = true)

    private fun notice(text: String) = MutationOutcome(success = true, notice = text)

    private fun fail(error: ApiError): MutationOutcome = MutationOutcome(success = false, error = describe(error))

    private fun describe(error: ApiError): String = error.message?.takeIf { it.isNotBlank() }
        ?: error.code.name
}
