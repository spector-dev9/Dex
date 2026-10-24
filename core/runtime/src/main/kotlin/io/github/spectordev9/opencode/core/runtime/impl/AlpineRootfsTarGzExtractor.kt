package io.github.spectordev9.opencode.core.runtime.impl

import java.io.IOException
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

class AlpineRootfsTarGzExtractor : RuntimeArchiveExtractor {
    override fun extract(archive: Path, destination: Path) {
        Files.createDirectories(destination)
        if (Files.isSymbolicLink(destination) || !Files.isDirectory(destination, NOFOLLOW_LINKS)) {
            throw IOException("Alpine extraction destination is not a real directory")
        }
        val root = destination.toRealPath()
        Files.newDirectoryStream(root).use { entries ->
            if (entries.iterator().hasNext()) throw IOException("Alpine extraction destination is not empty")
        }

        val members = readMembers(archive)
        validateMembers(members)
        createDirectories(root, members)
        createSymlinks(root, members)
        extractFiles(archive, root, members)
        applyDirectoryPermissions(root, members)
    }

    private fun readMembers(archive: Path): List<Member> {
        val members = mutableListOf<Member>()
        GZIPInputStream(Files.newInputStream(archive).buffered()).use { input ->
            val header = ByteArray(BLOCK_SIZE)
            while (readBlock(input, header)) {
                if (header.all { it == 0.toByte() }) {
                    requireZeroPadding(input)
                    if (members.isEmpty()) throw IOException("Alpine archive contains no entries")
                    return members
                }
                verifyChecksum(header)
                val name = readString(header, NAME_OFFSET, NAME_LENGTH)
                val prefix = readString(header, PREFIX_OFFSET, PREFIX_LENGTH)
                val archivePath = if (prefix.isEmpty()) name else "$prefix/$name"
                val path = normalizeArchivePath(archivePath)
                val type = header[TYPE_OFFSET].toInt().toChar().let { if (it == '\u0000') '0' else it }
                if (type !in setOf('0', '5', '2')) {
                    throw IOException("unsupported Alpine archive entry type: $type")
                }
                val size = readOctal(header, SIZE_OFFSET, SIZE_LENGTH)
                if (size < 0 || size > MAX_ENTRY_SIZE) throw IOException("Alpine archive entry is too large")
                if (type != '0' && size != 0L) throw IOException("non-file Alpine entry has a payload")
                val mode = readOctal(header, MODE_OFFSET, MODE_LENGTH)
                val link = if (type == '2') readString(header, LINK_OFFSET, LINK_LENGTH) else null
                if (type == '2' && path.isEmpty()) throw IOException("Alpine archive cannot link the root")
                if (members.size >= MAX_ENTRIES) throw IOException("Alpine archive contains too many entries")
                members += Member(path, type, size, mode, link)
                if (type == '0') skipEntry(input, size) else skipPadding(input, 0)
            }
        }
        throw IOException("Alpine archive is missing its end marker")
    }

    private fun validateMembers(members: List<Member>) {
        val byPath = members.associateBy { it.path }
        if (byPath.size != members.size) throw IOException("Alpine archive contains duplicate paths")

        val symlinks = members.filter { it.type == '2' }.associate { member ->
            member.path to resolveLinkTarget(member.path, requireNotNull(member.link))
        }.keys
        members.forEach { member ->
            if (member.path.isEmpty() && member.type != '5') {
                throw IOException("Alpine archive root entry is not a directory")
            }
            val segments = member.path.split('/').filter(String::isNotEmpty)
            for (index in 1 until segments.size) {
                val parent = segments.take(index).joinToString("/")
                if (parent in symlinks) throw IOException("Alpine archive entry is nested under a symbolic link")
                val parentMember = byPath[parent]
                if (parentMember != null && parentMember.type != '5') {
                    throw IOException("Alpine archive entry is nested under a non-directory")
                }
            }
            if (member.type == '2') resolveLinkTarget(member.path, requireNotNull(member.link))
        }
    }

