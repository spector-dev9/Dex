package io.github.spectordev9.opencode.core.runtime

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.ProcessId
import io.github.spectordev9.opencode.core.common.RuntimeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.toPath

private val runtimeManifestJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class RuntimeManifestFile(
    val version: String = "",
    val profile: String = RuntimeProfile.A_DIRECT.name,
    val binary: String = "",
    val loader: String? = null,
    val required: List<String> = emptyList(),
    val artifact: RuntimeManifestArtifact = RuntimeManifestArtifact(),
)

@Serializable
private data class RuntimeManifestArtifact(
    val url: String = "",
    val sha256: String = "",
)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun fileSha256(path: Path): String = sha256Hex(path.readBytes())

private fun normalizeRuntimeProfile(value: String): RuntimeProfile =
    when (value.uppercase(Locale.US)) {
        RuntimeProfile.A_DIRECT.name -> RuntimeProfile.A_DIRECT
        RuntimeProfile.B_PROOT.name -> RuntimeProfile.B_PROOT
        else -> throw IllegalArgumentException("unsupported runtime profile: $value")
    }

private fun validFileName(name: String): Boolean = name.isNotBlank() && !name.contains("..")

class ManifestRuntimeManager(
    private val rootDirectory: Path,
    private val manifestFile: Path = rootDirectory.resolve("manifest.json"),
) : RuntimeManager {
    private val stateFlow = MutableStateFlow(RuntimeState(status = RuntimeStateStatus.ABSENT))
    private val json = runtimeManifestJson

    override val state: StateFlow<RuntimeState> = stateFlow.asStateFlow()
    override val profile: RuntimeProfile = RuntimeProfile.A_DIRECT

    override suspend fun ensureReady(): ApiResult<RuntimeInfo> {
        return try {
            val manifest = parseManifest(manifestFile)
            val runtimeDir = rootDirectory
            if (!runtimeDir.exists() || !runtimeDir.isDirectory()) {
                stateFlow.value = RuntimeState(
                    status = RuntimeStateStatus.FAILED,
                    reason = "runtime root does not exist: ${runtimeDir.absolutePathString()}",
                )
                ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime root not found"))
            } else {
                val required = manifest.required.filter(::validFileName)
                val missing = required.filter { !runtimeDir.resolve(it).exists() || !runtimeDir.resolve(it).isRegularFile() }
                if (missing.isNotEmpty()) {
                    val reason = "missing required runtime files: ${missing.joinToString() }"
                    stateFlow.value = RuntimeState(status = RuntimeStateStatus.FAILED, reason = reason)
                    ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, reason))
                } else {
                    val binary = runtimeDir.resolve(manifest.binary)
                    val binarySha = fileSha256(binary)
                    val info = RuntimeInfo(
                        id = RuntimeId("runtime-${manifest.version}"),
                        version = manifest.version,
                        profile = normalizeRuntimeProfile(manifest.profile),
                        rootPath = runtimeDir.absolutePathString(),
                        manifestSha256 = sha256Hex(manifestFile.readBytes()),
                        openCodeSha256 = binarySha,
                    )
                    stateFlow.value = RuntimeState(status = RuntimeStateStatus.READY, version = manifest.version)
                    ApiResult.Success(info)
                }
            }
        } catch (throwable: Throwable) {
            stateFlow.value = RuntimeState(
                status = RuntimeStateStatus.FAILED,
                reason = throwable.message ?: throwable.javaClass.simpleName,
            )
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, throwable.message ?: throwable.javaClass.simpleName))
        }
    }

    override fun spawn(spec: ProcessSpec): ApiResult<ManagedProcess> {
        val workingDirectory = java.io.File(spec.workingDirectory)
        if (!workingDirectory.exists() || !workingDirectory.isDirectory) {
            return ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, "working directory not found: ${spec.workingDirectory}"))
        }

        return try {
            val processBuilder = ProcessBuilder(spec.executable)
                .directory(workingDirectory)
                .redirectErrorStream(true)
            processBuilder.environment().putAll(spec.environment)
            if (spec.arguments.isNotEmpty()) {
                processBuilder.command().addAll(spec.arguments)
            }
            val process = processBuilder.start()
            ApiResult.Success(
                ProcessHandleManagedProcess(
                    id = ProcessId("pid-${process.pid()}"),
                    process = process,
                ),
            )
        } catch (throwable: Throwable) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "failed to spawn process: ${throwable.message}"))
        }
    }

    private fun parseManifest(path: Path): RuntimeManifestFile {
        if (!path.exists() || !path.isRegularFile()) {
            throw IllegalArgumentException("missing manifest: ${path.absolutePathString()}")
        }
        val text = path.readText(StandardCharsets.UTF_8)
        val manifest = json.decodeFromString(RuntimeManifestFile.serializer(), text)
        require(manifest.version.isNotBlank()) { "manifest version is blank" }
        require(manifest.binary.isNotBlank()) { "manifest binary is blank" }
        require(manifest.required.isNotEmpty()) { "manifest requires at least one runtime file" }
        return manifest
    }
}

class ProcessHandleManagedProcess(
    override val id: ProcessId,
    private val process: Process,
) : ManagedProcess {
    override val pid: Long?
        get() = process.pid().toLong()

    override val isAlive: Boolean
        get() = process.isAlive

    override fun signal(signal: Int): ApiResult<Unit> =
        try {
            process.destroyForcibly()
            ApiResult.Success(Unit)
        } catch (_: Throwable) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "failed to signal process"))
        }

    override suspend fun waitFor(): ProcessExit {
        val exitCode = process.waitFor()
        return ProcessExit(exitCode = exitCode, signal = null)
    }

    override suspend fun terminate(graceMillis: Long): ApiResult<Unit> {
        process.destroy()
        return if (process.waitFor(graceMillis, TimeUnit.MILLISECONDS)) {
            ApiResult.Success(Unit)
        } else {
            process.destroyForcibly()
            ApiResult.Success(Unit)
        }
    }
}
