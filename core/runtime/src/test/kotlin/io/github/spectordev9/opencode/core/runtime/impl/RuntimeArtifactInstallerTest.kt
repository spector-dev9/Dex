package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeArtifactInstallerTest {
    @Test
    fun `install verifies archive and executable before atomically publishing it`() {
        val parent = createTempDirectory("runtime-install")
        val archive = parent / "runtime.tar.gz"
        archive.writeBytes("archive".toByteArray())
        val executableBytes = "verified opencode".toByteArray()
        val target = parent / "runtime"
        val installer = installer(executableBytes)

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(archive.readBytes()),
            targetDirectory = target,
            executableName = "opencode",
            expectedExecutableSha256 = sha256(executableBytes),
        )

        val installed = assertIs<ApiResult.Success<InstalledRuntimeArtifact>>(result).value
        assertEquals(target.toAbsolutePath().normalize(), installed.directory)
        assertEquals(target / "opencode", installed.executable)
        assertEquals(sha256(executableBytes), installed.sha256)
        assertEquals(executableBytes.size.toLong(), installed.sizeBytes)
        assertEquals(executableBytes.toList(), installed.executable.readBytes().toList())
    }

    @Test
    fun `install preserves existing runtime when extracted executable digest is wrong`() {
        val parent = createTempDirectory("runtime-install-mismatch")
        val archive = parent / "runtime.tar.gz"
        archive.writeBytes("archive".toByteArray())
        val target = parent / "runtime"
        Files.createDirectory(target)
        (target / "opencode").writeBytes("previous version".toByteArray())
        val installer = installer("tampered executable".toByteArray())

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(archive.readBytes()),
            targetDirectory = target,
            executableName = "opencode",
            expectedExecutableSha256 = sha256("expected executable".toByteArray()),
        )

        assertIs<ApiResult.Failure>(result)
        assertEquals("previous version", (target / "opencode").readText())
        assertEquals(setOf("runtime", "runtime.tar.gz"), Files.list(parent).use { paths ->
            paths.map { it.fileName.toString() }.toList().toSet()
        })
    }

    @Test
    fun `install rejects archive hash mismatch before extraction`() {
        val parent = createTempDirectory("runtime-install-archive-mismatch")
        val archive = parent / "runtime.tar.gz"
        archive.writeBytes("archive".toByteArray())
        var extracted = false
        val installer = RuntimeArtifactInstaller(
            verifier = RuntimeArtifactVerifier(),
            extractor = RuntimeArchiveExtractor { _, _ -> extracted = true },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = "a".repeat(64),
            targetDirectory = parent / "runtime",
            executableName = "opencode",
            expectedExecutableSha256 = sha256("executable".toByteArray()),
        )

        assertIs<ApiResult.Failure>(result)
        assertFalse(extracted)
    }

    @Test
    fun `install does not replace an existing immutable runtime directory`() {
        val parent = createTempDirectory("runtime-install-replace")
        val archive = parent / "runtime.tar.gz"
        archive.writeBytes("archive".toByteArray())
        val target = parent / "runtime"
        Files.createDirectory(target)
        (target / "opencode").writeBytes("old version".toByteArray())
        val executableBytes = "new version".toByteArray()

        val result = installer(executableBytes).install(
            archive = archive,
            expectedArchiveSha256 = sha256(archive.readBytes()),
            targetDirectory = target,
            executableName = "opencode",
            expectedExecutableSha256 = sha256(executableBytes),
        )

        assertIs<ApiResult.Failure>(result)
        assertEquals("old version", (target / "opencode").readText())
        assertTrue(Files.isDirectory(target))
        assertEquals(setOf("runtime", "runtime.tar.gz"), Files.list(parent).use { paths ->
            paths.map { it.fileName.toString() }.toList().toSet()
        })
    }

    @Test
    fun `install rejects a symlink in place of executable`() {
        val parent = createTempDirectory("runtime-install-symlink")
        val archive = parent / "runtime.tar.gz"
        archive.writeBytes("archive".toByteArray())
        val outside = parent / "outside"
        outside.writeBytes("executable".toByteArray())
        val installer = RuntimeArtifactInstaller(
            verifier = RuntimeArtifactVerifier(),
            extractor = RuntimeArchiveExtractor { _, destination ->
                Files.createSymbolicLink(destination.resolve("opencode"), outside)
            },
        )

        val result = installer.install(
            archive = archive,
            expectedArchiveSha256 = sha256(archive.readBytes()),
            targetDirectory = parent / "runtime",
            executableName = "opencode",
            expectedExecutableSha256 = sha256("executable".toByteArray()),
        )

        assertIs<ApiResult.Failure>(result)
        assertFalse(Files.exists(parent / "runtime"))
    }

    private fun installer(executableBytes: ByteArray) = RuntimeArtifactInstaller(
        verifier = RuntimeArtifactVerifier(),
        extractor = RuntimeArchiveExtractor { _, destination ->
            (destination / "opencode").writeBytes(executableBytes)
        },
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(path: Path): String = sha256(path.readBytes())
}
