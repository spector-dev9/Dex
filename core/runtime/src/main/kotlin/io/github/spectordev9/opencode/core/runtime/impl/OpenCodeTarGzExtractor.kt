package io.github.spectordev9.opencode.core.runtime.impl

import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.GZIPInputStream

class OpenCodeTarGzExtractor : RuntimeArchiveExtractor {
    override fun extract(archive: Path, destination: Path) {
        Files.createDirectories(destination)
        if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, NOFOLLOW_LINKS)) {
            throw IOException("OpenCode extraction destination is not a real directory")
        }
        GZIPInputStream(Files.newInputStream(archive).buffered()).use { input ->
            val header = ByteArray(BLOCK_SIZE)
            var extracted = false
            while (readBlock(input, header)) {
                if (header.all { it == 0.toByte() }) {
                    requireZeroPadding(input)
                    if (!extracted) throw IOException("OpenCode archive contains no executable")
                    return
                }
                if (extracted) throw IOException("OpenCode archive contains unexpected additional entries")
                verifyChecksum(header)
                val name = readString(header, NAME_OFFSET, NAME_LENGTH)
                val prefix = readString(header, PREFIX_OFFSET, PREFIX_LENGTH)
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (path != "opencode") throw IOException("unexpected OpenCode archive entry: $path")
                val type = header[TYPE_OFFSET].toInt().toChar()
                if (type != '\u0000' && type != '0') {
                    throw IOException("unsupported OpenCode archive entry type")
                }
                val size = readOctal(header, SIZE_OFFSET, SIZE_LENGTH)
                val executable = destination.resolve("opencode")
                Files.newOutputStream(executable, CREATE_NEW, WRITE).buffered().use { output ->
                    copyExactly(input, output, size)
                }
                skipPadding(input, size)
                extracted = true
            }
            throw IOException("OpenCode archive is missing its end marker")
        }
    }

    private fun verifyChecksum(header: ByteArray) {
        val expected = readOctal(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH)
        val actual = header.indices.sumOf { index ->
            if (index in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) {
                ' '.code
            } else {
                header[index].toInt() and 0xff
            }
        }.toLong()
        if (actual != expected) throw IOException("OpenCode archive header checksum mismatch")
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() }
            ?: offset + length
        return header.copyOfRange(offset, end).toString(Charsets.US_ASCII)
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        val value = header.copyOfRange(offset, offset + length)
            .toString(Charsets.US_ASCII)
            .trim(' ', '\u0000')
        if (value.isEmpty() || value.any { it !in '0'..'7' }) {
            throw IOException("invalid OpenCode archive numeric field")
        }
        return value.toLongOrNull(8) ?: throw IOException("OpenCode archive numeric field overflow")
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val read = input.read(block, offset, block.size - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw IOException("truncated OpenCode archive header")
            }
            if (read == 0) continue
            offset += read
        }
        return true
    }

    private fun copyExactly(input: InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException("truncated OpenCode executable entry")
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE
        repeat(padding.toInt()) {
            if (input.read() != 0) throw IOException("invalid OpenCode archive entry padding")
        }
    }

    private fun requireZeroPadding(input: InputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if ((0 until read).any { buffer[it] != 0.toByte() }) {
                throw IOException("OpenCode archive contains data after its end marker")
            }
        }
    }

    private companion object {
        const val BLOCK_SIZE = 512
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val NAME_OFFSET = 0
        const val NAME_LENGTH = 100
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val CHECKSUM_OFFSET = 148
        const val CHECKSUM_LENGTH = 8
        const val TYPE_OFFSET = 156
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
    }
}
