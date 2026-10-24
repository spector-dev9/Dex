package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ProcessId
import io.github.spectordev9.opencode.core.runtime.ManagedProcess
import io.github.spectordev9.opencode.core.runtime.ProcessExit
import io.github.spectordev9.opencode.core.runtime.ProcessSpec
import io.github.spectordev9.opencode.core.runtime.RuntimeInfo
import io.github.spectordev9.opencode.core.runtime.RuntimeProfile
import io.github.spectordev9.opencode.core.runtime.RuntimeStateStatus
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.writeBytes
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeManagerImplTest {
    @Test
    fun `ensureReady succeeds with a valid manifest and required files`() = runBlocking {
        val root = createTempDirectory("runtime-manager")
        (root / "opencode").writeBytes("binary".toByteArray())
        (root / "ld-musl-aarch64.so.1").writeBytes("loader".toByteArray())
        writeManifest(root)

        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())
        val result = manager.ensureReady()

        assertIs<ApiResult.Success<RuntimeInfo>>(result)
        assertEquals(RuntimeStateStatus.READY, manager.state.value.status)
        assertEquals("1.19.3", result.value.version)
        assertEquals(RuntimeProfile.A_DIRECT, result.value.profile)
        assertTrue(result.value.rootPath.startsWith(root.toString()))
    }

    @Test
    fun `manifest rejects absolute required file paths`() = runBlocking {
        val root = createTempDirectory("runtime-unsafe-manifest")
        (root / "opencode").writeBytes("binary".toByteArray())
        writeManifest(root, required = """["opencode", "/tmp/outside"]""")

        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())
        val result = manager.ensureReady()

        assertIs<ApiResult.Failure>(result)
        assertEquals(RuntimeStateStatus.FAILED, manager.state.value.status)
    }

    @Test
    fun `ensureReady rejects required files that escape the runtime root`() = runBlocking {
        val root = createTempDirectory("runtime-symlink")
        val outside = createTempDirectory("runtime-outside") / "external"
        outside.writeBytes("external".toByteArray())
        (root / "opencode").writeBytes("binary".toByteArray())
        (root / "ld-musl-aarch64.so.1").createSymbolicLinkPointingTo(outside)
        writeManifest(root)

        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())
        val result = manager.ensureReady()

        assertIs<ApiResult.Failure>(result)
        assertEquals(RuntimeStateStatus.FAILED, manager.state.value.status)
    }

    @Test
    fun `spawn requires runtime readiness and delegates through launcher`() {
        val launcher = FakeRuntimeProcessLauncher()
        val manager = ManifestRuntimeManager(createTempDirectory("runtime-spawn"), launcher)
        val result = manager.spawn(ProcessSpec("/bin/sh", workingDirectory = "/tmp"))

        assertIs<ApiResult.Failure>(result)
        assertTrue(launcher.requestedSpec == null)
    }

    @Test
    fun `spawn delegates only after runtime is ready`() = runBlocking {
        val root = createTempDirectory("runtime-spawn-ready")
        (root / "opencode").writeBytes("binary".toByteArray())
        (root / "ld-musl-aarch64.so.1").writeBytes("loader".toByteArray())
        writeManifest(root)
        val launcher = FakeRuntimeProcessLauncher()
        val manager = ManifestRuntimeManager(root, launcher)
        manager.ensureReady()

        val spec = ProcessSpec("opencode", workingDirectory = root.toString())
        val result = manager.spawn(spec)

        assertIs<ApiResult.Success<ManagedProcess>>(result)
        assertEquals(spec, launcher.requestedSpec)
    }

    private fun writeManifest(root: java.nio.file.Path, required: String = """["opencode", "ld-musl-aarch64.so.1"]""") {
        (root / "manifest.json").writeBytes(
            """
            {
              "version": "1.19.3",
              "profile": "A_DIRECT",
              "binary": "opencode",
              "required": $required,
              "artifact": {"sha256": "${"a".repeat(64)}"}
            }
            """.trimIndent().toByteArray(),
        )
    }

    private class FakeRuntimeProcessLauncher : RuntimeProcessLauncher {
        var requestedSpec: ProcessSpec? = null

        override fun launch(spec: ProcessSpec): ApiResult<ManagedProcess> {
            requestedSpec = spec
            return ApiResult.Success(FakeManagedProcess())
        }
    }

    private class FakeManagedProcess : ManagedProcess {
        override val id = ProcessId("test-process")
        override val pid: Long? = 1
        override val isAlive: Boolean = true

        override fun signal(signal: Int): ApiResult<Unit> = ApiResult.Success(Unit)

        override suspend fun waitFor(): ProcessExit = ProcessExit(exitCode = 0)

        override suspend fun terminate(graceMillis: Long): ApiResult<Unit> = ApiResult.Success(Unit)
    }
}
