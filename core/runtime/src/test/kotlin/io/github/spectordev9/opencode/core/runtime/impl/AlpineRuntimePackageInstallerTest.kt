package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlpineRuntimePackageInstallerTest {
    @Test
    fun `installs verified package files and relative symlinks into rootfs`() {
        val root = createTempDirectory("alpine-package")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val archive = root.resolve("nodejs.apk").also { it.writeBytes(ARCHIVE_BYTES) }
        val nodeBytes = "node executable".toByteArray()
        val libraryBytes = "node dependency".toByteArray()
        val installer = AlpineRuntimePackageInstaller(
            extractor = RuntimeArchiveExtractor { _, destination ->
                val bin = Files.createDirectories(destination.resolve("usr/bin"))
                val lib = Files.createDirectories(destination.resolve("usr/lib"))
                val node = bin.resolve("node").also { it.writeBytes(nodeBytes) }
                node.toFile().setExecutable(true)
                lib.resolve("libnode.so.1").writeBytes(libraryBytes)
                Files.createSymbolicLink(lib.resolve("libnode.so"), Path.of("libnode.so.1"))
            },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(ARCHIVE_BYTES),
            packageName = "nodejs",
            rootfsDirectory = rootfs,
        )

        assertEquals(rootfs, assertIs<ApiResult.Success<Path>>(result).value)
        assertEquals(nodeBytes.toList(), Files.readAllBytes(rootfs.resolve("usr/bin/node")).toList())
        assertTrue(Files.isExecutable(rootfs.resolve("usr/bin/node")))
        assertEquals(
            libraryBytes.toList(),
            Files.readAllBytes(rootfs.resolve("usr/lib/libnode.so")).toList(),
        )
        assertEquals(Path.of("libnode.so.1"), Files.readSymbolicLink(rootfs.resolve("usr/lib/libnode.so")))
    }

    @Test
    fun `rejects a package with a symlink escaping the rootfs`() {
        val root = createTempDirectory("alpine-package-symlink")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val archive = root.resolve("nodejs.apk").also { it.writeBytes(ARCHIVE_BYTES) }
        val installer = AlpineRuntimePackageInstaller(
            extractor = RuntimeArchiveExtractor { _, destination ->
                val bin = Files.createDirectories(destination.resolve("usr/bin"))
                Files.createSymbolicLink(bin.resolve("node"), Path.of("../../../../outside"))
            },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(ARCHIVE_BYTES),
            packageName = "nodejs",
            rootfsDirectory = rootfs,
        )

        assertIs<ApiResult.Failure>(result)
        assertFalse(Files.exists(rootfs.resolve("usr/bin/node")))
    }

    @Test
    fun `rejects an archive that does not match its manifest digest`() {
        val root = createTempDirectory("alpine-package-digest")
        val rootfs = Files.createDirectory(root.resolve("rootfs"))
        val archive = root.resolve("nodejs.apk").also { it.writeBytes(ARCHIVE_BYTES) }
        val installer = AlpineRuntimePackageInstaller(
            extractor = RuntimeArchiveExtractor { _, _ -> error("must not extract an unverified package") },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = "a".repeat(64),
            packageName = "nodejs",
            rootfsDirectory = rootfs,
        )

        assertIs<ApiResult.Failure>(result)
        assertFalse(Files.exists(rootfs.resolve("usr")))
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val ARCHIVE_BYTES = "archive".toByteArray()
    }
}
