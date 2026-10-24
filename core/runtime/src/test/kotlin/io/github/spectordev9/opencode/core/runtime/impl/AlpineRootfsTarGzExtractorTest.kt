package io.github.spectordev9.opencode.core.runtime.impl

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlpineRootfsTarGzExtractorTest {
    @Test
    fun `extracts files directories and guest-root absolute symlinks`() {
        val root = createTempDirectory("alpine-rootfs")
        val archive = writeArchive(
            root,
            listOf(
                Entry("./", type = '5', mode = 493),
                Entry("./bin/", type = '5', mode = 493),
                Entry("./usr/", type = '5', mode = 493),
                Entry("./usr/bin/", type = '5', mode = 493),
                Entry("./bin/busybox", "binary".toByteArray(), mode = 493),
                Entry("./usr/bin/yes", type = '2', link = "/bin/busybox"),
            ),
        )
        val destination = root / "rootfs"

        AlpineRootfsTarGzExtractor().extract(archive, destination)

        assertContentEquals("binary".toByteArray(), (destination / "bin/busybox").readBytes())
        assertTrue(Files.isExecutable(destination / "bin/busybox"))
        assertTrue(Files.isSymbolicLink(destination / "usr/bin/yes"))
        assertContentEquals("binary".toByteArray(), (destination / "usr/bin/yes").readBytes())
    }

    @Test
    fun `rejects archive traversal and absolute entry paths`() {
        val root = createTempDirectory("alpine-rootfs-traversal")
        listOf("../outside", "/outside").forEachIndexed { index, path ->
            val archive = writeArchive(root, listOf(Entry(path, "bad".toByteArray())))
            assertFailsWith<IOException> {
                AlpineRootfsTarGzExtractor().extract(archive, root / "destination-$index")
            }
        }
        assertTrue(!Files.exists(root / "outside"))
    }

    @Test
    fun `rejects escaping links and entries beneath symlink parents`() {
        val root = createTempDirectory("alpine-rootfs-links")
        val escapingLink = writeArchive(
            root,
            listOf(Entry("etc/", type = '5', mode = 493), Entry("etc/link", type = '2', link = "../../outside")),
        )
        assertFailsWith<IOException> {
            AlpineRootfsTarGzExtractor().extract(escapingLink, root / "escaping")
        }

        val symlinkParent = writeArchive(
            root,
            listOf(
                Entry("escape", type = '2', link = "/"),
                Entry("escape/file", "bad".toByteArray()),
            ),
        )
        assertFailsWith<IOException> {
            AlpineRootfsTarGzExtractor().extract(symlinkParent, root / "nested-link")
        }
        assertTrue(!Files.exists(root / "outside"))
    }

    @Test
    fun `requires an empty real extraction destination`() {
        val root = createTempDirectory("alpine-rootfs-destination")
        val archive = writeArchive(root,         listOf(Entry("bin/", type = '5', mode = 493)))
        val destination = Files.createDirectory(root / "existing")
        Files.writeString(destination.resolve("keep"), "existing")

        assertFailsWith<IOException> {
            AlpineRootfsTarGzExtractor().extract(archive, destination)
        }
        assertTrue(Files.exists(destination.resolve("keep")))
    }

    private fun writeArchive(root: Path, entries: List<Entry>): Path {
        val tar = ByteArrayOutputStream()
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
            writeString(header, 157, 100, entry.link)
            writeString(header, 257, 6, "ustar\u0000")
            writeString(header, 263, 2, "00")
            writeChecksum(header, header.sumOf { it.toInt() and 0xff })
            tar.write(header)
            tar.write(entry.contents)
            tar.write(ByteArray((BLOCK_SIZE - entry.contents.size % BLOCK_SIZE) % BLOCK_SIZE))
        }
        tar.write(ByteArray(BLOCK_SIZE * 2))

        val archive = root / "alpine-${entries.size}.tar.gz"
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

    private data class Entry(
        val name: String,
        val contents: ByteArray = byteArrayOf(),
        val type: Char = '0',
        val link: String = "",
        val mode: Int = 420,
    )

    private companion object {
        const val BLOCK_SIZE = 512
        const val CHECKSUM_OFFSET = 148
    }
}
