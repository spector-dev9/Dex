package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.RuntimeId
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessSpec
import io.github.spectordev9.opencode.core.runtime.RuntimeInfo
import io.github.spectordev9.opencode.core.runtime.RuntimeManager
import io.github.spectordev9.opencode.core.runtime.RuntimeProfile
import io.github.spectordev9.opencode.core.runtime.RuntimeState
import io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.inputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

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

private fun fileSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun normalizeRuntimeProfile(value: String): RuntimeProfile =
    when (value.uppercase(Locale.US)) {
        RuntimeProfile.A_DIRECT.name -> RuntimeProfile.A_DIRECT
        RuntimeProfile.B_PROOT.name -> RuntimeProfile.B_PROOT
        else -> throw IllegalArgumentException("unsupported runtime profile: $value")
    }

private fun isSafeRelativeFileName(name: String): Boolean {
    if (name.isBlank() || '\u0000' in name || '/' in name || '\\' in name) return false
    val path = Path.of(name)
    return !path.isAbsolute && path.nameCount == 1 && path.fileName.toString() !in setOf(".", "..")
}

class ManifestRuntimeManager(
    private val rootDirectory: Path,
    private val processLauncher: RuntimeProcessLauncher,
    private val manifestFile: Path = rootDirectory.resolve("manifest.json"),
    override val profile: RuntimeProfile = RuntimeProfile.A_DIRECT,
) : RuntimeManager {
    private val stateFlow = MutableStateFlow(RuntimeState(status = RuntimeStateStatus.ABSENT))
    private val json = runtimeManifestJson

    override val state: StateFlow<RuntimeState> = stateFlow.asStateFlow()
    override suspend fun ensureReady(): ApiResult<RuntimeInfo> {
        return try {
            val manifest = parseManifest(manifestFile)
            require(normalizeRuntimeProfile(manifest.profile) == profile) {
                "manifest profile does not match configured runtime profile"
            }
            val runtimeDir = rootDirectory.toRealPath()
            if (!runtimeDir.exists() || !runtimeDir.isDirectory()) {
                stateFlow.value = RuntimeState(
                    status = RuntimeStateStatus.FAILED,
                    reason = "runtime root does not exist: ${runtimeDir.absolutePathString()}",
                )
                ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime root not found"))
            } else {
                require(manifest.required.all(::isSafeRelativeFileName)) {
                    "manifest contains an unsafe required file path"
                }
                require(isSafeRelativeFileName(manifest.binary)) {
                    "manifest contains an unsafe binary path"
                }
                require(manifest.binary in manifest.required) {
                    "manifest binary is not listed in required files"
                }
                val requiredFiles = manifest.required.map(runtimeDir::resolve)
                val missing = requiredFiles.filter { !it.exists() || !it.isRegularFile() }
                if (missing.isNotEmpty()) {
                    val reason = "missing required runtime files: ${missing.joinToString()}"
                    stateFlow.value = RuntimeState(status = RuntimeStateStatus.FAILED, reason = reason)
                    ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, reason))
                } else {
                    require(requiredFiles.all { it.toRealPath().startsWith(runtimeDir) }) {
                        "runtime file resolves outside runtime root"
                    }
                    val binary = runtimeDir.resolve(manifest.binary)
                    val binarySha = fileSha256(binary)
                    val info = RuntimeInfo(
                        id = RuntimeId("runtime-${manifest.version}"),
                        version = manifest.version,
                        profile = profile,
                        rootPath = runtimeDir.absolutePathString(),
                        manifestSha256 = sha256Hex(manifestFile.readBytes()),
                        openCodeSha256 = binarySha,
                    )
                    stateFlow.value = RuntimeState(status = RuntimeStateStatus.READY, version = manifest.version)
                    ApiResult.Success(info)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            stateFlow.value = RuntimeState(status = RuntimeStateStatus.FAILED, reason = failure.message)
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, failure.message ?: "runtime files could not be read"))
        } catch (failure: IllegalArgumentException) {
            stateFlow.value = RuntimeState(status = RuntimeStateStatus.FAILED, reason = failure.message)
            ApiResult.Failure(ApiError(ErrorCode.E_UNSUPPORTED_VERSION, failure.message ?: "invalid runtime manifest"))
        } catch (failure: SerializationException) {
            stateFlow.value = RuntimeState(
                status = RuntimeStateStatus.FAILED,
                reason = failure.message ?: "invalid runtime manifest",
            )
            ApiResult.Failure(ApiError(ErrorCode.E_UNSUPPORTED_VERSION, failure.message ?: "invalid runtime manifest"))
        }
    }

    override fun spawn(spec: ProcessSpec): ApiResult<ManagedProcess> {
        if (stateFlow.value.status != RuntimeStateStatus.READY) {
            return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime is not ready"))
        }
        return processLauncher.launch(spec)
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
        require(manifest.artifact.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "manifest artifact sha-256 is invalid"
        }
        return manifest
    }
}
