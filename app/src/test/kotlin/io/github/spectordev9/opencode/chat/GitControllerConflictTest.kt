package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.app.bridge.FakeGitService
import io.github.spectordev9.opencode.app.bridge.FakeWorkspaceService
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.workspace.Precondition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class GitControllerConflictTest {

    private val conflictFile = listOf(
        "line before",
        "<<<<<<< HEAD",
        "ours line",
        "=======",
        "theirs line",
        ">>>>>>> feature/x",
        "line after",
        "",
    ).joinToString("\n")

    private fun controller(): Pair<GitController, FakeWorkspaceService> {
        val workspace = FakeWorkspaceService()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val git = GitController(
            scope = scope,
            git = FakeGitService(),
            repositoryPath = "/workspace",
            workspace = workspace,
        )
        return git to workspace
    }

    private fun seed(path: String, content: String, workspace: FakeWorkspaceService) {
        val stored = runBlocking { workspace.write(path, content.toByteArray(), Precondition.None) }
        require(stored is ApiResult.Success)
    }

    @Test
    fun `openConflict parses both hunks`() = runBlocking {
        val (git, workspace) = controller()
        seed("merged.txt", conflictFile, workspace)

        git.openConflict("merged.txt")

        val state = git.state.value
        assertEquals(1, state.conflictBlocks.size, "one conflict block expected")
        val block = state.conflictBlocks.single()
        assertEquals(listOf("ours line"), block.ours)
        assertEquals(listOf("theirs line"), block.theirs)
        assertEquals("HEAD", block.oursLabel)
        assertEquals("feature/x", block.theirsLabel)
        scope.cancel()
    }

    @Test
    fun `resolveBlock keeps chosen side and strips markers`() = runBlocking {
        val (git, workspace) = controller()
        seed("merged.txt", conflictFile, workspace)

        git.openConflict("merged.txt")
        git.resolveBlock(0, ConflictKeep.OURS)

        val text = git.state.value.conflictText!!
        assertTrue(!text.contains("<<<<<<<") && !text.contains("=======") && !text.contains(">>>>>>>"))
        assertTrue(text.contains("ours line"))
        assertTrue(!text.contains("theirs line"))
        assertTrue(text.contains("line before") && text.contains("line after"))
        scope.cancel()
    }

    @Test
    fun `saveResolved writes file and stages it`() = runBlocking {
        val (git, workspace) = controller()
        seed("merged.txt", conflictFile, workspace)
        git.openConflict("merged.txt")
        git.resolveBlock(0, ConflictKeep.THEIRS)

        git.saveResolved()
        awaitState(git, timeoutMs = 2_000) { it.conflictPath == null && it.notice != null }

        val state = git.state.value
        assertEquals("Resolved and staged merged.txt", state.notice)
        val saved = runBlocking {
            (workspace.readRange("merged.txt", 0, 64 * 1024) as ApiResult.Success).value.bytes
        }.toString(Charsets.UTF_8)
        assertTrue(!saved.contains("<<<<<<<"))
        assertTrue(saved.contains("theirs line"))
        scope.cancel()
    }

    @Test
    fun `saveResolved is blocked while markers remain`() = runBlocking {
        val (git, workspace) = controller()
        seed("merged.txt", conflictFile, workspace)
        git.openConflict("merged.txt")

        // Nothing resolved: save must not clear the editor.
        git.saveResolved()

        awaitState(git, timeoutMs = 2_000) { it.conflictPath == null || it.error != null || it.notice != null }
        // The editor stays open because markers remain in the text.
        assertEquals("merged.txt", git.state.value.conflictPath)
        assertEquals(1, git.state.value.conflictBlocks.size)
        scope.cancel()
    }

    private fun awaitState(
        git: GitController,
        timeoutMs: Long,
        predicate: (GitUiState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(git.state.value)) return
            Thread.sleep(10)
        }
        error("state did not converge: ${git.state.value}")
    }
}
