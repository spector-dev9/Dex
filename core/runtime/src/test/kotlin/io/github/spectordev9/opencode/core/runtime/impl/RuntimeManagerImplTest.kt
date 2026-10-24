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
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeManagerImplTest {
    @Test
    fun `artifact verifier accepts a matching sha-256 pin`() {
        val archive = createTempDirectory("runtime-archive") / "runtime.tar.gz"
        val bytes = "verified runtime archive".toByteArray()
        archive.writeBytes(bytes)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        val result = RuntimeArtifactVerifier().verify(archive, expected)

        val verified = assertIs<ApiResult.Success<VerifiedRuntimeArtifact>>(result).value
        assertEquals(expected, verified.sha256)
        assertEquals(bytes.size.toLong(), verified.sizeBytes)
        assertEquals(bytes.toList(), verified.path.readBytes().toList())
    }

    @Test
    fun `artifact verifier rejects a mismatched sha-256 pin`() {
        val archive = createTempDirectory("runtime-archive-mismatch") / "runtime.tar.gz"
        archive.writeBytes("tampered runtime archive".toByteArray())

        val result = RuntimeArtifactVerifier().verify(archive, "a".repeat(64))

        assertIs<ApiResult.Failure>(result)
    }

    @Test
    fun `ensureReady succeeds with a valid manifest and required files`() = runBlocking {
        val root = createTempDirectory("runtime-manager")
        val installed = Files.createDirectory(root / "environment")
        (installed / "opencode").writeBytes("binary".toByteArray())
        (installed / "ld-musl-aarch64.so.1").writeBytes("loader".toByteArray())
        writeManifest(root)

        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())
        val result = manager.ensureReady()

        assertIs<ApiResult.Success<RuntimeInfo>>(result)
        assertEquals(RuntimeStateStatus.READY, manager.state.value.status)
        assertEquals("1.19.3", result.value.version)
        assertEquals(RuntimeProfile.A_DIRECT, result.value.profile)
        assertEquals(installed.toRealPath().toString(), result.value.rootPath)
    }

    @Test
    fun `ensureReady rejects an extracted binary that does not match its pinned digest`() = runBlocking {
        val root = createTempDirectory("runtime-binary-mismatch")
        val installed = Files.createDirectory(root / "environment")
        (installed / "opencode").writeBytes("tampered binary".toByteArray())
        (installed / "ld-musl-aarch64.so.1").writeBytes("loader".toByteArray())
        writeManifest(root)

        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())
        val result = manager.ensureReady()

        assertIs<ApiResult.Failure>(result)
        assertEquals(RuntimeStateStatus.FAILED, manager.state.value.status)
        assertEquals("OpenCode binary sha-256 mismatch", manager.state.value.reason)
    }

    @Test
    fun `manifest rejects absolute required file paths`() = runBlocking {
        val root = createTempDirectory("runtime-unsafe-manifest")
        val installed = Files.createDirectory(root / "environment")
        (installed / "opencode").writeBytes("binary".toByteArray())
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
        val installed = Files.createDirectory(root / "environment")
        (installed / "opencode").writeBytes("binary".toByteArray())
        (installed / "ld-musl-aarch64.so.1").createSymbolicLinkPointingTo(outside)
        writeManifest(root, required = """["opencode", "ld-musl-aarch64.so.1"]""")

        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())
        val result = manager.ensureReady()

        assertIs<ApiResult.Failure>(result)
        assertEquals(RuntimeStateStatus.FAILED, manager.state.value.status)
    }

    @Test
    fun `ensureReady rejects an active version directory that escapes the runtime root`() = runBlocking {
        val root = createTempDirectory("runtime-active-version")
        val binary = "binary".toByteArray()
        val digest = sha256(binary)
        val outside = createTempDirectory("runtime-version-outside")
        (outside / "opencode").writeBytes(binary)
        Files.createDirectory(root / "versions")
        Files.createSymbolicLink(root / "versions" / digest, outside)
        (root / "active-runtime").writeBytes(digest.toByteArray())
        writeManifest(root, binaryHash = digest)

        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())
        val result = manager.ensureReady()

        assertIs<ApiResult.Failure>(result)
        assertEquals(RuntimeStateStatus.FAILED, manager.state.value.status)
        assertTrue(manager.state.value.reason.orEmpty().contains("outside the runtime root"))
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
        val installed = Files.createDirectory(root / "environment")
        (installed / "opencode").writeBytes("binary".toByteArray())
        (installed / "ld-musl-aarch64.so.1").writeBytes("loader".toByteArray())
        writeManifest(root)
        val launcher = FakeRuntimeProcessLauncher()
        val manager = ManifestRuntimeManager(root, launcher)
        manager.ensureReady()

        val spec = ProcessSpec("opencode", workingDirectory = installed.toString())
        val result = manager.spawn(spec)

        assertIs<ApiResult.Success<ManagedProcess>>(result)
        assertEquals(spec, launcher.requestedSpec)
    }

    @Test
    fun `installArtifact installs from pinned manifest and transitions to ready`() = runBlocking {
        val root = createTempDirectory("runtime-install-manager")
        val archive = root / "opencode.tar.gz"
        archive.writeBytes("archive bytes".toByteArray())
        val executable = "binary".toByteArray()
        writeManifest(
            root,
            artifactHash = sha256(archive.readBytes()),
            binaryHash = sha256(executable),
        )
        val installer = RuntimeArtifactInstaller(
            extractor = RuntimeArchiveExtractor { _, destination ->
                (destination / "opencode").writeBytes(executable)
            },
        )
        val manager = ManifestRuntimeManager(
            rootDirectory = root,
            processLauncher = FakeRuntimeProcessLauncher(),
            installer = installer,
        )

        val result = manager.installArtifact(archive)

        val runtime = assertIs<ApiResult.Success<RuntimeInfo>>(result).value
        assertEquals(RuntimeStateStatus.READY, manager.state.value.status)
        assertEquals("1.19.3", runtime.version)
        val installedVersion = root / "versions" / sha256(executable)
        assertTrue(Files.isRegularFile(installedVersion / "opencode"))
        assertEquals(sha256(executable), (root / "active-runtime").readText())
        assertEquals(installedVersion.toRealPath().toString(), runtime.rootPath)
    }

    @Test
    fun `installPinnedArtifact downloads the manifest pin before verified installation`() = runBlocking {
        val root = createTempDirectory("runtime-pinned-install")
        val archive = "pinned official archive".toByteArray()
        val executable = "verified OpenCode binary".toByteArray()
        val url = "https://runtime.test/opencode.tar.gz"
        writeManifest(
            root,
            artifactHash = sha256(archive),
            binaryHash = sha256(executable),
            artifactUrl = url,
        )
        val connection = FakeHttpsConnection(URI(url).toURL()) {
            bytes = archive
        }
        val downloader = RuntimeArtifactDownloader(
            connectionFactory = HttpsConnectionFactory { connection },
        )
        val installer = RuntimeArtifactInstaller(
            extractor = RuntimeArchiveExtractor { _, destination ->
                (destination / "opencode").writeBytes(executable)
            },
        )
        val manager = ManifestRuntimeManager(
            rootDirectory = root,
            processLauncher = FakeRuntimeProcessLauncher(),
            installer = installer,
            artifactDownloader = downloader,
        )

        val result = manager.installPinnedArtifact()

        val runtime = assertIs<ApiResult.Success<RuntimeInfo>>(result).value
        assertEquals("1.19.3", runtime.version)
        assertEquals(RuntimeStateStatus.READY, manager.state.value.status)
        assertTrue(connection.disconnected)
        assertEquals(
            archive.toList(),
            (root / "downloads" / "opencode-${sha256(archive)}.tar.gz").readBytes().toList(),
        )
    }

    @Test
    fun `installArtifact keeps valid installed runtime when new archive verification fails`() = runBlocking {
        val root = createTempDirectory("runtime-install-invalid-archive")
        val installed = Files.createDirectory(root / "environment")
        (installed / "opencode").writeBytes("binary".toByteArray())
        (installed / "ld-musl-aarch64.so.1").writeBytes("loader".toByteArray())
        writeManifest(root)
        val archive = root / "bad.tar.gz"
        archive.writeBytes("tampered".toByteArray())
        val manager = ManifestRuntimeManager(root, FakeRuntimeProcessLauncher())

        val result = manager.installArtifact(archive)

        assertIs<ApiResult.Failure>(result)
        assertEquals(RuntimeStateStatus.READY, manager.state.value.status)
        assertEquals("binary", (installed / "opencode").readText())
    }

    @Test
    fun `installAlpineRootfs uses the trusted manifest pin and restores runtime state`() = runBlocking {
        val root = createTempDirectory("runtime-manager-alpine")
        val archive = root / "alpine.tar.gz"
        archive.writeBytes("pinned Alpine archive".toByteArray())
        val archiveHash = sha256(archive.readBytes())
        writeManifest(root, alpineJson = """,
          "alpine": {"version": "3.20.3", "sha256": "$archiveHash"}""")
        val installer = AlpineRootfsInstaller(
            extractor = RuntimeArchiveExtractor { _, destination ->
                Files.createDirectories(destination.resolve("bin"))
                listOf("sh", "busybox").forEach { name ->
                    val executable = Files.writeString(destination.resolve("bin").resolve(name), name)
                    executable.toFile().setExecutable(true)
                }
            },
        )
        val manager = ManifestRuntimeManager(
            rootDirectory = root,
            processLauncher = FakeRuntimeProcessLauncher(),
            alpineInstaller = installer,
        )

        val result = manager.installAlpineRootfs(archive)

        val installed = assertIs<ApiResult.Success<java.nio.file.Path>>(result).value
        assertEquals(
            root.resolve("rootfs-versions").resolve(archiveHash),
            installed,
        )
        assertEquals(RuntimeStateStatus.ABSENT, manager.state.value.status)
        assertEquals("sh", Files.readString(installed.resolve("bin/sh")))
    }

    private fun writeManifest(
        root: java.nio.file.Path,
        required: String = """["opencode"]""",
        artifactHash: String = "a".repeat(64),
        binaryHash: String = sha256("binary".toByteArray()),
        artifactUrl: String = "https://runtime.test/opencode.tar.gz",
        alpineJson: String = "",
    ) {
        (root / "manifest.json").writeBytes(
            """
            {
              "version": "1.19.3",
              "profile": "A_DIRECT",
              "binary": "opencode",
              "binarySha256": "$binaryHash",
              "required": $required,
              "artifact": {"url": "$artifactUrl", "sha256": "$artifactHash"}$alpineJson
            }
            """.trimIndent().toByteArray(),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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
