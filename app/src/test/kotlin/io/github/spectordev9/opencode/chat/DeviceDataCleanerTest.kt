package io.github.spectordev9.opencode.chat

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeviceDataCleanerTest {
    @Test
    fun `deleteTree removes nested files and reports bytes`(@TempDir root: File) {
        val nested = File(root, "a/b").apply { mkdirs() }
        File(nested, "one.txt").writeText("12345")
        File(root, "two.bin").writeBytes(ByteArray(10))
        File(root, "empty-dir").mkdir()

        val freed = DeviceDataCleaner.deleteTree(root)

        assertEquals(15L, freed)
        assertFalse(File(root, "two.bin").exists())
        assertFalse(nested.exists())
        assertFalse(File(root, "empty-dir").exists())
    }

    @Test
    fun `deleteTree tolerates missing roots`(@TempDir root: File) {
        assertEquals(0L, DeviceDataCleaner.deleteTree(File(root, "missing")))
        assertEquals(0L, DeviceDataCleaner.deleteTree(null))
    }
}
