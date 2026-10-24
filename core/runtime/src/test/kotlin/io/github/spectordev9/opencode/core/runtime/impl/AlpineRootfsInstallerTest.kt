package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AlpineRootfsInstallerTest {
    @Test
    fun `installs the pinned rootfs into an immutable content-addressed directory`() {
        val root = createTempDirectory("alpine-install")
        val archive = writeArchive(root)
        val digest = sha256(Files.readAllBytes(archive))
        val target = root / "rootfs-versions" / digest
        val installer = AlpineRootfsInstaller()

        val result = installer.install(archive, digest, target)

        val installed = assertIs<ApiResult.Success<InstalledAlpineRootfs>>(result).value
        assertEquals(target.toAbsolutePath().normalize(), installed.directory)
        assertEquals(digest, installed.archiveSha256)
        assertEquals(digest, (target / ".runtime-archive-sha256").readText())
        assertEquals("shell".toByteArray().toList(), (target / "bin/sh").readBytes().toList())
        assertIs<ApiResult.Success<InstalledAlpineRootfs>>(installer.install(archive, digest, target))
    }

    @Test
    fun `rejects a bad archive pin without publishing a rootfs`() {
        val root = createTempDirectory("alpine-install-pin")
        val archive = writeArchive(root)
        val target = root / "rootfs"

        val result = AlpineRootfsInstaller().install(archive, "a".repeat(64), target)

        assertIs<ApiResult.Failure>(result)
        assertTrue(!Files.exists(target))
        assertEquals(setOf("alpine.tar.gz"), Files.list(root).use { entries ->
            entries.map { it.fileName.toString() }.toList().toSet()
        })
    }

    @Test
    fun `does not overwrite a rootfs directory with a conflicting archive marker`() {
        val root = createTempDirectory("alpine-install-conflict")
        val archive = writeArchive(root)
        val digest = sha256(Files.readAllBytes(archive))
        val target = root / "rootfs"
        Files.createDirectory(target)
        Files.writeString(target.resolve(".runtime-archive-sha256"), "different")

        val result = AlpineRootfsInstaller().install(archive, digest, target)

        assertIs<ApiResult.Failure>(result)
        assertEquals("different", (target / ".runtime-archive-sha256").readText())
    }

    private fun writeArchive(root: Path): Path {
        val tar = ByteArrayOutputStream()
        val entries = listOf(
            Entry("bin/", type = '5', mode = 493),
            Entry("bin/sh", "shell".toByteArray(), mode = 493),
            Entry("bin/busybox", "busybox".toByteArray(), mode = 493),
        )
        entries.forEach { entry ->
            val header = ByteArray(BLOCK_SIZE)
            writeString(header, 0, 100, entry.name)
            writeOctal(header, 100, 8, entry.mode.toLong())
            writeOctal(header, 108, 8, 0)
            writeOctal(header, 116, 8, 0)
            writeOctal(header, 124, 12, entry.contents.size.toLong())
            writeOctal(header, 136, 12, 0)
            header.fill(' '.code.toByte(), CHECKSUM_OFFSET, CHECKSUM_OFFSET + 8)
            header[156] = entry.type.code.toByte()
            writeString(header, 257, 6, "ustar\u0000")
            writeString(header, 263, 2, "00")
            writeChecksum(header, header.sumOf { it.toInt() and 0xff })
            tar.write(header)
            tar.write(entry.contents)
            tar.write(ByteArray((BLOCK_SIZE - entry.contents.size % BLOCK_SIZE) % BLOCK_SIZE))
        }
        tar.write(ByteArray(BLOCK_SIZE * 2))
        val archive = root / "alpine.tar.gz"
        GZIPOutputStream(Files.newOutputStream(archive)).use { it.write(tar.toByteArray()) }
        return archive
    }

    private fun writeString(header: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= length)
        bytes.copyInto(header, offset)
    }

    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = value.toString(8).padStart(length - 1, '0')
        require(digits.length < length)
        digits.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        header[offset + length - 1] = 0
    }

    private fun writeChecksum(header: ByteArray, value: Int) {
        val digits = value.toString(8).padStart(6, '0')
        digits.toByteArray(Charsets.US_ASCII).copyInto(header, CHECKSUM_OFFSET)
        header[CHECKSUM_OFFSET + 6] = 0
        header[CHECKSUM_OFFSET + 7] = ' '.code.toByte()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Entry(
        val name: String,
        val contents: ByteArray = byteArrayOf(),
        val type: Char = '0',
        val mode: Int = 420,
    )

    private companion object {
        const val BLOCK_SIZE = 512
        const val CHECKSUM_OFFSET = 148
    }
}
