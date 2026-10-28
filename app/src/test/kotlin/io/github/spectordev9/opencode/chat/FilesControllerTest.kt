package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.app.bridge.FakeOpenCodeClient
import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.FileEntryType
import io.github.spectordev9.opencode.core.opencode.client.OpenCodeClient
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilesControllerTest {

    @Test
    fun `workspace mutations create rename and trash entries`() = withController { controller, root ->
        controller.attach(client(), root.toString())
        controller.createDirectory("src")
        controller.open(
            FileEntry(
                name = "src",
                path = "src",
                absolute = root.resolve("src").toString(),
                type = FileEntryType.DIRECTORY,
                ignored = false,
            ),
        )
        controller.createFile("Main.kt")

        assertTrue(Files.isDirectory(root.resolve("src")))
        assertTrue(Files.isRegularFile(root.resolve("src/Main.kt")))

        controller.rename("src/Main.kt", "App.kt")
        assertFalse(Files.exists(root.resolve("src/Main.kt")))
        assertTrue(Files.exists(root.resolve("src/App.kt")))

        controller.trash("src/App.kt")
        assertFalse(Files.exists(root.resolve("src/App.kt")))
        assertTrue(Files.exists(root.resolve(".opencode-trash")))
    }

    @Test
    fun `search finds names and content with line numbers and extension filter`() = withController { controller, root ->
        Files.createDirectories(root.resolve("src"))
        Files.write(root.resolve("src/Main.kt"), "package sample\nfun needle() = true\n".toByteArray())
        Files.write(root.resolve("README.md"), "needle in documentation\n".toByteArray())
        controller.attach(client(), root.toString())

        controller.search("needle", "kt")

        val results = controller.state.value.searchResults
        assertEquals(1, results.size)
        assertEquals("src/Main.kt", results.single().path)
        assertEquals(2, results.single().line)
        assertTrue(results.single().snippet.orEmpty().contains("needle"))
        assertFalse(controller.state.value.searching)
    }

    @Test
    fun `editor saves only when original file has not changed`() = withController { controller, root ->
        val file = root.resolve("main.kt")
        Files.write(file, "val version = 1\n".toByteArray())
        controller.attach(client(), root.toString())
        controller.open(fileEntry(root, "main.kt"))

        assertEquals("val version = 1\n", controller.state.value.previewText)
        controller.editPreview()
        controller.updatePreviewText("val version = 2\n")
        Files.write(file, "val version = 3\n".toByteArray())

        controller.savePreviewEdit()

        assertEquals("val version = 3\n", Files.readAllBytes(file).toString(Charsets.UTF_8))
        assertTrue(controller.state.value.error.orEmpty().contains("changed"))
        assertTrue(controller.state.value.editingPreview)
    }

    @Test
    fun `editor saves changes atomically when precondition matches`() = withController { controller, root ->
        val file = root.resolve("main.kt")
        Files.write(file, "val version = 1\n".toByteArray())
        controller.attach(client(), root.toString())
        controller.open(fileEntry(root, "main.kt"))
        controller.editPreview()
        controller.updatePreviewText("val version = 2\n")

        controller.savePreviewEdit()

        assertEquals("val version = 2\n", Files.readAllBytes(file).toString(Charsets.UTF_8))
        assertFalse(controller.state.value.editingPreview)
        assertEquals(null, controller.state.value.error)
    }

    private fun withController(block: suspend (FilesController, java.nio.file.Path) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("opencode-files-test")
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = FilesController(scope)
            block(controller, root)
        } finally {
            scope.cancel()
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun client(): OpenCodeClient = FakeOpenCodeClient()

    private fun fileEntry(root: java.nio.file.Path, relativePath: String) = FileEntry(
        name = relativePath.substringAfterLast('/'),
        path = relativePath,
        absolute = root.resolve(relativePath).toString(),
        type = FileEntryType.FILE,
        ignored = false,
    )
}
