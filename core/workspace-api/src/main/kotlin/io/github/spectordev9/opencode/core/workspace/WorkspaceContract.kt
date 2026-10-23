package io.github.spectordev9.opencode.core.workspace

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.TrashId

enum class EntryKind {
    FILE,
    DIRECTORY,
    SYMLINK,
    OTHER,
}

data class Entry(
    val path: String,
    val name: String,
    val kind: EntryKind,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val symlinkTarget: String? = null,
)

data class Chunk(
    val offset: Long,
    val bytes: ByteArray,
    val endOfFile: Boolean,
)

sealed interface Precondition {
    data object None : Precondition

    data class Match(
        val sizeBytes: Long,
        val modifiedAtEpochMillis: Long? = null,
        val sha256: String? = null,
    ) : Precondition
}

data class WriteReceipt(
    val path: String,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val sha256: String,
)

interface WorkspaceService {
    suspend fun list(path: String): ApiResult<List<Entry>>

    suspend fun stat(path: String): ApiResult<Entry>

    suspend fun readRange(path: String, offset: Long, length: Int): ApiResult<Chunk>

    suspend fun write(path: String, data: ByteArray, expect: Precondition): ApiResult<WriteReceipt>

    suspend fun mkdir(path: String): ApiResult<Entry>

    suspend fun rename(from: String, to: String): ApiResult<Entry>

    suspend fun move(from: String, to: String): ApiResult<Entry>

    suspend fun trash(path: String): ApiResult<TrashId>

    suspend fun restore(id: TrashId): ApiResult<Entry>

    suspend fun search(path: String, query: String, limit: Int = 100): ApiResult<List<Entry>>
}
