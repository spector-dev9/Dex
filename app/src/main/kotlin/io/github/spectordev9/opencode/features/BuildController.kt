package io.github.spectordev9.opencode.features

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.terminal.PtyEvent
import io.github.spectordev9.opencode.core.terminal.PtyService
import io.github.spectordev9.opencode.core.terminal.PtySession
import io.github.spectordev9.opencode.core.terminal.PtySize
import io.github.spectordev9.opencode.core.terminal.PtySpec
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BuildStatus {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class BuildArtifact(
    val path: String,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
)

data class BuildPreset(
    val id: String,
    val label: String,
    val command: String,
    val requiresAndroidSdk: Boolean = false,
)

data class BuildState(
    val status: BuildStatus = BuildStatus.IDLE,
    val command: String = "",
    val output: String = "",
    val exitCode: Int? = null,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null,
    val artifacts: List<BuildArtifact> = emptyList(),
    val error: String? = null,
) {
    val running: Boolean
        get() = status == BuildStatus.RUNNING
}

/**
 * Runs configured build/test commands in the guest runtime, streams their
 * output, reports exit codes and collects generated artifacts (F68-F70).
 *
 * This never pretends the device can run every Android build locally: the
 * caller decides which presets are eligible by consulting [DeviceCompatibility].
 */
class BuildController(
    private val scope: CoroutineScope,
    private val ptyService: PtyService?,
    private val workspaceDirectory: Path,
    private val environment: suspend () -> Map<String, String> = { emptyMap() },
) {
    private val _state = MutableStateFlow(BuildState())
    val state: StateFlow<BuildState> = _state.asStateFlow()

    private var session: PtySession? = null
    private var job: Job? = null
    private var cancelRequested = false

    val presets: List<BuildPreset> = listOf(
        BuildPreset("unit", "Run unit tests", "./gradlew test --offline", requiresAndroidSdk = true),
        BuildPreset("assemble", "Assemble debug APK", "./gradlew :app:assembleDebug --offline", requiresAndroidSdk = true),
        BuildPreset(
            "gradle-debug",
            "Gradle debug build",
            "if [ -x ./gradlew ]; then ./gradlew :app:assembleDebug; " +
                "else echo 'No Gradle wrapper in this project' >&2; exit 127; fi",
            isCustom = true,
        ),
        BuildPreset("install-git", "Install Git", "apk add --no-cache git"),
        BuildPreset("install-node", "Install Node.js", "apk add --no-cache nodejs npm"),
        BuildPreset("install-python", "Install Python", "apk add --no-cache python3 py3-pip"),
        BuildPreset("npm-test", "npm test", "npm test --silent"),
        BuildPreset("npm-build", "npm run build", "npm run build --silent"),
        BuildPreset("custom", "Custom command", ""),
    )

    fun start(command: String, label: String? = null) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(error = "Build command is empty") }
            return
        }
        if (trimmed.length > MAX_COMMAND_CHARS) {
            _state.update { it.copy(error = "Build command exceeds $MAX_COMMAND_CHARS characters") }
            return
        }
        if (_state.value.running) {
            _state.update { it.copy(error = "A build is already running") }
            return
        }
        val service = ptyService
        if (service == null) {
            _state.update { it.copy(error = "Terminal runtime is unavailable") }
            return
        }
        cancelRequested = false
        val started = System.currentTimeMillis()
        _state.value = BuildState(
            status = BuildStatus.RUNNING,
            command = label?.takeIf { it.isNotBlank() }?.let { "# $it\n$trimmed" } ?: trimmed,
            startedAtEpochMillis = started,
        )
        job = scope.launch {
            val extraEnvironment = runCatching { environment() }.getOrDefault(emptyMap())
            when (val opened = service.open(
                PtySpec(
                    executable = GUEST_SHELL,
                    arguments = listOf("-lc", trimmed),
                    environment = extraEnvironment,
                    workingDirectory = GUEST_WORKSPACE,
                    initialSize = PtySize(columns = 120, rows = 40),
                    name = "build",
                ),
            )) {
                is ApiResult.Failure -> finish(
                    status = BuildStatus.FAILED,
                    exitCode = null,
                    error = opened.error.message ?: "Could not start the build",
                )
                is ApiResult.Success -> {
                    val active = opened.value
                    session = active
                    active.events.collect { event ->
                        when (event) {
                            is PtyEvent.Data -> appendOutput(event.bytes)
                            is PtyEvent.Exit -> finish(
                                status = when {
                                    cancelRequested -> BuildStatus.CANCELLED
                                    event.status.exitCode == 0 -> BuildStatus.SUCCEEDED
                                    else -> BuildStatus.FAILED
                                },
                                exitCode = event.status.exitCode,
                                error = if (event.status.exitCode == null && !cancelRequested) {
                                    "The build process ended without an exit code"
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    fun cancel() {
        if (!_state.value.running) return
        cancelRequested = true
        val active = session
        if (active == null) {
            job?.cancel()
            return
        }
        runCatching { active.signal(SIGINT) }
        runCatching { active.write("exit\n".toByteArray()) }
    }

    fun clear() {
        if (_state.value.running) return
        _state.value = BuildState()
    }

    /** Resolves a reported artifact to a regular file inside the workspace. */
    fun resolveArtifact(artifactPath: String): java.io.File? {
        val root = workspaceDirectory.toAbsolutePath().normalize()
        val candidate = runCatching { root.resolve(artifactPath).normalize() }.getOrNull() ?: return null
        if (!candidate.startsWith(root) || candidate == root) return null
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        return candidate.toFile()
    }

    fun refreshArtifacts() {
        val artifacts = runCatching { scanBuildArtifacts(workspaceDirectory) }.getOrDefault(emptyList())
        _state.update { it.copy(artifacts = artifacts) }
    }

    fun close() {
        job?.cancel()
        job = null
        runCatching { session?.close() }
        session = null
    }

    private fun appendOutput(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val text = String(bytes, Charsets.UTF_8)
        _state.update { current ->
            val combined = current.output + text
            current.copy(
                output = if (combined.length > MAX_OUTPUT_CHARS) {
                    combined.takeLast(MAX_OUTPUT_CHARS)
                } else {
                    combined
                },
            )
        }
    }

    private fun finish(status: BuildStatus, exitCode: Int?, error: String?) {
        session = null
        val artifacts = runCatching { scanBuildArtifacts(workspaceDirectory) }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                status = status,
                exitCode = exitCode,
                finishedAtEpochMillis = System.currentTimeMillis(),
                artifacts = artifacts,
                error = error,
            )
        }
    }

    companion object {
        const val GUEST_SHELL = "/bin/sh"
        const val GUEST_WORKSPACE = "/workspace"
        const val MAX_COMMAND_CHARS = 4_096
        const val MAX_OUTPUT_CHARS = 200_000
        const val SIGINT = 2
        const val MAX_ARTIFACTS = 100
        const val MAX_SCAN_DEPTH = 8
    }
}

/** Finds common build outputs without following symlinks outside [root] (F70). */
internal fun scanBuildArtifacts(root: Path): List<BuildArtifact> {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val normalizedRoot = root.toAbsolutePath().normalize()
    return runCatching {
        Files.walk(normalizedRoot, BuildController.MAX_SCAN_DEPTH).use { stream ->
            val scanned = stream
                .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .filter { path -> !Files.isSymbolicLink(path) }
                .filter { path -> isArtifactPath(path) }
                .limit(BuildController.MAX_ARTIFACTS.toLong() * 4)
                .map { path ->
                    val relative = normalizedRoot.relativize(path.toAbsolutePath().normalize())
                    BuildArtifact(
                        path = relative.toString().replace('\\', '/'),
                        sizeBytes = runCatching { Files.size(path) }.getOrDefault(0L),
                        modifiedAtEpochMillis = runCatching {
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                        }.getOrDefault(0L),
                    )
                }
                .collect(java.util.stream.Collectors.toList())
            scanned.sortedByDescending { it.modifiedAtEpochMillis }
                .take(BuildController.MAX_ARTIFACTS)
        }
    }.getOrDefault(emptyList())
}

private fun isArtifactPath(path: Path): Boolean {
    val name = path.fileName?.toString()?.lowercase() ?: return false
    if (name.endsWith(".apk") || name.endsWith(".aab")) return true
    if (name.endsWith(".jar") || name.endsWith(".zip")) return true
    if (name.endsWith(".aar") || name.endsWith(".whl")) return true
    val text = path.toString().replace('\\', '/').lowercase()
    return (text.contains("/build/outputs/") || text.contains("/dist/")) &&
        (name.endsWith(".tgz") || name.endsWith(".tar.gz") || name.endsWith(".map"))
}