    private fun createDirectories(root: Path, members: List<Member>) {
        members.filter { it.type == '5' && it.path.isNotEmpty() }
            .sortedBy { it.path.count { character -> character == '/' } }
            .forEach { member ->
                val directory = root.resolve(member.path).normalize()
                requireInsideRoot(root, directory)
                Files.createDirectories(directory)
                requireInsideRoot(root, directory.toRealPath())
                if (!Files.isDirectory(directory)) throw IOException("Alpine directory entry conflicts with another path")
            }
    }

    private fun createSymlinks(root: Path, members: List<Member>) {
        members.filter { it.type == '2' }.forEach { member ->
            val link = root.resolve(member.path).normalize()
            val parent = link.parent ?: throw IOException("Alpine symbolic link has no parent")
            requireInsideRoot(root, link)
            requireInsideRoot(root, parent.toRealPath())
            if (Files.exists(link, NOFOLLOW_LINKS)) {
                throw IOException("Alpine symbolic link conflicts with an existing path")
            }
            val target = Path.of(resolveLinkTarget(member.path, requireNotNull(member.link)))
            val relativeTarget = parent.relativize(root.resolve(target).normalize())
            Files.createSymbolicLink(link, relativeTarget)
        }
    }

    private fun extractFiles(archive: Path, root: Path, expectedMembers: List<Member>) {
        GZIPInputStream(Files.newInputStream(archive).buffered()).use { input ->
            var index = 0
            val header = ByteArray(BLOCK_SIZE)
            while (readBlock(input, header)) {
                if (header.all { it == 0.toByte() }) {
                    requireZeroPadding(input)
                    if (index != expectedMembers.size) throw IOException("Alpine archive changed during extraction")
                    return
                }
                verifyChecksum(header)
                val member = expectedMembers.getOrNull(index++)
                    ?: throw IOException("Alpine archive changed during extraction")
                val parsed = parseMember(header)
                if (parsed != member) throw IOException("Alpine archive changed during extraction")
                if (member.type == '0') {
                    val file = root.resolve(member.path).normalize()
                    val parent = file.parent ?: throw IOException("Alpine file entry has no parent")
                    requireInsideRoot(root, file)
                    requireInsideRoot(root, parent.toRealPath())
                    Files.newOutputStream(file, CREATE_NEW, WRITE).buffered().use { output ->
                        copyExactly(input, output, member.size)
                    }
                    applyPermissions(file, member.mode)
                } else {
                    skipEntry(input, member.size)
                }
            }
            throw IOException("Alpine archive is missing its end marker")
        }
    }

    private fun parseMember(header: ByteArray): Member {
        val name = readString(header, NAME_OFFSET, NAME_LENGTH)
        val prefix = readString(header, PREFIX_OFFSET, PREFIX_LENGTH)
        val archivePath = if (prefix.isEmpty()) name else "$prefix/$name"
        val type = header[TYPE_OFFSET].toInt().toChar().let { if (it == '\u0000') '0' else it }
        val size = readOctal(header, SIZE_OFFSET, SIZE_LENGTH)
        val mode = readOctal(header, MODE_OFFSET, MODE_LENGTH)
        val link = if (type == '2') readString(header, LINK_OFFSET, LINK_LENGTH) else null
        return Member(normalizeArchivePath(archivePath), type, size, mode, link)
    }

    private fun normalizeArchivePath(value: String): String {
        if (value.startsWith('/') || '\\' in value || '\u0000' in value) {
            throw IOException("unsafe path in Alpine archive")
        }
        val segments = value.split('/').filter(String::isNotEmpty)
        if (segments.any { it == ".." }) throw IOException("unsafe path in Alpine archive")
        return segments.filterNot { it == "." }.joinToString("/")
    }

