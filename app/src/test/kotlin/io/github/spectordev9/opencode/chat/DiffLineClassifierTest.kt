package io.github.spectordev9.opencode.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiffLineClassifierTest {
    @Test
    fun `classifies patch lines without treating file markers as changes`() {
        assertEquals(DiffLineKind.FILE_HEADER, classifyDiffLine("--- a/file.kt"))
        assertEquals(DiffLineKind.FILE_HEADER, classifyDiffLine("+++ b/file.kt"))
        assertEquals(DiffLineKind.HUNK, classifyDiffLine("@@ -1,2 +1,3 @@"))
        assertEquals(DiffLineKind.ADDED, classifyDiffLine("+new line"))
        assertEquals(DiffLineKind.REMOVED, classifyDiffLine("-old line"))
        assertEquals(DiffLineKind.CONTEXT, classifyDiffLine(" unchanged"))
    }
}
