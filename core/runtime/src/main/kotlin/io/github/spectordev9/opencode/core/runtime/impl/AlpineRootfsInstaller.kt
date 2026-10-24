package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.Comparator

data class InstalledAlpineRootfs(
    val directory: Path,
    val archiveSha256: String,
)

class AlpineRootfsInstaller(
    private val verifier: RuntimeArtifactVerifier = RuntimeArtifactVerifier(),
    private val extractor: RuntimeArchiveExtractor = AlpineRootfsTarGzExtractor(),
) {
    fun install(
        archive: Path,
        expectedArchiveSha256: String,
        targetDirectory: Path,
    ): ApiResult<InstalledAlpineRootfs> {
        val verifiedArchive = when (val result = verifier.verify(archive, expectedArchiveSha256)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }

        val target = targetDirectory.toAbsolutePath().normalize()
        val parent = target.parent
            ?: return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "rootfs target has no parent"))
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            val marker = target.resolve(ARCHIVE_DIGEST_FILE)
            if (Files.isSymbolicLink(target) ||
                !Files.isDirectory(target, NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(marker) ||
                !Files.isRegularFile(marker, NOFOLLOW_LINKS)
            ) {
                return ApiResult.Failure(
                    ApiError(ErrorCode.E_RUNTIME_DOWN, "existing rootfs version directory is invalid"),
                )
            }
            return try {
                if (Files.readString(marker, Charsets.US_ASCII) != verifiedArchive.sha256) {
                    ApiResult.Failure(
                        ApiError(ErrorCode.E_RUNTIME_DOWN, "existing rootfs directory has a different archive digest"),
                    )
                } else {
                    validateRootfs(target)
                    ApiResult.Success(InstalledAlpineRootfs(target, verifiedArchive.sha256))
                }
            } catch (ioFailure: IOException) {
                failure("existing rootfs archive marker could not be read", ioFailure)
            }
        }

        val staging = try {
            Files.createDirectories(parent)
            Files.createTempDirectory(parent, ".${target.fileName}.staging-")
        } catch (failure: IOException) {
            return failure("rootfs staging directory could not be created", failure)
        }

        try {
            extractor.extract(verifiedArchive.path, staging)
            validateRootfs(staging)
            Files.newOutputStream(staging.resolve(ARCHIVE_DIGEST_FILE), CREATE_NEW, WRITE).buffered().use {
                it.write(verifiedArchive.sha256.toByteArray(Charsets.US_ASCII))
            }
            Files.move(staging, target, ATOMIC_MOVE)
        } catch (unsupported: UnsupportedOperationException) {
            return failedAfterCleanup(
                staging,
                "rootfs filesystem operation is unsupported",
                IOException(unsupported.message, unsupported),
            )
        } catch (failure: IOException) {
            return failedAfterCleanup(staging, "pinned Alpine rootfs could not be installed", failure)
        }
        return ApiResult.Success(InstalledAlpineRootfs(target, verifiedArchive.sha256))
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

    private fun validateRootfs(directory: Path) {
        val root = directory.toRealPath()
        listOf("bin/sh", "bin/busybox").forEach { relativePath ->
            val executable = root.resolve(relativePath)
            if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                throw IOException("Alpine rootfs is missing an executable $relativePath")
            }
            if (!executable.toRealPath().startsWith(root)) {
                throw IOException("Alpine rootfs $relativePath resolves outside its root")
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        val paths = Files.walk(path)
        try {
            val iterator = paths.sorted(Comparator.reverseOrder()).iterator()
            while (iterator.hasNext()) Files.delete(iterator.next())
        } finally {
            paths.close()
        }
    }

    private companion object {
        const val ARCHIVE_DIGEST_FILE = ".runtime-archive-sha256"
    }
}

