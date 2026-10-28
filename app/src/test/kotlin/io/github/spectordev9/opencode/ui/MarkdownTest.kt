package io.github.spectordev9.opencode.ui

import io.github.spectordev9.opencode.core.model.FileEntry
import io.github.spectordev9.opencode.core.model.FileEntryType
import io.github.spectordev9.opencode.chat.FilesUiState
import io.github.spectordev9.opencode.chat.flattenFileTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {

    @Test
    fun headingsParseWithLevels() {
        val blocks = parseMarkdownBlocks("# Title\n\n## Section\n\n### Sub")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Heading(2, "Section"),
                MdBlock.Heading(3, "Sub"),
            ),
            blocks,
        )
    }

    @Test
    fun fencedCodeCapturesLanguageAndBody() {
        val blocks = parseMarkdownBlocks("```kotlin\nval x = 1\nval y = 2\n```")
        assertEquals(listOf(MdBlock.Code("kotlin", "val x = 1\nval y = 2")), blocks)
    }

    @Test
    fun unclosedFenceKeepsBody() {
        val blocks = parseMarkdownBlocks("```\npartial block")
        assertEquals(listOf(MdBlock.Code(null, "partial block")), blocks)
    }

    @Test
    fun listsParseOrderedAndUnordered() {
        val blocks = parseMarkdownBlocks("- alpha\n1. beta\n2. gamma")
        assertEquals(
            listOf(
                MdBlock.ListItem(false, "-", "alpha"),
                MdBlock.ListItem(true, "1.", "beta"),
                MdBlock.ListItem(true, "2.", "gamma"),
            ),
            blocks,
        )
    }

    @Test
    fun tablesParseHeadersAndRows() {
        val blocks = parseMarkdownBlocks("| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(listOf(MdBlock.Table(listOf("A", "B"), listOf(listOf("1", "2"), listOf("3", "4")))), blocks)
    }

    @Test
    fun paragraphsAccumulateAcrossLines() {
        val blocks = parseMarkdownBlocks("first line\nsecond line\n\nafter break")
        assertEquals(
            listOf(
                MdBlock.Paragraph("first line\nsecond line"),
                MdBlock.Paragraph("after break"),
            ),
            blocks,
        )
    }

    @Test
    fun inlineSpansCoverAllStyles() {
        val spans = parseInlineMarkdown("**bold** `code` *it* ~~gone~~ [t](http://x)")
        assertTrue(spans[0] is MdSpan.Bold)
        assertTrue(spans[1] is MdSpan.Code)
        assertTrue(spans[2] is MdSpan.Italic)
        assertTrue(spans[3] is MdSpan.Strike)
        val link = spans[4] as? MdSpan.Link
        assertEquals("t", link?.text)
        assertEquals("http://x", link?.url)
    }

    @Test
    fun inlinePlainTextPassesThrough() {
        val spans = parseInlineMarkdown("no markup here")
        assertEquals(listOf<MdSpan>(MdSpan.Plain("no markup here")), spans)
    }

    @Test
    fun treeFlattenFollowsExpansions() {
        fun entry(name: String, path: String, dir: Boolean) = FileEntry(
            name = name,
            path = path,
            absolute = "/workspace/$path",
            type = if (dir) FileEntryType.DIRECTORY else FileEntryType.FILE,
            ignored = false,
        )
        val root = listOf(entry("app", "app", dir = true), entry("README.md", "README.md", dir = false))
        val kids = listOf(entry("build.gradle.kts", "app/build.gradle.kts", dir = false))

        val collapsed = flattenFileTree(root, FilesUiState())
        assertEquals(2, collapsed.size)
        assertEquals(0, collapsed[0].depth)

        val expanded = flattenFileTree(
            root,
            FilesUiState(
                expanded = setOf("app"),
                children = mapOf("app" to kids),
            ),
        )
        assertEquals(3, expanded.size)
        assertEquals("app/build.gradle.kts", expanded[1].entry.path)
        assertEquals(1, expanded[1].depth)
    }
}
