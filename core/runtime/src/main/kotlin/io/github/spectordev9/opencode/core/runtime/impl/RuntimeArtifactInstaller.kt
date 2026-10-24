package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID

data class InstalledRuntimeArtifact(
    val directory: Path,
    val executable: Path,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Implementations must reject archive entries that escape [destination],
 * including absolute paths and links that resolve outside it.
 */
fun interface RuntimeArchiveExtractor {
    @Throws(IOException::class)
    fun extract(archive: Path, destination: Path)
}

class RuntimeArtifactInstaller(
    private val verifier: RuntimeArtifactVerifier = RuntimeArtifactVerifier(),
    private val extractor: RuntimeArchiveExtractor = OpenCodeTarGzExtractor(),
) {
    fun install(
        archive: Path,
        expectedArchiveSha256: String,
        targetDirectory: Path,
        executableName: String,
        expectedExecutableSha256: String,
    ): ApiResult<InstalledRuntimeArtifact> {
        if (!isSha256(expectedExecutableSha256) || !isSafeFileName(executableName)) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "invalid runtime executable pin"),
            )
        }

        val verifiedArchive = when (val result = verifier.verify(archive, expectedArchiveSha256)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }

        val target = targetDirectory.toAbsolutePath().normalize()
        val parent = target.parent
            ?: return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "runtime target has no parent"))
        val staging = try {
            Files.createDirectories(parent)
            Files.createTempDirectory(parent, ".${target.fileName}.staging-")
        } catch (failure: IOException) {
            return failure("runtime staging directory could not be created", failure)
        }

        try {
            extractor.extract(verifiedArchive.path, staging)
        } catch (failure: IOException) {
            return failedAfterCleanup(staging, "runtime archive could not be extracted", failure)
        }

        val executable = staging.resolve(executableName)
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            return failedAfterCleanup(
                staging,
                "runtime executable is missing or is not a regular file",
                IOException(executable.toString()),
            )
        }

        val executableSha256: String
        val executableSize: Long
        try {
            executableSha256 = sha256(executable)
            executableSize = Files.size(executable)
        } catch (failure: IOException) {
            return failedAfterCleanup(staging, "runtime executable could not be read", failure)
        }
        if (!executableSha256.equals(expectedExecutableSha256, ignoreCase = true)) {
            return failedAfterCleanup(
                staging,
                "runtime executable sha-256 mismatch",
                IOException(executable.toString()),
            )
        }

        val backup = parent.resolve(".${target.fileName}.backup-${UUID.randomUUID()}")
        var previousRuntimeMoved = false
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    return failedAfterCleanup(
                        staging,
                        "runtime target is not a real directory",
                        IOException(target.toString()),
                    )
                }
                Files.move(target, backup, ATOMIC_MOVE)
                previousRuntimeMoved = true
            }
            Files.move(staging, target, ATOMIC_MOVE)
        } catch (failure: IOException) {
            if (previousRuntimeMoved) {
                try {
                    Files.move(backup, target, ATOMIC_MOVE)
                } catch (restoreFailure: IOException) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            return failedAfterCleanup(staging, "runtime could not be atomically installed", failure)
        }

        if (previousRuntimeMoved) {
            try {
                deleteTree(backup)
            } catch (failure: IOException) {
                return ApiResult.Failure(
                    ApiError(
                        ErrorCode.E_RUNTIME_DOWN,
                        "runtime installed, but previous version cleanup failed at $backup: ${failure.message}",
                    ),
                )
            }
        }

        return ApiResult.Success(
            InstalledRuntimeArtifact(
                directory = target,
                executable = target.resolve(executableName),
                sha256 = executableSha256,
                sizeBytes = executableSize,
            ),
        )
    }

    private fun failedAfterCleanup(
        staging: Path,
        message: String,
        cause: IOException,
    ): ApiResult.Failure {
        return try {
            deleteTree(staging)
            failure(message, cause)
        } catch (cleanupFailure: IOException) {
            cause.addSuppressed(cleanupFailure)
            failure("$message; staging cleanup failed", cause)
        }
    }

    private fun failure(message: String, cause: IOException): ApiResult.Failure =
        ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "$message: ${cause.message}"))

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        val paths = Files.walk(path)
        try {
            val iterator = paths.sorted(Comparator.reverseOrder()).iterator()
            while (iterator.hasNext()) Files.delete(iterator.next())
        } finally {
            paths.close()
        }
    }

    private fun isSha256(value: String): Boolean = value.matches(Regex("[a-fA-F0-9]{64}"))

    private fun isSafeFileName(value: String): Boolean =
        value.isNotBlank() && value !in setOf(".", "..") && '/' !in value && '\\' !in value && '\u0000' !in value

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
