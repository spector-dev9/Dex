package io.github.spectordev9.opencode.core.workspace.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import io.github.spectordev9.opencode.core.workspace.Chunk
import io.github.spectordev9.opencode.core.workspace.Entry
import io.github.spectordev9.opencode.core.workspace.EntryKind
import io.github.spectordev9.opencode.core.workspace.Precondition
import io.github.spectordev9.opencode.core.workspace.WriteReceipt
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileWorkspaceServiceTest {
    @Test
    fun `writes empty and nonempty files atomically with receipts and range reads`() = runBlocking {
        val root = createTempDirectory("workspace-write")
        val service = FileWorkspaceService(root)

        val empty = assertIs<ApiResult.Success<WriteReceipt>>(
            service.write("/empty.txt", byteArrayOf(), Precondition.None),
        ).value
        assertEquals(0, empty.sizeBytes)
        assertTrue(Files.isRegularFile(root / "empty.txt"))

        val bytes = "0123456789".toByteArray()
        val written = assertIs<ApiResult.Success<WriteReceipt>>(
            service.write("notes.txt", bytes, Precondition.None),
        ).value
        assertEquals(sha256(bytes), written.sha256)
        val chunk = assertIs<ApiResult.Success<Chunk>>(service.readRange("/notes.txt", 2, 4)).value
        assertContentEquals("2345".toByteArray(), chunk.bytes)
        assertEquals(2, chunk.offset)
        assertEquals(false, chunk.endOfFile)
        val end = assertIs<ApiResult.Success<Chunk>>(service.readRange("notes.txt", 99, 8)).value
        assertContentEquals(byteArrayOf(), end.bytes)
        assertTrue(end.endOfFile)
    }

    @Test
    fun `preconditions reject stale content without overwriting it`() = runBlocking {
        val root = createTempDirectory("workspace-conflict")
        val file = root / "source.txt"
        file.writeBytes("original".toByteArray())
        val service = FileWorkspaceService(root)

        val stale = service.write(
            "/source.txt",
            "replacement".toByteArray(),
            Precondition.Match(sizeBytes = 8, sha256 = sha256("stale".toByteArray())),
        )

        assertEquals(ErrorCode.E_CONFLICT, assertIs<ApiResult.Failure>(stale).error.code)
        assertEquals("original", file.readText())
    }

    @Test
    fun `write with a matching precondition atomically replaces content`() = runBlocking {
        val root = createTempDirectory("workspace-write-match")
        val file = root / "source.txt"
        file.writeBytes("original".toByteArray())
        val service = FileWorkspaceService(root)
        val modified = Files.getLastModifiedTime(file).toMillis()

        val result = service.write(
            "/source.txt",
            "replacement".toByteArray(),
            Precondition.Match(sizeBytes = 8, modifiedAtEpochMillis = modified, sha256 = sha256(file.readBytes())),
        )

        assertIs<ApiResult.Success<WriteReceipt>>(result)
        assertEquals("replacement", file.readText())
    }

    @Test
    fun `list stat mkdir rename move and search implement workspace operations`() = runBlocking {
        val root = createTempDirectory("workspace-ops")
        val service = FileWorkspaceService(root)
        assertIs<ApiResult.Success<Entry>>(service.mkdir("/src"))
        assertIs<ApiResult.Success<Entry>>(service.mkdir("/dest"))
        assertIs<ApiResult.Success<WriteReceipt>>(
            service.write("/src/a file.txt", "content".toByteArray(), Precondition.None),
        )

        val listed = assertIs<ApiResult.Success<List<Entry>>>(service.list("/src")).value
        assertEquals(listOf("a file.txt"), listed.map { it.name })
        assertEquals(EntryKind.FILE, assertIs<ApiResult.Success<Entry>>(service.stat("/src/a file.txt")).value.kind)
        assertIs<ApiResult.Failure>(service.rename("/src/a file.txt", "/dest/renamed.txt"))
        assertIs<ApiResult.Success<Entry>>(service.rename("/src/a file.txt", "/src/renamed.txt"))
        assertIs<ApiResult.Success<Entry>>(service.move("/src/renamed.txt", "/dest/renamed.txt"))
        val found = assertIs<ApiResult.Success<List<Entry>>>(service.search("/", "renamed")).value
        assertEquals(listOf("/dest/renamed.txt"), found.map { it.path })
    }

    @Test
    fun `trash and restore preserve directories while keeping trash hidden`() = runBlocking {
        val root = createTempDirectory("workspace-trash")
        Files.createDirectories(root.resolve("folder/nested"))
        Files.writeString(root.resolve("folder/nested/item.txt"), "kept")
        val service = FileWorkspaceService(root)

        val id = assertIs<ApiResult.Success<io.github.spectordev9.opencode.core.common.TrashId>>(
            service.trash("/folder"),
        ).value
        assertTrue(!Files.exists(root / "folder"))
        assertTrue(assertIs<ApiResult.Success<List<Entry>>>(service.list("/")).value.none {
            it.name == ".opencode-trash"
        })
        assertTrue(assertIs<ApiResult.Success<List<Entry>>>(service.search("/", "item")).value.isEmpty())

        assertIs<ApiResult.Success<Entry>>(service.restore(id))
        assertEquals("kept", (root / "folder/nested/item.txt").readText())
    }

    @Test
    fun `all 60 file and folder operation cases preserve their names and contents`() = runBlocking {
        val root = createTempDirectory("workspace-operation-matrix")
        val service = FileWorkspaceService(root)
        val names = listOf("plain", "with space", "single'quote", "double\"quote", "café", "empty file")
        val operations = listOf("create", "rename", "move", "trash", "restore")
        var caseNumber = 0

        suspend fun createItem(path: String, directory: Boolean, name: String) {
            if (directory) {
                assertIs<ApiResult.Success<Entry>>(service.mkdir(path))
                assertIs<ApiResult.Success<WriteReceipt>>(
                    service.write("$path/child.txt", "nested:$name".toByteArray(), Precondition.None),
                )
            } else {
                val data = if (name == "empty file") byteArrayOf() else "content:$name".toByteArray()
                assertIs<ApiResult.Success<WriteReceipt>>(service.write(path, data, Precondition.None))
            }
        }

        suspend fun assertPresent(path: String, directory: Boolean, name: String) {
            val entry = assertIs<ApiResult.Success<Entry>>(service.stat(path)).value
            assertEquals(if (directory) EntryKind.DIRECTORY else EntryKind.FILE, entry.kind)
            if (directory) {
                val nested = assertIs<ApiResult.Success<Chunk>>(service.readRange("$path/child.txt", 0, 256)).value
                assertContentEquals("nested:$name".toByteArray(), nested.bytes)
            } else {
                val expected = if (name == "empty file") byteArrayOf() else "content:$name".toByteArray()
                val contents = assertIs<ApiResult.Success<Chunk>>(service.readRange(path, 0, 256)).value
                assertContentEquals(expected, contents.bytes)
            }
        }

        for (name in names) {
            for (directory in listOf(false, true)) {
                for (operation in operations) {
                    caseNumber += 1
                    val base = "/case-$caseNumber"
                    Files.createDirectory(root.resolve("case-$caseNumber"))

                    when (operation) {
                        "create" -> {
                            val target = "$base/$name"
                            createItem(target, directory, name)
                            assertPresent(target, directory, name)
                        }

                        "rename" -> {
                            val source = "$base/$name"
                            val target = "$base/$name-renamed"
                            createItem(source, directory, name)
                            assertIs<ApiResult.Success<Entry>>(service.rename(source, target))
                            assertPresent(target, directory, name)
                            assertIs<ApiResult.Failure>(service.stat(source))
                        }

                        "move" -> {
                            assertIs<ApiResult.Success<Entry>>(service.mkdir("$base/source"))
                            assertIs<ApiResult.Success<Entry>>(service.mkdir("$base/destination"))
                            val source = "$base/source/$name"
                            val target = "$base/destination/$name"
                            createItem(source, directory, name)
                            assertIs<ApiResult.Success<Entry>>(service.move(source, target))
                            assertPresent(target, directory, name)
                            assertIs<ApiResult.Failure>(service.stat(source))
                        }

                        "trash" -> {
                            val source = "$base/$name"
                            createItem(source, directory, name)
                            assertIs<ApiResult.Success<io.github.spectordev9.opencode.core.common.TrashId>>(
                                service.trash(source),
                            )
                            assertIs<ApiResult.Failure>(service.stat(source))
                        }

                        "restore" -> {
                            val source = "$base/$name"
                            createItem(source, directory, name)
                            val id =
                                assertIs<ApiResult.Success<io.github.spectordev9.opencode.core.common.TrashId>>(
                                    service.trash(source),
                                ).value
                            assertIs<ApiResult.Success<Entry>>(service.restore(id))
                            assertPresent(source, directory, name)
                        }
                    }
                }
            }
        }

        assertEquals(60, caseNumber)
    }

    @Test
    fun `restore conflicts rather than overwriting a recreated path`() = runBlocking {
        val root = createTempDirectory("workspace-restore-conflict")
        Files.writeString(root.resolve("item.txt"), "original")
        val service = FileWorkspaceService(root)
        val id = assertIs<ApiResult.Success<io.github.spectordev9.opencode.core.common.TrashId>>(
            service.trash("/item.txt"),
        ).value
        Files.writeString(root.resolve("item.txt"), "new")

        val result = service.restore(id)

        assertEquals(ErrorCode.E_CONFLICT, assertIs<ApiResult.Failure>(result).error.code)
        assertEquals("new", (root / "item.txt").readText())
    }

    @Test
    fun `symlinks are visible but cannot be followed for reads or writes`() = runBlocking {
        val root = createTempDirectory("workspace-symlink")
        val outside = createTempDirectory("workspace-outside")
        (outside / "secret.txt").writeBytes("secret".toByteArray())
        Files.createSymbolicLink(root.resolve("escape"), outside)
        Files.createSymbolicLink(root.resolve("loop"), root.resolve("loop"))
        val service = FileWorkspaceService(root)

        val listed = assertIs<ApiResult.Success<List<Entry>>>(service.list("/")).value
        assertEquals(EntryKind.SYMLINK, listed.single { it.name == "escape" }.kind)
        assertIs<ApiResult.Success<Entry>>(service.stat("/escape"))
        assertEquals(
            ErrorCode.E_PATH_DENIED,
            assertIs<ApiResult.Failure>(service.readRange("/escape/secret.txt", 0, 10)).error.code,
        )
        assertEquals(
            ErrorCode.E_PATH_DENIED,
            assertIs<ApiResult.Failure>(
                service.write("/escape/new.txt", byteArrayOf(1), Precondition.None),
            ).error.code,
        )
        assertEquals("secret", (outside / "secret.txt").readText())
    }

    @Test
    fun `rejects traversal and noncanonical paths in a 100000 case safety run`() = runBlocking {
        val root = createTempDirectory("workspace-path-fuzz")
        val outside = createTempDirectory("workspace-path-fuzz-outside")
        (outside / "sentinel").writeBytes("unchanged".toByteArray())
        val service = FileWorkspaceService(root)
        val nonNormalized = "e\u0301.txt"

        repeat(100_000) { index ->
            val candidate = when (index % 5) {
                0 -> "../outside/sentinel"
                1 -> "/../../outside/$index"
                2 -> "dir/../../../outside/$index"
                3 -> "nul\u0000$index"
                else -> nonNormalized
            }
            val result = service.stat(candidate)
            assertEquals(ErrorCode.E_PATH_DENIED, assertIs<ApiResult.Failure>(result).error.code)
        }
        assertEquals("unchanged", (outside / "sentinel").readText())
    }

    @Test
    fun `large files are read in bounded ranges`() = runBlocking {
        val root = createTempDirectory("workspace-large")
        val file = root / "large.bin"
        FileChannel.open(file, java.nio.file.StandardOpenOption.CREATE_NEW, WRITE).use { channel ->
            channel.position(100L * 1024 * 1024)
            channel.write(ByteBuffer.wrap(byteArrayOf(7)))
        }
        val result = FileWorkspaceService(root).readRange("/large.bin", 100L * 1024 * 1024 - 16, 64)
        val chunk = assertIs<ApiResult.Success<Chunk>>(result).value

        assertEquals(17, chunk.bytes.size)
        assertTrue(chunk.endOfFile)
    }

    @Test
    fun `non-NFC file names and reserved trash path are denied`() = runBlocking {
        val root = createTempDirectory("workspace-unicode")
        val service = FileWorkspaceService(root)

        val nfd = service.write("e\u0301.txt", byteArrayOf(1), Precondition.None)
        val trashPath = service.mkdir("/.opencode-trash")

        assertEquals(ErrorCode.E_PATH_DENIED, assertIs<ApiResult.Failure>(nfd).error.code)
        assertEquals(ErrorCode.E_PATH_DENIED, assertIs<ApiResult.Failure>(trashPath).error.code)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