class AlpineRuntimeLibraryInstaller(
    private val verifier: RuntimeArtifactVerifier = RuntimeArtifactVerifier(),
    private val extractor: RuntimeArchiveExtractor = AlpineRootfsTarGzExtractor(),
) {
    fun install(
        archive: Path,
        expectedArchiveSha256: String,
        packageName: String,
        libraryName: String,
        rootfsDirectory: Path,
    ): ApiResult<Path> {
        if (PACKAGE_LIBRARIES[packageName] != libraryName) {
            return ApiResult.Failure(
                ApiError(ErrorCode.E_UNSUPPORTED_VERSION, "unsupported pinned Alpine runtime package"),
            )
        }
        val verifiedArchive = when (val result = verifier.verify(archive, expectedArchiveSha256)) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> result.value
        }
        val rootfsPath = rootfsDirectory.toAbsolutePath().normalize()
        val root = try {
            if (Files.isSymbolicLink(rootfsPath) || !Files.isDirectory(rootfsPath, NOFOLLOW_LINKS)) {
                return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "Alpine rootfs is not a real directory"))
            }
            rootfsPath.toRealPath()
        } catch (failure: IOException) {
            return failure("Alpine rootfs could not be resolved", failure)
        }

        val libraryDirectory = try {
            val usr = root.resolve("usr")
            ensureRealDirectory(root, usr)
            val lib = usr.resolve("lib")
            ensureRealDirectory(root, lib)
            lib
        } catch (failure: IOException) {
            return failure("Alpine library directory is invalid", failure)
        }
        val stagingParent = root.parent
            ?: return ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "Alpine rootfs has no parent"))
        val extraction = try {
            Files.createTempDirectory(stagingParent, ".${root.fileName}-$packageName-")
        } catch (failure: IOException) {
            return failure("Alpine package staging directory could not be created", failure)
        }

        var result: ApiResult<Path> = try {
            extractor.extract(verifiedArchive.path, extraction)
            val source = extraction.resolve("usr/lib").resolve(libraryName)
            val resolvedSource = source.toRealPath()
            if (!Files.isRegularFile(resolvedSource) || !resolvedSource.startsWith(extraction.toRealPath())) {
                throw IOException("$packageName package does not contain a safe usr/lib/$libraryName")
            }
            val destination = libraryDirectory.resolve(libraryName)
            if (Files.isSymbolicLink(destination) ||
                (Files.exists(destination, NOFOLLOW_LINKS) && !Files.isRegularFile(destination, NOFOLLOW_LINKS))
            ) {
                throw IOException("Alpine runtime library destination is not a regular file")
            }
            val temporary = Files.createTempFile(libraryDirectory, ".$libraryName-", ".part")
            try {
                Files.copy(resolvedSource, temporary, REPLACE_EXISTING)
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            ApiResult.Success(destination)
        } catch (failure: IOException) {
            failure("pinned Alpine runtime package could not be installed", failure)
        } catch (unsupported: UnsupportedOperationException) {
            failure(
                "Alpine runtime package storage does not support atomic install",
                IOException(unsupported.message, unsupported),
            )
        }

        try {
            deleteTree(extraction)
        } catch (cleanupFailure: IOException) {
            result = when (result) {
                is ApiResult.Failure -> failure(
                    "${result.error.message ?: "Alpine package installation failed"}; staging cleanup failed",
                    cleanupFailure,
                )
                is ApiResult.Success -> failure(
                    "Alpine package installed but staging cleanup failed",
                    cleanupFailure,
                )
            }
        }
        return result
    }

    private fun ensureRealDirectory(root: Path, directory: Path) {
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                throw IOException("Alpine runtime path is not a real directory: $directory")
            }
        } else {
            Files.createDirectory(directory)
        }
        if (!directory.toRealPath().startsWith(root)) {
            throw IOException("Alpine runtime path resolves outside the rootfs")
        }
    }

    private fun failure(message: String, cause: IOException): ApiResult.Failure =
        ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, "$message: ${cause.message}"))

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private companion object {
        val PACKAGE_LIBRARIES = mapOf(
            "libgcc" to "libgcc_s.so.1",
            "libstdc++" to "libstdc++.so.6",
        )
    }
}
