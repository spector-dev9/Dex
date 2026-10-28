package io.github.spectordev9.opencode.core.git.impl

/**
 * One-shot command execution boundary for [ShellGitService].
 *
 * The app wires this to a guest (PRoot) shell; tests inject canned outcomes.
 * Input is always a full argv-free shell command string produced by the
 * service itself, with user-influenced values validated and, where needed,
 * passed through stdin.
 */
interface ShellRunner {
    suspend fun run(command: String, stdin: String? = null): ShellOutcome
}

data class ShellOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val combined: String
        get() = (stdout + stderr).trim()
}
