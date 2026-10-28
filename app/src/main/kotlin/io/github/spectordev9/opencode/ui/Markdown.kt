package io.github.spectordev9.opencode.ui

/**
 * Lightweight markdown model + parser (F02). Pure Kotlin so it is unit
 * testable without a device. Supports the subset an assistant emits in
 * practice: headings, paragraphs, fenced code, lists, blockquotes, tables
 * and horizontal rules.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val inline: String) : MdBlock

    data class Paragraph(val inline: String) : MdBlock

    data class Code(val language: String?, val code: String) : MdBlock

    data class ListItem(val ordered: Boolean, val marker: String, val inline: String) : MdBlock

    data class Blockquote(val inline: String) : MdBlock

    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock

    data object Rule : MdBlock
}

fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var index = 0
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val body = paragraph.toString().trim()
        paragraph.clear()
        if (body.isNotEmpty()) blocks += MdBlock.Paragraph(body)
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        when {
            trimmed.isBlank() -> {
                flushParagraph()
                index++
            }

            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flushParagraph()
                val fence = trimmed.take(3)
                val language = trimmed.drop(3).trim().takeIf { it.isNotEmpty() && !it.contains(' ') }
                val body = StringBuilder()
                index++
                while (index < lines.size) {
                    val inner = lines[index]
                    if (inner.trim().startsWith(fence)) {
                        index++
                        break
                    }
                    body.appendLine(inner)
                    index++
                }
                blocks += MdBlock.Code(language, body.toString().trimEnd('\n'))
            }

            headingRegex.matchesAt(trimmed, 0) -> {
                flushParagraph()
                val match = headingRegex.matchAt(trimmed, 0)!!
                blocks += MdBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
                index++
            }

            trimmed.startsWith("|") && index + 1 < lines.size && tableSeparatorRegex.matches(lines[index + 1].trim()) -> {
                flushParagraph()
                val headers = tableCells(trimmed)
                index += 2
                val rows = mutableListOf<List<String>>()
                while (index < lines.size) {
                    val rowLine = lines[index].trim()
                    if (!rowLine.startsWith("|")) break
                    rows += tableCells(rowLine)
                    index++
                }
                blocks += MdBlock.Table(headers, rows)
            }

            horizontalRuleRegex.matches(trimmed) -> {
                flushParagraph()
                blocks += MdBlock.Rule
                index++
            }

            blockquoteRegex.matchesAt(trimmed, 0) -> {
                flushParagraph()
                val match = blockquoteRegex.matchAt(trimmed, 0)!!
                blocks += MdBlock.Blockquote(match.groupValues[1].trim())
                index++
            }

            listRegex.matchesAt(trimmed, 0) -> {
                flushParagraph()
                val match = listRegex.matchAt(trimmed, 0)!!
                val marker = match.groupValues[1]
                val ordered = marker.first().isDigit()
                blocks += MdBlock.ListItem(ordered, marker, match.groupValues[2].trim())
                index++
            }

            else -> {
                paragraph.appendLine(line)
                index++
            }
        }
    }
    flushParagraph()
    return blocks
}

private val headingRegex = Regex("(#{1,6})\\s+(.*)")
private val blockquoteRegex = Regex(">\\s?(.*)")
private val listRegex = Regex("([-*+]|\\d+[.)])\\s+(.*)")
private val horizontalRuleRegex = Regex("(?:-{3,}|\\*{3,}|_{3,})")
private val tableSeparatorRegex = Regex("\\|?(?:\\s*:?-{2,}:?\\s*\\|)+\\s*:?-{2,}:?\\s*\\|?")

private fun tableCells(line: String): List<String> {
    val body = line.removeSurrounding("|")
    return body.split('|').map { it.trim() }
}

/** Parsed inline markdown runs, resolved to styled text without Android deps. */
sealed interface MdSpan {
    data class Plain(val text: String) : MdSpan

    data class Bold(val text: String) : MdSpan

    data class Italic(val text: String) : MdSpan

    data class Strike(val text: String) : MdSpan

    data class Code(val text: String) : MdSpan

    data class Link(val text: String, val url: String) : MdSpan
}

private val inlineRegex = Regex(
    "`([^`\\n]+)`" +              // 1: inline code
        "|\\*\\*(.+?)\\*\\*" +        // 2: bold
        "|__(.+?)__" +                // 3: bold alt
        "|~~(.+?)~~" +                // 4: strike
        "|\\*([^*\\n]+)\\*" +         // 5: italic
        "|\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", // 6: text, 7: url
)

fun parseInlineMarkdown(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    var cursor = 0
    for (match in inlineRegex.findAll(text)) {
        if (match.range.first > cursor) {
            spans += MdSpan.Plain(text.substring(cursor, match.range.first))
        }
        val groups = match.groupValues
        when {
            groups[1].isNotEmpty() -> spans += MdSpan.Code(groups[1])
            groups[2].isNotEmpty() -> spans += MdSpan.Bold(groups[2])
            groups[3].isNotEmpty() -> spans += MdSpan.Bold(groups[3])
            groups[4].isNotEmpty() -> spans += MdSpan.Strike(groups[4])
            groups[5].isNotEmpty() -> spans += MdSpan.Italic(groups[5])
            groups[6].isNotEmpty() -> spans += MdSpan.Link(groups[6], groups[7])
            else -> spans += MdSpan.Plain(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) spans += MdSpan.Plain(text.substring(cursor))
    return spans
}
