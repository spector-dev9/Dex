package io.github.spectordev9.opencode.core.git

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.SecretRef

enum class GitChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    UNTRACKED,
    CONFLICT,
    UNKNOWN,
}

data class GitChange(
    val path: String,
    val oldPath: String? = null,
    val kind: GitChangeKind,
    val staged: Boolean,
    val unstaged: Boolean,
)

data class GitStatus(
    val branch: String?,
    val commit: String?,
    val ahead: Int,
    val behind: Int,
    val changes: List<GitChange>,
)

data class GitDiff(
    val path: String?,
    val patch: String,
    val binary: Boolean,
    val truncated: Boolean,
)

data class GitBranch(
    val name: String,
    val current: Boolean,
    val tracking: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
)

data class GitCommitResult(
    val hash: String,
    val subject: String,
)

data class GitCredential(
    val username: String,
    val secretRef: SecretRef,
)

interface GitService {
    suspend fun status(repositoryPath: String): ApiResult<GitStatus>

    suspend fun diff(
        repositoryPath: String,
        path: String? = null,
        staged: Boolean = false,
    ): ApiResult<GitDiff>

    suspend fun initialize(repositoryPath: String): ApiResult<Unit>

    suspend fun fetch(repositoryPath: String, remote: String? = null): ApiResult<Unit>

    suspend fun pull(
        repositoryPath: String,
        remote: String? = null,
        branch: String? = null,
    ): ApiResult<Unit>

    suspend fun branches(repositoryPath: String): ApiResult<List<GitBranch>>

    suspend fun checkout(repositoryPath: String, ref: String): ApiResult<Unit>

    suspend fun commit(
        repositoryPath: String,
        message: String,
        paths: List<String> = emptyList(),
    ): ApiResult<GitCommitResult>
}
