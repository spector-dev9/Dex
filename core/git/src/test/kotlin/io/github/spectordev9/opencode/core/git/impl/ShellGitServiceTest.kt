package io.github.spectordev9.opencode.core.git.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.git.GitChangeKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellGitServiceTest {

    private class FakeRunner(
        private val outcomes: Map<String, ShellOutcome>,
        private val default: ShellOutcome = ShellOutcome(0, "", ""),
    ) : ShellRunner {
        val commands = mutableListOf<String>()

        override suspend fun run(command: String, stdin: String?): ShellOutcome {
            commands += command
            return outcomes.entries.firstOrNull { command.contains(it.key) }?.value ?: default
        }
    }

    private fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Success)?.value

    private fun ApiResult<*>.errorOrNull() = (this as? ApiResult.Failure)?.error

    private fun service(runner: ShellRunner) = ShellGitService(runner)

    @Test
    fun statusParsesPorcelainV2() = runBlocking {
        val stdout = listOf(
            "# branch.oid deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
            "# branch.head main",
            "# branch.upstream origin/main",
            "# branch.ab +2 -1",
            "1 .M .N N 100644 100644 100644 abc def src/Main.kt",
            "1 A. N 100644 000000 100644 000 new/File.kt",
            "2 RM N 100644 100644 100644 abc def old-name.kt\trenamed-new.kt",
            "? stray.txt",
            "",
        ).joinToString("\n")
        val runner = FakeRunner(mapOf("git status" to ShellOutcome(0, stdout, "")))
        val status = service(runner).status("/repo").valueOrNull()!!
        assertEquals("main", status.branch)
        assertEquals("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef", status.commit)
        assertEquals(2, status.ahead)
        assertEquals(1, status.behind)
        assertEquals(4, status.changes.size)
        assertEquals(GitChangeKind.MODIFIED, status.changes[0].kind)
        assertEquals(false, status.changes[0].staged)
        assertEquals(true, status.changes[0].unstaged)
        assertEquals(GitChangeKind.ADDED, status.changes[1].kind)
        assertEquals(true, status.changes[1].staged)
        assertEquals(GitChangeKind.RENAMED, status.changes[2].kind)
        assertEquals("renamed-new.kt", status.changes[2].path)
        assertEquals("old-name.kt", status.changes[2].oldPath)
        assertEquals(GitChangeKind.UNTRACKED, status.changes[3].kind)
        Unit
    }

    @Test
    fun conflictEntryIsClassified() = runBlocking {
        val stdout = "u UU N 000000 000000 000000 000 000 file.txt\tours.txt\ttheirs.txt"
        val runner = FakeRunner(mapOf("git status" to ShellOutcome(0, stdout, "")))
        val status = service(runner).status("/repo").valueOrNull()!!
        assertEquals(GitChangeKind.CONFLICT, status.changes.single().kind)
        assertEquals("file.txt", status.changes.single().path)
        Unit
    }

    @Test
    fun notARepositoryMapsToPathDenied() = runBlocking {
        val runner = FakeRunner(mapOf("git status" to ShellOutcome(128, "", "fatal: not a git repository")))
        val error = service(runner).status("/repo").errorOrNull()!!
        assertEquals(ErrorCode.E_PATH_DENIED, error.code)
        Unit
    }

    @Test
    fun missingGitBinaryIsDiagnosedSeparately() = runBlocking {
        val runner = FakeRunner(mapOf<String, ShellOutcome>(), default = ShellOutcome(127, "", "sh: git: not found"))
        val error = service(runner).status("/repo").errorOrNull()!!
        assertEquals(ErrorCode.E_RUNTIME_DOWN, error.code)
        assertTrue(error.message.orEmpty().contains("not installed"))
        Unit
    }

    @Test
    fun authFailureMapsToEAuth() = runBlocking {
        val runner = FakeRunner(
            mapOf("git pull" to ShellOutcome(128, "", "fatal: Authentication failed for 'https://x'")),
        )
        val error = service(runner).pull("/repo").errorOrNull()!!
        assertEquals(ErrorCode.E_AUTH, error.code)
        Unit
    }

    @Test
    fun commitTravelsViaStdin() = runBlocking {
        val runner = FakeRunner(mapOf("git commit" to ShellOutcome(0, "", "")))
        val commit = service(runner).commit("/repo", "feat: add thing", listOf("a.kt")).valueOrNull()!!
        assertEquals("feat: add thing", commit.subject)
        assertTrue(runner.commands.any { it.contains("git add -- ") })
        assertTrue(runner.commands.any { it.contains("git commit -F -") })
        Unit
    }

    @Test
    fun emptyCommitMessageRejected() = runBlocking {
        val runner = FakeRunner(mapOf<String, ShellOutcome>())
        val error = service(runner).commit("/repo", "   ").errorOrNull()!!
        assertEquals(ErrorCode.E_CONFLICT, error.code)
        assertEquals(emptyList(), runner.commands)
        Unit
    }

    @Test
    fun optionInjectionRejected() = runBlocking {
        val runner = FakeRunner(mapOf<String, ShellOutcome>())
        val error = service(runner).diff("/repo", path = "--upload-pack=evil").errorOrNull()!!
        assertEquals(ErrorCode.E_PATH_DENIED, error.code)
        val refError = service(runner).checkout("/repo", ref = "--exec=evil").errorOrNull()!!
        assertEquals(ErrorCode.E_PATH_DENIED, refError.code)
        assertEquals(emptyList(), runner.commands)
        Unit
    }

    @Test
    fun historyParsesLogFormat() = runBlocking {
        val sep = "\u001f"
        val stdout = listOf(
            "aaa111${sep}first commit${sep}Alice${sep}1700000000",
            "bbb222${sep}second commit${sep}Bob${sep}1699990000",
        ).joinToString("\n")
        val runner = FakeRunner(mapOf("git log" to ShellOutcome(0, stdout, "")))
        val commits = service(runner).history("/repo").valueOrNull()!!
        assertEquals(2, commits.size)
        assertEquals("first commit", commits[0].subject)
        assertEquals("Alice", commits[0].author)
        assertEquals(1_700_000_000_000L, commits[0].timeEpochMillis)
        Unit
    }

    @Test
    fun diffTruncationAndBinaryFlags() = runBlocking {
        val big = "x".repeat(200_000)
        val runner = FakeRunner(
            mapOf(
                "git diff  -- 'big'" to ShellOutcome(0, big, ""),
                "git diff  -- 'binary'" to ShellOutcome(0, "Binary files a/x and b/x differ\n", ""),
            ),
        )
        val truncated = service(runner).diff("/repo", path = "big").valueOrNull()!!
        assertTrue(truncated.truncated)
        val binary = service(runner).diff("/repo", path = "binary").valueOrNull()!!
        assertTrue(binary.binary)
        assertEquals(false, binary.truncated)
        Unit
    }
}
