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

class OpenCodeTarGzExtractorTest {
    @Test
    fun `extracts the pinned archive executable entry`() {
        val root = createTempDirectory("opencode-tar")
        val archive = writeArchive(root, listOf(Entry("opencode", "binary".toByteArray())))
        val destination = root / "extracted"

        OpenCodeTarGzExtractor().extract(archive, destination)

        assertContentEquals("binary".toByteArray(), (destination / "opencode").readBytes())
    }

    @Test
    fun `rejects paths that escape the extraction destination`() {
        val root = createTempDirectory("opencode-tar-traversal")
        val archive = writeArchive(root, listOf(Entry("../outside", "binary".toByteArray())))

        assertFailsWith<IOException> {
            OpenCodeTarGzExtractor().extract(archive, root / "extracted")
        }
        kotlin.test.assertFalse(Files.exists(root / "outside"))
    }

    @Test
    fun `rejects symbolic links and additional archive entries`() {
        val root = createTempDirectory("opencode-tar-entry-type")
        val linkArchive = writeArchive(root, listOf(Entry("opencode", byteArrayOf(), type = '2')))
        assertFailsWith<IOException> {
            OpenCodeTarGzExtractor().extract(linkArchive, root / "link-output")
        }

        val multiEntryArchive = writeArchive(
            root,
            listOf(
                Entry("opencode", "binary".toByteArray()),
                Entry("unexpected", "extra".toByteArray()),
            ),
        )
        assertFailsWith<IOException> {
            OpenCodeTarGzExtractor().extract(multiEntryArchive, root / "multi-output")
        }
    }

    @Test
    fun `rejects a symlink extraction destination`() {
        val root = createTempDirectory("opencode-tar-destination")
        val archive = writeArchive(root, listOf(Entry("opencode", "binary".toByteArray())))
        val outside = Files.createDirectory(root / "outside")
        val destination = root / "linked-destination"
        Files.createSymbolicLink(destination, outside)

        assertFailsWith<IOException> {
            OpenCodeTarGzExtractor().extract(archive, destination)
        }
        kotlin.test.assertFalse(Files.exists(outside / "opencode"))
    }

    @Test
    fun `rejects archives without a tar end marker`() {
        val root = createTempDirectory("opencode-tar-no-end")
        val archive = writeArchive(root, listOf(Entry("opencode", "binary".toByteArray())), endMarker = false)

        assertFailsWith<IOException> {
            OpenCodeTarGzExtractor().extract(archive, root / "extracted")
        }
    }

    private fun writeArchive(
        root: Path,
        entries: List<Entry>,
        endMarker: Boolean = true,
    ): Path {
        val tar = ByteArrayOutputStream()
        entries.forEach { entry ->
            val header = ByteArray(BLOCK_SIZE)
            writeString(header, 0, 100, entry.name)
            writeOctal(header, 100, 8, 493)
            writeOctal(header, 108, 8, 0)
            writeOctal(header, 116, 8, 0)
            writeOctal(header, 124, 12, entry.contents.size.toLong())
            writeOctal(header, 136, 12, 0)
            header.fill(' '.code.toByte(), CHECKSUM_OFFSET, CHECKSUM_OFFSET + 8)
            header[156] = entry.type.code.toByte()
            writeString(header, 257, 6, "ustar\u0000")
            writeString(header, 263, 2, "00")
            val checksum = header.sumOf { it.toInt() and 0xff }
            writeChecksum(header, checksum)
            tar.write(header)
            tar.write(entry.contents)
            val padding = (BLOCK_SIZE - entry.contents.size % BLOCK_SIZE) % BLOCK_SIZE
            tar.write(ByteArray(padding))
        }
        if (endMarker) tar.write(ByteArray(BLOCK_SIZE * 2))

        val archive = root / "archive-${entries.size}-${endMarker}.tar.gz"
        GZIPOutputStream(Files.newOutputStream(archive)).use { it.write(tar.toByteArray()) }
        return archive
    }

    private fun writeString(header: ByteArray, offset: Int, length: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
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
        val contents: ByteArray,
        val type: Char = '0',
    )

    private companion object {
        const val BLOCK_SIZE = 512
        const val CHECKSUM_OFFSET = 148
    }
}