    private fun resolveLinkTarget(linkPath: String, linkTarget: String): String {
        if ('\\' in linkTarget || '\u0000' in linkTarget || linkTarget.isEmpty()) {
            throw IOException("unsafe symbolic link in Alpine archive")
        }
        val absolute = linkTarget.startsWith('/')
        val segments = if (absolute) mutableListOf() else {
            linkPath.split('/').dropLast(1).filter(String::isNotEmpty).toMutableList()
        }
        linkTarget.split('/').filter(String::isNotEmpty).forEach { segment ->
            when (segment) {
                "." -> Unit
                ".." -> if (segments.isEmpty()) {
                    throw IOException("Alpine symbolic link escapes the guest root")
                } else {
                    segments.removeAt(segments.lastIndex)
                }
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }

    private fun applyDirectoryPermissions(root: Path, members: List<Member>) {
        members.filter { it.type == '5' && it.path.isNotEmpty() }
            .sortedByDescending { it.path.count { character -> character == '/' } }
            .forEach { applyPermissions(root.resolve(it.path), it.mode) }
    }

    private fun applyPermissions(path: Path, mode: Long) {
        val permissions = buildSet {
            if (mode and 0x100 != 0L) add(PosixFilePermission.OWNER_READ)
            if (mode and 0x80 != 0L) add(PosixFilePermission.OWNER_WRITE)
            if (mode and 0x40 != 0L) add(PosixFilePermission.OWNER_EXECUTE)
            if (mode and 0x20 != 0L) add(PosixFilePermission.GROUP_READ)
            if (mode and 0x10 != 0L) add(PosixFilePermission.GROUP_WRITE)
            if (mode and 0x8 != 0L) add(PosixFilePermission.GROUP_EXECUTE)
            if (mode and 0x4 != 0L) add(PosixFilePermission.OTHERS_READ)
            if (mode and 0x2 != 0L) add(PosixFilePermission.OTHERS_WRITE)
            if (mode and 0x1 != 0L) add(PosixFilePermission.OTHERS_EXECUTE)
        }
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (failure: UnsupportedOperationException) {
            throw IOException("Alpine extraction filesystem does not support POSIX permissions", failure)
        }
    }

    private fun requireInsideRoot(root: Path, path: Path) {
        if (!path.startsWith(root)) throw IOException("Alpine archive entry escapes extraction destination")
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
        if (actual != expected) throw IOException("Alpine archive header checksum mismatch")
    }

    private fun readString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() }
            ?: offset + length
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(header, offset, end - offset))
                .toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw IOException("invalid UTF-8 in Alpine archive header", failure)
        }
    }

    private fun readOctal(header: ByteArray, offset: Int, length: Int): Long {
        val value = header.copyOfRange(offset, offset + length)
            .toString(Charsets.US_ASCII)
            .trim(' ', '\u0000')
        if (value.isEmpty() || value.any { it !in '0'..'7' }) {
            throw IOException("invalid Alpine archive numeric field")
        }
        return value.toLongOrNull(8) ?: throw IOException("Alpine archive numeric field overflow")
    }

    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < block.size) {
            val read = input.read(block, offset, block.size - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw IOException("truncated Alpine archive header")
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
            if (read < 0) throw IOException("truncated Alpine archive file entry")
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
        skipPadding(input, size)
    }

    private fun skipEntry(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw IOException("truncated Alpine archive entry")
                if (read == 0) continue
                remaining -= read
            }
        }
        skipPadding(input, size)
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val padding = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE
        repeat(padding.toInt()) {
            if (input.read() != 0) throw IOException("invalid Alpine archive entry padding")
        }
    }

    private fun requireZeroPadding(input: InputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if ((0 until read).any { buffer[it] != 0.toByte() }) {
                throw IOException("Alpine archive contains data after its end marker")
            }
        }
    }

    private data class Member(
        val path: String,
        val type: Char,
        val size: Long,
        val mode: Long,
        val link: String?,
    )

    private companion object {
        const val BLOCK_SIZE = 512
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MAX_ENTRY_SIZE = 1L shl 30
        const val MAX_ENTRIES = 10_000
        const val NAME_OFFSET = 0
        const val NAME_LENGTH = 100
        const val MODE_OFFSET = 100
        const val MODE_LENGTH = 8
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val CHECKSUM_OFFSET = 148
        const val CHECKSUM_LENGTH = 8
        const val TYPE_OFFSET = 156
        const val LINK_OFFSET = 157
        const val LINK_LENGTH = 100
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
    }
}
