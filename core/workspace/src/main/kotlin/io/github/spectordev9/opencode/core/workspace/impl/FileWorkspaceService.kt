package io.github.spectordev9.opencode.core.workspace.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.common.TrashId
import io.github.spectordev9.opencode.core.workspace.Chunk
import io.github.spectordev9.opencode.core.workspace.Entry
import io.github.spectordev9.opencode.core.workspace.EntryKind
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.WorkspaceService
import io.github.spectordev9.opencode.core.workspace.WriteReceipt
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal fun interface AtomicWriteObserver {
    fun afterWrite(bytesWritten: Long)
}

class FileWorkspaceService internal constructor(
    rootDirectory: Path,
    private val afterTemporaryWrite: AtomicWriteObserver?,
) : WorkspaceService {
    constructor(rootDirectory: Path) : this(rootDirectory, null)

    private val root: Path
    private val trashRoot: Path
    private val mutationLock = Mutex()

    init {
        require(!Files.isSymbolicLink(rootDirectory) && Files.isDirectory(rootDirectory, NOFOLLOW_LINKS)) {
            "workspace root must be an existing real directory"
        }
        root = rootDirectory.toRealPath()
        trashRoot = root.resolve(TRASH_DIRECTORY)
    }

    override suspend fun list(path: String): ApiResult<List<Entry>> = readOperation {
        val directory = resolve(path, allowRoot = true)
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) deny("path is not a directory")
        Files.newDirectoryStream(directory).use { children ->
            children.asSequence()
                .filterNot { directory == root && it.fileName.toString() == TRASH_DIRECTORY }
                .map(::entry)
                .toList()
                .sortedBy { it.name.lowercase() }
        }
    }

    override suspend fun stat(path: String): ApiResult<Entry> = readOperation {
        entry(resolve(path, allowRoot = true, allowFinalSymlink = true))
    }

    override suspend fun readRange(path: String, offset: Long, length: Int): ApiResult<Chunk> = readOperation {
        if (offset < 0 || length < 0) deny("read range must be non-negative")
        if (length > MAX_READ_BYTES) fail(ErrorCode.E_TOO_LARGE, "read range exceeds $MAX_READ_BYTES bytes")
        val file = resolve(path)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) deny("range reads require a regular file")
        FileChannel.open(file, READ).use { channel ->
            channel.position(offset)
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) continue
            }
            val bytes = ByteArray(buffer.position())
            buffer.flip()
            buffer.get(bytes)
            val size = channel.size()
            Chunk(
                offset = offset,
                bytes = bytes,
                endOfFile = offset >= size || bytes.size.toLong() >= size - offset,
            )
        }
    }

    override suspend fun write(path: String, data: ByteArray, expect: Precondition): ApiResult<WriteReceipt> =
        mutate {
            if (data.size > MAX_WRITE_BYTES) fail(ErrorCode.E_TOO_LARGE, "write exceeds $MAX_WRITE_BYTES bytes")
            val target = resolve(path, allowMissingLeaf = true)
            if (target == root) deny("workspace root cannot be replaced")
            val parent = target.parent ?: deny("file path has no parent")
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) deny("parent directory does not exist")
            requirePrecondition(target, expect)

            val existingPermissions = if (Files.exists(target, NOFOLLOW_LINKS)) {
                Files.getPosixFilePermissions(target, NOFOLLOW_LINKS)
            } else {
                null
            }
            val temporary = Files.createTempFile(parent, TEMP_PREFIX, TEMP_SUFFIX)
            try {
                FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(data)
                    if (afterTemporaryWrite == null) {
                        while (buffer.hasRemaining()) channel.write(buffer)
                    } else if (buffer.hasRemaining()) {
                        buffer.limit(maxOf(1, buffer.remaining() / 2))
                        while (buffer.hasRemaining()) channel.write(buffer)
                        afterTemporaryWrite.afterWrite(channel.position())
                        buffer.limit(data.size)
                        while (buffer.hasRemaining()) channel.write(buffer)
                    }
                    channel.force(true)
                }
                if (existingPermissions != null) {
                    Files.setPosixFilePermissions(temporary, existingPermissions)
                }
                requirePrecondition(target, expect)
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            val attributes = Files.readAttributes(target, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            WriteReceipt(
                path = virtualPath(target),
                sizeBytes = attributes.size(),
                modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis(),
                sha256 = sha256(data),
            )
        }

    override suspend fun mkdir(path: String): ApiResult<Entry> = mutate {
        val directory = resolve(path, allowMissingLeaf = true)
        if (directory == root) fail(ErrorCode.E_CONFLICT, "workspace root already exists")
        if (Files.exists(directory, NOFOLLOW_LINKS)) fail(ErrorCode.E_CONFLICT, "path already exists")
        Files.createDirectory(directory)
        entry(directory)
    }

    override suspend fun rename(from: String, to: String): ApiResult<Entry> = moveEntry(from, to, requireSameParent = true)

    override suspend fun move(from: String, to: String): ApiResult<Entry> = moveEntry(from, to, requireSameParent = false)

    override suspend fun trash(path: String): ApiResult<TrashId> = mutate {
        val source = resolve(path, allowFinalSymlink = true)
        if (source == root) deny("workspace root cannot be trashed")
        val relativeSource = root.relativize(source).joinToString("/")
        val id = UUID.randomUUID().toString()
        ensureTrashRoot()
        val staging = trashRoot.resolve(".staging-$id")
        val destination = trashRoot.resolve(id)
        Files.createDirectory(staging)
        val metadata = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(relativeSource.toByteArray(Charsets.UTF_8))
        try {
            Files.writeString(staging.resolve(TRASH_PATH_FILE), metadata, Charsets.US_ASCII)
            Files.move(source, staging.resolve(TRASH_DATA_FILE), ATOMIC_MOVE)
            Files.move(staging, destination, ATOMIC_MOVE)
        } catch (failure: IOException) {
            if (Files.exists(staging.resolve(TRASH_DATA_FILE), NOFOLLOW_LINKS) &&
                !Files.exists(source, NOFOLLOW_LINKS)
            ) {
                try {
                    Files.move(staging.resolve(TRASH_DATA_FILE), source, ATOMIC_MOVE)
                } catch (restoreFailure: IOException) {
                    failure.addSuppressed(restoreFailure)
                }
            }
            try {
                deleteTree(staging)
            } catch (cleanupFailure: IOException) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        TrashId(id)
    }

    override suspend fun restore(id: TrashId): ApiResult<Entry> = mutate {
        val uuid = try {
            UUID.fromString(id.value).toString()
        } catch (_: IllegalArgumentException) {
            deny("invalid trash id")
        }
        ensureTrashRoot()
        val item = trashRoot.resolve(uuid)
        if (Files.isSymbolicLink(item) || !Files.isDirectory(item, NOFOLLOW_LINKS)) {
            deny("trash item does not exist")
        }
        val metadata = item.resolve(TRASH_PATH_FILE)
        val data = item.resolve(TRASH_DATA_FILE)
        if (Files.isSymbolicLink(metadata) || !Files.isRegularFile(metadata, NOFOLLOW_LINKS) ||
            !Files.exists(data, NOFOLLOW_LINKS)
        ) {
            deny("trash item is incomplete")
        }
        val relative = try {
            String(
                Base64.getUrlDecoder().decode(Files.readString(metadata, Charsets.US_ASCII)),
                Charsets.UTF_8,
            )
        } catch (_: IllegalArgumentException) {
            deny("trash metadata is invalid")
        }
        val target = resolve(relative, allowMissingLeaf = true, allowFinalSymlink = true)
        if (target == root || Files.exists(target, NOFOLLOW_LINKS)) {
            fail(ErrorCode.E_CONFLICT, "original workspace path is no longer available")
        }
        Files.move(data, target, ATOMIC_MOVE)
        Files.delete(metadata)
        Files.delete(item)
        entry(target)
    }

    override suspend fun search(path: String, query: String, limit: Int): ApiResult<List<Entry>> = readOperation {
        if (limit !in 1..MAX_SEARCH_RESULTS) deny("search limit must be between 1 and $MAX_SEARCH_RESULTS")
        if ('\u0000' in query) deny("search query contains NUL")
        if (query.isEmpty()) return@readOperation emptyList()
        val start = resolve(path, allowRoot = true)
        if (!Files.isDirectory(start, NOFOLLOW_LINKS)) deny("search path is not a directory")
        val needle = Normalizer.normalize(query, Normalizer.Form.NFC)
        val results = mutableListOf<Entry>()
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (directory == trashRoot) return FileVisitResult.SKIP_SUBTREE
                if (!directory.startsWith(root)) deny("search escaped workspace root")
                if (directory != start && matches(directory, needle)) {
                    results += entry(directory)
                    if (results.size >= limit) return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (file != trashRoot && file.startsWith(root) && matches(file, needle)) {
                    results += entry(file)
                    if (results.size >= limit) return FileVisitResult.TERMINATE
                }
                return FileVisitResult.CONTINUE
            }
        })
        results.sortedBy { it.path }
    }

    private suspend fun moveEntry(from: String, to: String, requireSameParent: Boolean): ApiResult<Entry> = mutate {
        val source = resolve(from, allowFinalSymlink = true)
        val target = resolve(to, allowMissingLeaf = true)
        if (source == root || target == root) deny("workspace root cannot be moved")
        if (source == target) fail(ErrorCode.E_CONFLICT, "source and destination are identical")
        if (requireSameParent && source.parent != target.parent) {
            deny("rename destination must remain in the same directory")
        }
        if (Files.exists(target, NOFOLLOW_LINKS)) fail(ErrorCode.E_CONFLICT, "destination already exists")
        if (Files.isDirectory(source, NOFOLLOW_LINKS) && target.startsWith(source)) {
            deny("directory cannot be moved into itself")
        }
        Files.move(source, target, ATOMIC_MOVE)
        entry(target)
    }

    private fun resolve(
        value: String,
        allowRoot: Boolean = false,
        allowMissingLeaf: Boolean = false,
        allowFinalSymlink: Boolean = false,
    ): Path {
        val relative = normalizePath(value)
        if (relative.isEmpty()) {
            if (!allowRoot) deny("operation is not allowed on the workspace root")
            return root
        }
        var current = root
        val segments = relative.split('/')
        segments.forEachIndexed { index, segment ->
            val candidate = current.resolve(segment).normalize()
            if (!candidate.startsWith(root)) deny("path escapes workspace root")
            val isLeaf = index == segments.lastIndex
            if (!Files.exists(candidate, NOFOLLOW_LINKS)) {
                if (isLeaf && allowMissingLeaf) return candidate
                deny("workspace path does not exist")
            }
            if (Files.isSymbolicLink(candidate)) {
                if (!isLeaf || !allowFinalSymlink) deny("symbolic links cannot be traversed")
                current = candidate
            } else {
                if (!isLeaf && !Files.isDirectory(candidate, NOFOLLOW_LINKS)) {
                    deny("workspace path parent is not a directory")
                }
                current = candidate.toRealPath()
                if (!current.startsWith(root)) deny("path resolves outside workspace root")
            }
        }
        return current
    }

    private fun normalizePath(value: String): String {
        if ('\u0000' in value || '\\' in value || value.startsWith("//")) deny("invalid workspace path")
        val path = value.removePrefix("/")
        val segments = path.split('/').filter(String::isNotEmpty)
        if (segments.any { it == "." || it == ".." }) deny("relative traversal is not allowed")
        if (segments.any { !Normalizer.isNormalized(it, Normalizer.Form.NFC) }) {
            deny("workspace path must use Unicode NFC normalization")
        }
        if (segments.firstOrNull() == TRASH_DIRECTORY) deny("reserved workspace path")
        return segments.joinToString("/")
    }

    private fun entry(path: Path): Entry {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val kind = when {
            attributes.isSymbolicLink -> EntryKind.SYMLINK
            attributes.isRegularFile -> EntryKind.FILE
            attributes.isDirectory -> EntryKind.DIRECTORY
            else -> EntryKind.OTHER
        }
        val relative = if (path == root) "" else root.relativize(path).joinToString("/")
        val name = if (relative.isEmpty()) "/" else path.fileName.toString()
        return Entry(
            path = if (relative.isEmpty()) "/" else "/$relative",
            name = name,
            kind = kind,
            sizeBytes = attributes.size(),
            modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis(),
            symlinkTarget = if (kind == EntryKind.SYMLINK) Files.readSymbolicLink(path).toString() else null,
        )
    }

    private fun virtualPath(path: Path): String {
        val relative = root.relativize(path).joinToString("/")
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    private fun matches(path: Path, needle: String): Boolean {
        val name = path.fileName?.toString().orEmpty()
        return name.contains(needle, ignoreCase = true) ||
            virtualPath(path).contains(needle, ignoreCase = true)
    }

    private fun requirePrecondition(path: Path, expect: Precondition) {
        when (expect) {
            Precondition.None -> if (Files.exists(path, NOFOLLOW_LINKS)) {
                fail(ErrorCode.E_CONFLICT, "workspace file already exists")
            }
            is Precondition.Match -> {
                if (!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
                    !Files.isRegularFile(path, NOFOLLOW_LINKS)
                ) {
                    fail(ErrorCode.E_CONFLICT, "workspace file changed or no longer exists")
                }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.size() != expect.sizeBytes ||
                    attributes.lastModifiedTime().toMillis() != expect.modifiedAtEpochMillis
                ) {
                    fail(ErrorCode.E_CONFLICT, "workspace file changed since it was read")
                }
                if (!expect.sha256.matches(SHA256_PATTERN) ||
                    !sha256(path).equals(expect.sha256, ignoreCase = true)
                ) {
                    fail(ErrorCode.E_CONFLICT, "workspace file content changed since it was read")
                }
            }
        }
    }

    private fun ensureTrashRoot() {
        if (Files.exists(trashRoot, NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(trashRoot) || !Files.isDirectory(trashRoot, NOFOLLOW_LINKS) ||
                !trashRoot.toRealPath().startsWith(root)
            ) {
                deny("workspace trash directory is unsafe")
            }
        } else {
            Files.createDirectory(trashRoot)
            Files.setPosixFilePermissions(
                trashRoot,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private suspend fun <T> mutate(block: () -> T): ApiResult<T> {
        mutationLock.lock()
        return try {
            readOperation(block)
        } finally {
            mutationLock.unlock()
        }
    }

    private suspend fun <T> readOperation(block: () -> T): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            ApiResult.Success(block())
        } catch (failure: WorkspaceFailure) {
            ApiResult.Failure(ApiError(failure.code, failure.message))
        } catch (failure: IOException) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, failure.message ?: "workspace I/O failed"))
        } catch (failure: SecurityException) {
            ApiResult.Failure(ApiError(ErrorCode.E_PATH_DENIED, failure.message ?: "workspace access denied"))
        } catch (failure: UnsupportedOperationException) {
            ApiResult.Failure(ApiError(ErrorCode.E_RUNTIME_DOWN, failure.message ?: "workspace operation unsupported"))
        }
    }

    private fun deny(message: String): Nothing = fail(ErrorCode.E_PATH_DENIED, message)

    private fun fail(code: ErrorCode, message: String): Nothing = throw WorkspaceFailure(code, message)

    private class WorkspaceFailure(val code: ErrorCode, message: String) : RuntimeException(message)

    private companion object {
        private const val TRASH_DIRECTORY = ".opencode-trash"
        private const val TRASH_PATH_FILE = "original-path"
        private const val TRASH_DATA_FILE = "data"
        private const val TEMP_PREFIX = ".workspace-write-"
        private const val TEMP_SUFFIX = ".tmp"
        private const val MAX_READ_BYTES = 1024 * 1024
        private const val MAX_WRITE_BYTES = 50 * 1024 * 1024
        private const val MAX_SEARCH_RESULTS = 1000
        private const val HASH_BUFFER_SIZE = 64 * 1024
        private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
    }
}
