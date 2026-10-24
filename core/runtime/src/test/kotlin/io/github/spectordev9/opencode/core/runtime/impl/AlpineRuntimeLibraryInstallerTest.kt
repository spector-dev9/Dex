package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlpineRuntimeLibraryInstallerTest {
    @Test
    fun `installs only the pinned runtime library atomically into the rootfs`() {
        val root = createTempDirectory("alpine-library")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val archive = root.resolve("libgcc.apk").also { it.writeBytes(ARCHIVE_BYTES) }
        val libraryBytes = "pinned libgcc".toByteArray()
        val installer = AlpineRuntimeLibraryInstaller(
            extractor = RuntimeArchiveExtractor { _, destination ->
                Files.createDirectories(destination.resolve("usr/lib"))
                destination.resolve("usr/lib/libgcc_s.so.1").writeBytes(libraryBytes)
                destination.resolve("usr/lib/unrelated").writeText("not installed")
            },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(ARCHIVE_BYTES),
            packageName = "libgcc",
            libraryName = "libgcc_s.so.1",
            rootfsDirectory = rootfs,
        )

        val library = assertIs<ApiResult.Success<Path>>(result).value
        assertEquals(libraryBytes.toList(), Files.readAllBytes(library).toList())
        assertFalse(Files.exists(rootfs.resolve("usr/lib/unrelated")))
        assertEquals(2, Files.list(root).use { paths -> paths.count() })
    }

    @Test
    fun `rejects an archive that does not match its manifest digest`() {
        val root = createTempDirectory("alpine-library-digest")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val archive = root.resolve("libgcc.apk").also { it.writeBytes(ARCHIVE_BYTES) }
        val installer = AlpineRuntimeLibraryInstaller(
            extractor = RuntimeArchiveExtractor { _, _ -> error("must not extract an unverified package") },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = "a".repeat(64),
            packageName = "libgcc",
            libraryName = "libgcc_s.so.1",
            rootfsDirectory = rootfs,
        )

        assertIs<ApiResult.Failure>(result)
        assertFalse(Files.exists(rootfs.resolve("usr")))
    }

    @Test
    fun `rejects package paths that can escape the rootfs or package staging directory`() {
        val root = createTempDirectory("alpine-library-symlink")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(rootfs.resolve("usr"), outside)
        val archive = root.resolve("libgcc.apk").also { it.writeBytes(ARCHIVE_BYTES) }
        val installer = AlpineRuntimeLibraryInstaller(
            extractor = RuntimeArchiveExtractor { _, _ -> error("must reject unsafe rootfs before extraction") },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(ARCHIVE_BYTES),
            packageName = "libgcc",
            libraryName = "libgcc_s.so.1",
            rootfsDirectory = rootfs,
        )

        assertIs<ApiResult.Failure>(result)
        assertTrue(Files.list(outside).use { paths -> !paths.findAny().isPresent })
    }

    @Test
    fun `rejects runtime libraries not paired with their approved package`() {
        val root = createTempDirectory("alpine-library-package")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val archive = root.resolve("libgcc.apk").also { it.writeBytes(ARCHIVE_BYTES) }
        val installer = AlpineRuntimeLibraryInstaller()

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(ARCHIVE_BYTES),
            packageName = "libgcc",
            libraryName = "unexpected.so",
            rootfsDirectory = rootfs,
        )

        assertIs<ApiResult.Failure>(result)
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val ARCHIVE_BYTES = "archive".toByteArray()
    }
}
