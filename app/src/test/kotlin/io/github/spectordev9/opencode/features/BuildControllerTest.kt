package io.github.spectordev9.opencode.features

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BuildControllerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `scans common artifacts and ignores unrelated files`() {
        Files.createDirectories(directory.resolve("app/build/outputs/apk/debug"))
        Files.createDirectories(directory.resolve("node_modules/ignored"))
        Files.write(directory.resolve("app/build/outputs/apk/debug/app-debug.apk"), ByteArray(16))
        Files.write(directory.resolve("app/build/outputs/apk/debug/output-metadata.json"), ByteArray(4))
        Files.write(directory.resolve("node_modules/ignored/package.jar"), ByteArray(8))

        val artifacts = scanBuildArtifacts(directory)

        assertTrue(artifacts.any { it.path == "app/build/outputs/apk/debug/app-debug.apk" })
        assertTrue(artifacts.any { it.path == "node_modules/ignored/package.jar" })
        assertFalse(artifacts.any { it.path.endsWith(".json") })
    }

    @Test
    fun `rejects missing and symlinked artifact roots`() {
        assertTrue(scanBuildArtifacts(directory.resolve("missing")).isEmpty())
        val target = directory.resolve("outside")
        Files.createDirectories(target)
        val link = directory.resolve("link")
        runCatching { Files.createSymbolicLink(link, target) }
        assertTrue(scanBuildArtifacts(link).isEmpty())
    }

    @Test
    fun `compatibility evaluation explains unsupported devices`() {
        val unsupported = DeviceCompatibility.evaluate(
            supportedAbis = listOf("x86_64"),
            sdkInt = 25,
            freeStorageBytes = 0,
            totalMemoryBytes = 0,
            runtimeInstalled = false,
        )
        assertFalse(unsupported.supported)
        assertEquals(4, unsupported.reasons.size)
        assertFalse(unsupported.arm64)
        assertFalse(unsupported.androidBuildSupported)

        val supported = DeviceCompatibility.evaluate(
            supportedAbis = listOf("arm64-v8a"),
            sdkInt = 34,
            freeStorageBytes = 9L * 1024 * 1024 * 1024,
            totalMemoryBytes = 8L * 1024 * 1024 * 1024,
            runtimeInstalled = true,
        )
        assertTrue(supported.supported)
        assertTrue(supported.androidBuildSupported)
        assertTrue(supported.reasons.isEmpty())
    }
}
