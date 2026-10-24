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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.path.inputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

private val runtimeManifestJson = Json { ignoreUnknownKeys = true }
private val RUNTIME_SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")

@Serializable
private data class RuntimeManifestFile(
    val version: String = "",
    val profile: String = RuntimeProfile.A_DIRECT.name,
    val binary: String = "",
    val binarySha256: String = "",
    val loader: String? = null,
    val required: List<String> = emptyList(),
    val artifact: RuntimeManifestArtifact = RuntimeManifestArtifact(),
    val alpine: RuntimeManifestAlpine? = null,
    val runtimeLibraries: List<String> = emptyList(),
    val alpineRuntimePackages: List<RuntimeManifestAlpinePackage> = emptyList(),
)

@Serializable
private data class RuntimeManifestArtifact(
    val url: String = "",
    val sha256: String = "",
)

@Serializable
private data class RuntimeManifestAlpine(
    val version: String = "",
    val url: String = "",
    val sha256: String = "",
)

@Serializable
private data class RuntimeManifestAlpinePackage(
    val name: String = "",
    val version: String = "",
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
    private val installer: RuntimeArtifactInstaller = RuntimeArtifactInstaller(),
    private val alpineInstaller: AlpineRootfsInstaller = AlpineRootfsInstaller(),
    private val alpineLibraryInstaller: AlpineRuntimeLibraryInstaller = AlpineRuntimeLibraryInstaller(),
    private val artifactDownloader: RuntimeArtifactDownloader = RuntimeArtifactDownloader(),
    private val installedDirectory: Path = rootDirectory.resolve("environment"),
) : RuntimeManager {
    private val stateFlow = MutableStateFlow(RuntimeState(status = RuntimeStateStatus.ABSENT))
    private val json = runtimeManifestJson
    private val alpinePackageLibraries = mapOf(
        "libgcc" to "libgcc_s.so.1",
        "libstdc++" to "libstdc++.so.6",
    )

    override val state: StateFlow<RuntimeState> = stateFlow.asStateFlow()

    suspend fun installPinnedArtifact(): ApiResult<RuntimeInfo> {
        val manifest = try {
            parseManifest(manifestFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            return failed(RuntimeStateStatus.FAILED, failure.message ?: "runtime manifest could not be read", ErrorCode.E_RUNTIME_DOWN)
        } catch (failure: IllegalArgumentException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "invalid runtime manifest",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        } catch (failure: SerializationException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "invalid runtime manifest",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        }
        val archivePath = rootDirectory.resolve("downloads")
            .resolve("opencode-${manifest.artifact.sha256.lowercase(Locale.US)}.tar.gz")
        val downloaded = withContext(Dispatchers.IO) {
            artifactDownloader.download(
                manifest.artifact.url,
                manifest.artifact.sha256,
                archivePath,
            )
        }
        return when (downloaded) {
            is ApiResult.Failure -> {
                stateFlow.value = RuntimeState(
                    status = RuntimeStateStatus.FAILED,
                    reason = downloaded.error.message,
                    version = manifest.version,
                )
                downloaded
            }
            is ApiResult.Success -> installArtifact(downloaded.value)
        }
    }

    suspend fun installArtifact(archive: Path): ApiResult<RuntimeInfo> {
        val manifest = try {
            parseManifest(manifestFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "runtime manifest could not be read",
                ErrorCode.E_RUNTIME_DOWN,
            )
        } catch (failure: IllegalArgumentException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "invalid runtime manifest",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        } catch (failure: SerializationException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "invalid runtime manifest",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        }
        val manifestProfile = try {
            normalizeRuntimeProfile(manifest.profile)
        } catch (failure: IllegalArgumentException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "unsupported runtime profile",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        }
        if (manifestProfile != profile) {
            return failed(
                RuntimeStateStatus.FAILED,
                "manifest profile does not match configured runtime profile",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        }
        if (!isSafeRelativeFileName(manifest.binary) || manifest.binary !in manifest.required) {
            return failed(
                RuntimeStateStatus.FAILED,
                "manifest binary is not a safe required file",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        }

        stateFlow.value = RuntimeState(status = RuntimeStateStatus.INSTALLING, version = manifest.version)
        val result = withContext(Dispatchers.IO) {
            installer.install(
                archive = archive,
                expectedArchiveSha256 = manifest.artifact.sha256,
                targetDirectory = versionDirectory(manifest.binarySha256),
                executableName = manifest.binary,
                expectedExecutableSha256 = manifest.binarySha256,
            )
        }
        if (result is ApiResult.Failure) {
            ensureReady()
            return result
        }
        try {
            activateVersion(manifest.binarySha256)
        } catch (failure: IOException) {
            val activationError = ApiError(
                ErrorCode.E_RUNTIME_DOWN,
                "runtime was installed but could not be activated: ${failure.message}",
            )
            return if (ensureReady() is ApiResult.Success) {
                ApiResult.Failure(activationError)
            } else {
                failed(
                    RuntimeStateStatus.FAILED,
                    activationError.message ?: "runtime could not be activated",
                    activationError.code,
                )
            }
        }
        return ensureReady()
    }

    suspend fun installAlpineRootfs(archive: Path): ApiResult<Path> {
        val manifest = try {
            parseManifest(manifestFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, failure.message ?: "runtime manifest could not be read"),
            )
        } catch (failure: IllegalArgumentException) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, failure.message ?: "invalid runtime manifest"),
            )
        } catch (failure: SerializationException) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, failure.message ?: "invalid runtime manifest"),
            )
        }
        val alpine = manifest.alpine
            ?: return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "manifest does not pin an Alpine rootfs"),
            )
        if (alpine.version.isBlank() || !alpine.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            return ApiResult.Failure(ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "invalid Alpine rootfs pin"))
        }

        val previousState = stateFlow.value
        stateFlow.value = RuntimeState(status = RuntimeStateStatus.INSTALLING, version = alpine.version)
        return try {
            when (
                val result = withContext(Dispatchers.IO) {
                    alpineInstaller.install(
                        archive = archive,
                        expectedArchiveSha256 = alpine.sha256,
                        targetDirectory = rootDirectory.resolve("rootfs-versions")
                            .resolve(alpine.sha256.lowercase(Locale.US)),
                    )
                }
            ) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> ApiResult.Success(result.value.directory)
            }
        } finally {
            stateFlow.value = previousState
        }
    }

    suspend fun installPinnedAlpineRootfs(): ApiResult<Path> {
        val manifest = try {
            parseManifest(manifestFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_RUNTIME_DOWN, failure.message ?: "runtime manifest could not be read"),
            )
        } catch (failure: IllegalArgumentException) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, failure.message ?: "invalid runtime manifest"),
            )
        } catch (failure: SerializationException) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, failure.message ?: "invalid runtime manifest"),
            )
        }
        val alpine = manifest.alpine
            ?: return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "manifest does not pin an Alpine rootfs"),
            )
        if (alpine.version.isBlank() || !alpine.sha256.matches(RUNTIME_SHA256_PATTERN)) {
            return ApiResult.Failure(ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "invalid Alpine rootfs pin"))
        }
        val archivePath = rootDirectory.resolve("downloads")
            .resolve("alpine-${alpine.sha256.lowercase(Locale.US)}.tar.gz")
        return when (
            val downloaded = withContext(Dispatchers.IO) {
                artifactDownloader.download(alpine.url, alpine.sha256, archivePath)
            }
        ) {
            is ApiResult.Failure -> downloaded
            is ApiResult.Success -> installAlpineRootfs(downloaded.value)
        }
    }

    suspend fun installPinnedRuntime(): ApiResult<RuntimeInfo> {
        val manifest = try {
            parseManifest(manifestFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "runtime manifest could not be read",
                ErrorCode.E_RUNTIME_DOWN,
            )
        } catch (failure: IllegalArgumentException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "invalid runtime manifest",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        } catch (failure: SerializationException) {
            return failed(
                RuntimeStateStatus.FAILED,
                failure.message ?: "invalid runtime manifest",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        }

        val previousState = stateFlow.value
        stateFlow.value = RuntimeState(RuntimeStateStatus.INSTALLING, version = manifest.version)
        return try {
            installPinnedRuntimeArtifacts(manifest)
        } catch (cancelled: CancellationException) {
            stateFlow.value = previousState
            throw cancelled
        }
    }

    private suspend fun installPinnedRuntimeArtifacts(
        manifest: RuntimeManifestFile,
    ): ApiResult<RuntimeInfo> {
        val packageNames = manifest.alpineRuntimePackages.map { it.name }
        val libraryNames = packageNames.map { alpinePackageLibraries[it] }
        if (packageNames.toSet().size != packageNames.size ||
            libraryNames.any { it == null } ||
            libraryNames.filterNotNull().toSet() != manifest.runtimeLibraries.toSet() ||
            manifest.runtimeLibraries.toSet().size != manifest.runtimeLibraries.size
        ) {
            return failed(
                RuntimeStateStatus.FAILED,
                "manifest Alpine runtime package list does not match required runtime libraries",
                ErrorCode.E_UNSUPPORTED_VERSION,
            )
        }

        val installedRootfs = when (val result = installPinnedAlpineRootfs()) {
            is ApiResult.Failure -> {
                stateFlow.value = RuntimeState(
                    RuntimeStateStatus.FAILED,
                    result.error.message,
                    manifest.alpine?.version,
                )
                return result
            }
            is ApiResult.Success -> result.value
        }
        for (packagePin in manifest.alpineRuntimePackages) {
            if (packagePin.version.isBlank() || !packagePin.sha256.matches(RUNTIME_SHA256_PATTERN)) {
                return failed(
                    RuntimeStateStatus.FAILED,
                    "invalid Alpine runtime package pin: ${packagePin.name}",
                    ErrorCode.E_UNSUPPORTED_VERSION,
                )
            }
            val packagePath = rootDirectory.resolve("downloads")
                .resolve("alpine-${packagePin.name}-${packagePin.sha256.lowercase(Locale.US)}.apk")
            val downloaded = withContext(Dispatchers.IO) {
                artifactDownloader.download(packagePin.url, packagePin.sha256, packagePath)
            }
            when (downloaded) {
                is ApiResult.Failure -> {
                    stateFlow.value = RuntimeState(
                        RuntimeStateStatus.FAILED,
                        downloaded.error.message,
                        manifest.alpine?.version,
                    )
                    return downloaded
                }
                is ApiResult.Success -> {
                    val installed = withContext(Dispatchers.IO) {
                        alpineLibraryInstaller.install(
                            archive = downloaded.value,
                            expectedArchiveSha256 = packagePin.sha256,
                            packageName = packagePin.name,
                            libraryName = alpinePackageLibraries.getValue(packagePin.name),
                            rootfsDirectory = installedRootfs,
                        )
                    }
                    if (installed is ApiResult.Failure) {
                        stateFlow.value = RuntimeState(
                            RuntimeStateStatus.FAILED,
                            installed.error.message,
                            manifest.alpine?.version,
                        )
                        return installed
                    }
                }
            }
        }
        return installPinnedArtifact()
    }

    override suspend fun ensureReady(): ApiResult<RuntimeInfo> {
        return try {
            val manifest = parseManifest(manifestFile)
            require(normalizeRuntimeProfile(manifest.profile) == profile) {
                "manifest profile does not match configured runtime profile"
            }
            val runtimeDir = resolveInstalledDirectory(manifest.binarySha256).toRealPath()
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
                    if (!binarySha.equals(manifest.binarySha256, ignoreCase = true)) {
                        val reason = "OpenCode binary sha-256 mismatch"
                        stateFlow.value = RuntimeState(status = RuntimeStateStatus.FAILED, reason = reason)
                        ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, reason))
                    } else {
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
        require(manifest.binarySha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "manifest binary sha-256 is invalid"
        }
        require(manifest.artifact.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "manifest artifact sha-256 is invalid"
        }
        return manifest
    }

    private fun resolveInstalledDirectory(expectedBinarySha256: String): Path {
        val activeVersionFile = rootDirectory.resolve("active-runtime")
        if (!Files.exists(activeVersionFile, LinkOption.NOFOLLOW_LINKS)) {
            return installedDirectory
        }
        require(!Files.isSymbolicLink(activeVersionFile) &&
            Files.isRegularFile(activeVersionFile, LinkOption.NOFOLLOW_LINKS)) {
            "active runtime marker is not a regular file"
        }
        val activeHash = Files.readString(activeVersionFile, StandardCharsets.UTF_8)
        require(activeHash.matches(Regex("[a-fA-F0-9]{64}")) &&
            activeHash.equals(expectedBinarySha256, ignoreCase = true)) {
            "active runtime marker does not match the pinned executable"
        }
        val root = rootDirectory.toRealPath()
        val runtimeDirectory = versionDirectory(activeHash).toRealPath()
        require(runtimeDirectory.startsWith(root)) {
            "active runtime directory resolves outside the runtime root"
        }
        return runtimeDirectory
    }

    private fun versionDirectory(binarySha256: String): Path =
        rootDirectory.resolve("versions").resolve(binarySha256.lowercase(Locale.US))

    private fun activateVersion(binarySha256: String) {
        Files.createDirectories(rootDirectory)
        val marker = rootDirectory.resolve("active-runtime")
        val temporaryMarker = Files.createTempFile(rootDirectory, ".active-runtime-", ".tmp")
        try {
            Files.writeString(temporaryMarker, binarySha256.lowercase(Locale.US), StandardCharsets.UTF_8)
            Files.move(
                temporaryMarker,
                marker,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporaryMarker)
        }
    }

    private fun failed(
        status: RuntimeStateStatus,
        reason: String,
        code: ErrorCode,
    ): ApiResult.Failure {
        stateFlow.value = RuntimeState(status = status, reason = reason)
        return ApiResult.Failure(ApiError(code, reason))
    }
}
