package io.github.spectordev9.opencode.chat

enum class DiffLineKind {
    ADDED,
    REMOVED,
    HUNK,
    FILE_HEADER,
    CONTEXT,
}

internal fun classifyDiffLine(line: String): DiffLineKind = when {
    line.startsWith("+++") || line.startsWith("---") ||
        line.startsWith("diff ") || line.startsWith("index ") ||
        line.startsWith("new file mode ") || line.startsWith("deleted file mode ") ->
        DiffLineKind.FILE_HEADER
    line.startsWith("@@") -> DiffLineKind.HUNK
    line.startsWith("+") -> DiffLineKind.ADDED
    line.startsWith("-") -> DiffLineKind.REMOVED
    else -> DiffLineKind.CONTEXT
}
