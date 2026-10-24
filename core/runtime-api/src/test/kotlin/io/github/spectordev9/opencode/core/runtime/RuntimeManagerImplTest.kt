package io.github.spectordev9.opencode.core.runtime

import io.github.spectordev9.opencode.core.common.ApiResult
import kotlinx.coroutines.runBlocking
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeManagerImplTest {
    @Test
    fun `ensureReady succeeds when manifest and required files are valid`() = runBlocking {
        val root = createTempDirectory("runtime-manager")
        val binary = root / "opencode"
        binary.writeBytes("binary".toByteArray())
        val loader = root / "ld-musl-aarch64.so.1"
        loader.writeBytes("loader".toByteArray())

        val manifest = root / "manifest.json"
        manifest.writeBytes(
            """
            {
              "version": "1.19.3",
              "profile": "A_DIRECT",
              "binary": "opencode",
              "required": ["opencode", "ld-musl-aarch64.so.1"]
            }
            """.trimIndent().toByteArray(),
        )

        val manager = ManifestRuntimeManager(root)
        val result = manager.ensureReady()
        assertIs<ApiResult.Success<RuntimeInfo>>(result)
        assertEquals(RuntimeStateStatus.READY, manager.state.value.status)
        assertEquals("1.19.3", result.value.version)
        assertEquals(RuntimeProfile.A_DIRECT, result.value.profile)
        assertTrue(result.value.rootPath.startsWith(root.toString()))
    }

    @Test
    fun `spawn executes a process and returns a process exit`() = runBlocking {
        val manager = ManifestRuntimeManager(createTempDirectory("runtime-spawn"))
        val process = manager.spawn(
            ProcessSpec(
                executable = "/bin/sh",
                arguments = listOf("-c", "printf ok"),
                workingDirectory = "/tmp",
            ),
        )

        assertIs<ApiResult.Success<ManagedProcess>>(process)
        val exit = process.value.waitFor()
        assertEquals(0, exit.exitCode)
    }
}
