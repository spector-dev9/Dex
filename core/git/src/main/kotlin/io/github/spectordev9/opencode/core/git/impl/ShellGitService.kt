package io.github.spectordev9.opencode.core.git.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.git.GitBranch
import io.github.spectordev9.opencode.core.git.GitChange
import io.github.spectordev9.opencode.core.git.GitChangeKind
import io.github.spectordev9.opencode.core.git.GitCommit
import io.github.spectordev9.opencode.core.git.GitCommitResult
import io.github.spectordev9.opencode.core.git.GitDiff
import io.github.spectordev9.opencode.core.git.GitService
import io.github.spectordev9.opencode.core.git.GitStatus

/**
 * Guest-shell implementation of [GitService] (WS-6, F31–F35).
 *
 * Every user-influenced value (paths, refs, remotes) is validated before it
 * is placed on a command line; commit messages travel over stdin (`git
 * commit -F -`) so they never need shell quoting. Status output uses
 * `--porcelain=v2`, which is stable and machine-parseable. Validation
 * failures map to [ErrorCode.E_PATH_DENIED] instead of throwing.
 */
class ShellGitService(
    private val runner: ShellRunner,
) : GitService {

    override suspend fun status(repositoryPath: String): ApiResult<GitStatus> = guarded {
        val outcome = runIn("cd ${quotePath(repositoryPath)} && git status --porcelain=v2 --branch")
        if (outcome.exitCode != 0) return@guarded failure(outcome)
        ApiResult.Success(parseStatus(outcome.stdout))
    }

    override suspend fun diff(
        repositoryPath: String,
        path: String?,
        staged: Boolean,
    ): ApiResult<GitDiff> = guarded {
        validatePath(path)
        val command = buildString {
            append("cd ${quotePath(repositoryPath)} && git diff ")
            if (staged) append("--staged ")
            if (path != null) append("-- ${quotePath(path)}")
        }
        patchResult(runIn(command), path)
    }

    override suspend fun diffRevision(
        repositoryPath: String,
        from: String,
        to: String,
    ): ApiResult<GitDiff> = guarded {
        validateRef(from)
        validateRef(to)
        patchResult(
            runIn("cd ${quotePath(repositoryPath)} && git diff ${quoteRef(from)} ${quoteRef(to)}"),
            null,
        )
    }

    override suspend fun initialize(repositoryPath: String): ApiResult<Unit> = guarded {
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git init"))
    }

    override suspend fun fetch(repositoryPath: String, remote: String?): ApiResult<Unit> = guarded {
        val remotePart = remote?.let { validateRemote(it); quoteRef(it) } ?: "--all"
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git fetch $remotePart"))
    }

    override suspend fun pull(
        repositoryPath: String,
        remote: String?,
        branch: String?,
    ): ApiResult<Unit> = guarded {
        remote?.let { validateRemote(it) }
        branch?.let { validateRef(it) }
        val spec = listOfNotNull(remote?.let(::quoteRef), branch?.let(::quoteRef)).joinToString(" ")
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git pull $spec"))
    }

    override suspend fun push(
        repositoryPath: String,
        remote: String?,
        branch: String?,
    ): ApiResult<Unit> = guarded {
        remote?.let { validateRemote(it) }
        branch?.let { validateRef(it) }
        val spec = listOfNotNull(remote?.let(::quoteRef), branch?.let(::quoteRef)).joinToString(" ")
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git push $spec"))
    }

    override suspend fun branches(repositoryPath: String): ApiResult<List<GitBranch>> = guarded {
        val outcome = runIn(
            "cd ${quotePath(repositoryPath)} && " +
                "git branch --format=%(refname:short)%09%(HEAD)%09%(upstream:short)%09%(upstream:track)",
        )
        if (outcome.exitCode != 0) return@guarded failure(outcome)
        val list = outcome.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.isEmpty()) return@mapNotNull null
                val name = parts[0].trim()
                if (name.isEmpty()) return@mapNotNull null
                val track = parts.getOrNull(3)
                GitBranch(
                    name = name,
                    current = parts.getOrNull(1)?.trim() == "*",
                    tracking = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
                    ahead = trackField(track, "ahead"),
                    behind = trackField(track, "behind"),
                )
            }
            .toList()
        ApiResult.Success(list)
    }

    override suspend fun checkout(repositoryPath: String, ref: String): ApiResult<Unit> = guarded {
        validateRef(ref)
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git checkout ${quoteRef(ref)}"))
    }

    override suspend fun stage(repositoryPath: String, paths: List<String>): ApiResult<Unit> = guarded {
        if (paths.isEmpty()) return@guarded ApiResult.Success(Unit)
        paths.forEach { validatePath(it) }
        val spec = paths.joinToString(" ") { quotePath(it) }
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git add -- $spec"))
    }

    override suspend fun unstage(repositoryPath: String, paths: List<String>): ApiResult<Unit> = guarded {
        if (paths.isEmpty()) return@guarded ApiResult.Success(Unit)
        paths.forEach { validatePath(it) }
        val spec = paths.joinToString(" ") { quotePath(it) }
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git reset HEAD -- $spec"))
    }

    override suspend fun discardChanges(repositoryPath: String, paths: List<String>): ApiResult<Unit> = guarded {
        if (paths.isEmpty()) return@guarded ApiResult.Success(Unit)
        paths.forEach { validatePath(it) }
        val spec = paths.joinToString(" ") { quotePath(it) }
        unitResult(runIn("cd ${quotePath(repositoryPath)} && git checkout HEAD -- $spec"))
    }

    override suspend fun commit(
        repositoryPath: String,
        message: String,
        paths: List<String>,
    ): ApiResult<GitCommitResult> = guarded {
        if (message.isBlank()) {
            return@guarded ApiResult.Failure(
                ApiError(ErrorCode.E_CONFLICT, "commit message must not be empty"),
            )
        }
        if (paths.isNotEmpty()) {
            when (val staged = stage(repositoryPath, paths)) {
                is ApiResult.Failure -> return@guarded staged
                is ApiResult.Success -> Unit
            }
        }
        val outcome = runIn("cd ${quotePath(repositoryPath)} && git commit -F -", stdin = message)
        if (outcome.exitCode != 0) return@guarded failure(outcome)
        val hash = runIn("cd ${quotePath(repositoryPath)} && git rev-parse --short HEAD")
        val subject = message.lineSequence().firstOrNull()?.take(SUBJECT_LIMIT) ?: message.take(SUBJECT_LIMIT)
        ApiResult.Success(
            GitCommitResult(
                hash = hash.stdout.trim().takeIf { hash.exitCode == 0 && it.isNotEmpty() } ?: "unknown",
                subject = subject,
            ),
        )
    }

    override suspend fun history(repositoryPath: String, limit: Int): ApiResult<List<GitCommit>> = guarded {
        val boundedLimit = limit.coerceIn(1, MAX_HISTORY)
        val outcome = runIn(
            "cd ${quotePath(repositoryPath)} && " +
                "git log -n $boundedLimit --date=raw --format=%H%x1f%s%x1f%an%x1f%at",
        )
        if (outcome.exitCode != 0) return@guarded failure(outcome)
        val commits = outcome.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR)
                val hash = fields.getOrNull(0)?.trim().orEmpty()
                val subject = fields.getOrNull(1).orEmpty()
                if (hash.isEmpty() || subject.isEmpty()) return@mapNotNull null
                GitCommit(
                    hash = hash,
                    subject = subject,
                    author = fields.getOrNull(2).orEmpty(),
                    timeEpochMillis = fields.getOrNull(3)?.trim()?.toLongOrNull()?.times(1_000) ?: 0L,
                )
            }
            .toList()
        ApiResult.Success(commits)
    }

    /** Maps validation failures (IllegalArgumentException) to E_PATH_DENIED. */
    private suspend fun <T> guarded(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (e: IllegalArgumentException) {
        ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, e.message ?: "invalid argument"))
    } catch (e: IllegalStateException) {
        ApiResult.Failure(ApiError(ErrorCode.E_CONFLICT, e.message ?: "invalid state"))
    }

    private suspend fun runIn(command: String, stdin: String? = null): ShellOutcome = try {
        runner.run(command, stdin)
    } catch (t: Throwable) {
        ShellOutcome(exitCode = TIMEOUT_EXIT, stdout = "", stderr = t.message ?: "runner interrupted")
    }

    private fun parseStatus(text: String): GitStatus {
        var branch: String? = null
        var commit: String? = null
        var ahead = 0
        var behind = 0
        val changes = mutableListOf<GitChange>()
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("# branch.oid ") -> commit = line.removePrefix("# branch.oid ").trim()
                line.startsWith("# branch.head ") ->
                    branch = line.removePrefix("# branch.head ").trim().takeIf { it != "(detached)" }

                line.startsWith("# branch.ab ") -> {
                    val numbers = line.removePrefix("# branch.ab ").trim().split(' ')
                    ahead = numbers.firstOrNull { it.startsWith("+") }?.drop(1)?.toIntOrNull() ?: ahead
                    behind = numbers.firstOrNull { it.startsWith("-") }?.drop(1)?.toIntOrNull() ?: behind
                }

                line.startsWith("? ") -> changes += GitChange(
                    path = line.drop(2).trim(),
                    kind = GitChangeKind.UNTRACKED,
                    staged = false,
                    unstaged = true,
                )

                line.startsWith("u ") -> changes += changeLine(line, conflict = true)
                line.startsWith("1 ") -> changes += changeLine(line)
                line.startsWith("2 ") -> changes += changeLine(line, renamed = true)
            }
        }
        return GitStatus(branch, commit, ahead, behind, changes)
    }

    private fun changeLine(line: String, conflict: Boolean = false, renamed: Boolean = false): GitChange {
        // Formats (separator = space): "1 <XY> <sub> <mH> <mI> <mW> <hH> <hI> <path>"
        // "2 ... <path>\t<origPath>"; "u <XY> ... <path>\t<orig1>[\t<orig2>]"
        val body = line.substring(2)
        val xy = body.take(2)
        val stagedStatus = xy[0]
        val unstagedStatus = xy[1]
        val firstTab = body.indexOf('\t')
        val pathFields = if (firstTab >= 0) body.substring(0, firstTab) else body
        val path = pathFields.substringAfterLast(' ', pathFields).trim()
        val origPath = if (renamed && firstTab >= 0) {
            body.substring(firstTab + 1).trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val kind = when {
            conflict -> GitChangeKind.CONFLICT
            renamed -> GitChangeKind.RENAMED
            else -> kindFor(if (unstagedStatus != '.') unstagedStatus else stagedStatus)
        }
        return GitChange(
            path = path,
            oldPath = origPath,
            kind = kind,
            staged = stagedStatus != '.' && stagedStatus != '?',
            unstaged = (unstagedStatus != '.' && unstagedStatus != '?') || conflict,
        )
    }

    private fun kindFor(code: Char): GitChangeKind = when (code) {
        'A', 'C' -> GitChangeKind.ADDED
        'D' -> GitChangeKind.DELETED
        'R' -> GitChangeKind.RENAMED
        'U' -> GitChangeKind.CONFLICT
        'M', 'T' -> GitChangeKind.MODIFIED
        else -> GitChangeKind.UNKNOWN
    }

    private fun patchResult(outcome: ShellOutcome, path: String?): ApiResult<GitDiff> {
        if (outcome.exitCode != 0) return failure(outcome)
        val patch = outcome.stdout
        val binary = patch.lineSequence().any { it.startsWith("Binary files ") }
        val truncated = patch.length > MAX_PATCH_CHARS
        return ApiResult.Success(
            GitDiff(
                path = path,
                patch = if (truncated) patch.take(MAX_PATCH_CHARS) + "\n… truncated" else patch,
                binary = binary,
                truncated = truncated,
            ),
        )
    }

    private fun unitResult(outcome: ShellOutcome): ApiResult<Unit> =
        if (outcome.exitCode != 0) failure(outcome) else ApiResult.Success(Unit)

    private fun failure(outcome: ShellOutcome): ApiResult.Failure = ApiResult.Failure(classify(outcome))

    private fun classify(outcome: ShellOutcome): ApiError {
        val text = outcome.combined
        return when {
            outcome.exitCode == TIMEOUT_EXIT ->
                ApiError(ErrorCode.E_TIMEOUT, "command timed out: ${text.take(200)}")

            outcome.exitCode == 127 ->
                ApiError(ErrorCode.E_RUNTIME_DOWN, "git is not installed in the guest runtime")

            text.contains("not a git repository") ->
                ApiError(ErrorCode.E_PATH_DENIED, "not a git repository")

            text.contains("Authentication failed") ||
                text.contains("could not read Username") ||
                text.contains("Permission denied (publickey)") ->
                ApiError(ErrorCode.E_AUTH, "git credentials missing or rejected")

            text.contains("CONFLICT") ->
                ApiError(ErrorCode.E_CONFLICT, "merge conflict: ${text.take(200)}")

            text.contains("Could not resolve host") ||
                text.contains("Connection refused") ||
                text.contains("Connection timed out") ||
                text.contains("unable to access") ->
                ApiError(ErrorCode.E_UPSTREAM, "network failure: ${text.take(200)}")

            else ->
                ApiError(ErrorCode.E_UPSTREAM, "git exited ${outcome.exitCode}: ${text.take(300)}")
        }
    }

    private fun validatePath(path: String?) {
        require(!path.isNullOrEmpty()) { "path must not be empty" }
        require(!path.startsWith("-")) { "path must not start with '-': $path" }
        require(path.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "path contains control characters: $path"
        }
    }

    private fun validateRef(ref: String) {
        require(ref.length <= 200) { "ref too long" }
        require(refRegex.matches(ref)) { "ref contains unsupported characters: $ref" }
    }

    private fun validateRemote(remote: String) {
        require(remoteRegex.matches(remote)) { "remote name contains unsupported characters: $remote" }
    }

    private fun quotePath(path: String): String = "'${path.replace("'", "'\\''")}'"

    private fun quoteRef(ref: String): String = "'$ref'"

    private fun trackField(text: String?, token: String): Int =
        text?.split(',')?.firstOrNull { it.contains(token) }
            ?.substringAfter(token)?.trim()?.trim('[', ']', ':', ' ')?.toIntOrNull() ?: 0

    private companion object {
        const val TIMEOUT_EXIT = -1
        const val MAX_PATCH_CHARS = 120_000
        const val MAX_HISTORY = 200
        const val SUBJECT_LIMIT = 80
        const val FIELD_SEPARATOR = "\u001f"
        val refRegex = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}")
        val remoteRegex = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,60}")
    }
}
