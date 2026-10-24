package io.github.spectordev9.opencode.spike

import android.content.Context
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.runtime.impl.AlpineRootfsInstaller
import io.github.spectordev9.opencode.core.runtime.impl.AlpineRuntimePackageInstaller
import io.github.spectordev9.opencode.core.runtime.impl.AlpineRootfsTarGzExtractor
import io.github.spectordev9.opencode.core.runtime.impl.RuntimeArtifactInstaller
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

object SpikeRuntimeInstaller {
    private const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
    private val runtimePackageLibraries = mapOf(
        "libgcc" to "libgcc_s.so.1",
        "libstdc++" to "libstdc++.so.6",
    )

    fun install(
        context: Context,
        progress: (String, Long, Long) -> Unit,
    ): String {
        val nativeDirectory = Path.of(context.applicationInfo.nativeLibraryDir)
        listOf("libldmusl.so", "libproot.so", "libproot_loader.so").forEach { name ->
            if (!Files.isRegularFile(nativeDirectory.resolve(name), NOFOLLOW_LINKS)) {
                throw IOException(
                    "$name is not packaged in the APK; run scripts/stage-spike-harness.py and rebuild",
                )
            }
        }
        for (name in listOf("libtalloc.so.2", "libandroid-shmem.so")) {
            context.assets.open("runtime-libs/$name").use { }
        }

        val manifest = context.assets.open("runtime-manifest.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val version = manifest.getString("version")
        if (!version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            throw IOException("runtime manifest version is invalid")
        }

        val root = SpikeProbe.runtimeDir(context).toPath()
        ensureDirectory(root)
        val cache = root.resolve("downloads")
        ensureDirectory(cache)

        val artifact = manifest.getJSONObject("artifact")
        val alpine = manifest.getJSONObject("alpine")
        val packages = manifest.getJSONArray("alpineRuntimePackages")
        val nodeRuntime = manifest.getJSONObject("nodeRuntime")
        val nodePackages = nodeRuntime.getJSONArray("packages")
        val downloadSpecs = buildList {
            add(
                DownloadSpec(
                    item = "OpenCode $version",
                    url = artifact.getString("url"),
                    sha256 = artifact.getString("sha256"),
                    destination = cache.resolve("opencode-$version.tar.gz"),
                ),
            )
            add(
                DownloadSpec(
                    item = "Alpine ${alpine.getString("version")}",
                    url = alpine.getString("url"),
                    sha256 = alpine.getString("sha256"),
                    destination = cache.resolve("alpine-${alpine.getString("version")}.tar.gz"),
                ),
            )
            for (index in 0 until packages.length()) {
                val spec = packages.getJSONObject(index)
                val packageName = spec.getString("name")
                add(
                    DownloadSpec(
                        item = "Alpine package $packageName",
                        url = spec.getString("url"),
                        sha256 = spec.getString("sha256"),
                        destination = cache.resolve("$packageName-${spec.getString("version")}.apk"),
                    ),
                )
            }
            for (index in 0 until nodePackages.length()) {
                val spec = nodePackages.getJSONObject(index)
                val packageName = spec.getString("name")
                add(
                    DownloadSpec(
                        item = "Alpine Node package $packageName",
                        url = spec.getString("url"),
                        sha256 = spec.getString("sha256"),
                        destination = cache.resolve("$packageName-${spec.getString("version")}.apk"),
                    ),
                )
            }
        }
        val downloaded = downloadAll(downloadSpecs, progress)
        val openCodeArchive = downloaded.getValue("OpenCode $version")
        val runtimeDirectory = root.resolve("runtime")
        val installedOpenCode = when (
            val result = RuntimeArtifactInstaller().install(
                archive = openCodeArchive,
                expectedArchiveSha256 = artifact.getString("sha256"),
                targetDirectory = runtimeDirectory,
                executableName = manifest.getString("binary"),
                expectedExecutableSha256 = manifest.getString("binarySha256"),
            )
        ) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw IOException(result.error.message ?: "OpenCode installation failed")
        }

        val alpineArchive = downloaded.getValue("Alpine ${alpine.getString("version")}")
        val rootfs = root.resolve("rootfs")
        val installedAlpine = when (
            val result = AlpineRootfsInstaller().install(
                archive = alpineArchive,
                expectedArchiveSha256 = alpine.getString("sha256"),
                targetDirectory = rootfs,
            )
        ) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> throw IOException(result.error.message ?: "Alpine installation failed")
        }

        val packageEvidence = mutableListOf<String>()
        for (index in 0 until packages.length()) {
            val spec = packages.getJSONObject(index)
            val packageName = spec.getString("name")
            val library = runtimePackageLibraries[packageName]
                ?: throw IOException("unsupported pinned Alpine runtime package: $packageName")
            val archive = downloaded.getValue("Alpine package $packageName")
            installRuntimeLibraryPackage(packageName, library, archive, rootfs, cache)
            packageEvidence += "$packageName sha256=${spec.getString("sha256")} bytes=${Files.size(archive)}"
        }

        val nodePackageInstaller = AlpineRuntimePackageInstaller()
        for (index in 0 until nodePackages.length()) {
            val spec = nodePackages.getJSONObject(index)
            val packageName = spec.getString("name")
            val archive = downloaded.getValue("Alpine Node package $packageName")
            when (
                val result = nodePackageInstaller.install(
                    archive = archive,
                    expectedArchiveSha256 = spec.getString("sha256"),
                    packageName = packageName,
                    rootfsDirectory = rootfs,
                )
            ) {
                is ApiResult.Success -> Unit
                is ApiResult.Failure ->
                    throw IOException(result.error.message ?: "Alpine Node package installation failed")
            }
            packageEvidence += "$packageName sha256=${spec.getString("sha256")} bytes=${Files.size(archive)}"
        }
        val nodePath = rootfs.resolve(nodeRuntime.getString("executable"))
        if (!Files.isRegularFile(nodePath) || !Files.isExecutable(nodePath)) {
            throw IOException("pinned Alpine Node package did not install an executable $nodePath")
        }

        val runtimeLibraries = manifest.getJSONArray("runtimeLibraries")
        for (index in 0 until runtimeLibraries.length()) {
            val libraryName = runtimeLibraries.getString(index)
            if (!libraryName.matches(Regex("[A-Za-z0-9_.+-]+"))) {
                throw IOException("invalid runtime library name in manifest")
            }
            val matches = mutableListOf<Path>()
            val rootfsRealPath = rootfs.toRealPath()
            Files.walk(rootfs).use { paths ->
                paths.forEach { path ->
                    if (path.fileName?.toString() == libraryName && Files.isRegularFile(path)) {
                        val realPath = path.toRealPath()
                        if (realPath.startsWith(rootfsRealPath) && realPath !in matches) {
                            matches.add(realPath)
                        }
                    }
                }
            }
            if (matches.size != 1) {
                throw IOException("expected one $libraryName in the pinned Alpine rootfs, found ${matches.size}")
            }
            Files.copy(matches.single(), runtimeDirectory.resolve(libraryName), REPLACE_EXISTING)
        }

        copyHelperAsset(context, root, "libtalloc.so.2")
        copyHelperAsset(context, root, "libandroid-shmem.so")

        return listOf(
            "OpenCode $version archive sha256=${artifact.getString("sha256")} bytes=${Files.size(openCodeArchive)}",
            "OpenCode executable sha256=${installedOpenCode.sha256} bytes=${installedOpenCode.sizeBytes}",
            "Alpine ${alpine.getString("version")} archive sha256=${installedAlpine.archiveSha256} " +
                "bytes=${Files.size(alpineArchive)}",
            "Node ${nodeRuntime.getString("version")} installed at $nodePath",
        ).plus(packageEvidence).joinToString("\n")
    }

    private fun downloadAll(
        specs: List<DownloadSpec>,
        progress: (String, Long, Long) -> Unit,
    ): Map<String, Path> {
        val executor = Executors.newFixedThreadPool(minOf(specs.size, MAX_PARALLEL_DOWNLOADS).coerceAtLeast(1))
        val downloads = specs.associate { spec ->
            spec.item to executor.submit<Path> {
                downloadVerified(spec.item, spec.url, spec.sha256, spec.destination, progress)
            }
        }
        try {
            return downloads.mapValues { (_, future) ->
                try {
                    future.get()
                } catch (failure: ExecutionException) {
                    val cause = failure.cause ?: failure
                    if (cause is IOException) throw cause
                    throw IOException("runtime artifact download failed", cause)
                } catch (failure: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("runtime artifact download interrupted", failure)
                }
            }
        } finally {
            downloads.values.forEach { if (!it.isDone) it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private fun installRuntimeLibraryPackage(
        packageName: String,
        libraryName: String,
        archive: Path,
        rootfs: Path,
        cache: Path,
    ) {
        val extraction = Files.createTempDirectory(cache, ".$packageName-")
        try {
            AlpineRootfsTarGzExtractor().extract(archive, extraction)
            val source = extraction.resolve("usr/lib").resolve(libraryName)
            if (!Files.isRegularFile(source)) {
                throw IOException("$packageName archive does not contain usr/lib/$libraryName")
            }
            val resolvedSource = source.toRealPath()
            if (!resolvedSource.startsWith(extraction.toRealPath())) {
                throw IOException("$packageName library resolves outside its extracted archive")
            }

            val root = rootfs.toRealPath()
            val libraryDirectory = rootfs.resolve("usr/lib")
            Files.createDirectories(libraryDirectory)
            if (!libraryDirectory.toRealPath().startsWith(root)) {
                throw IOException("Alpine library directory resolves outside the rootfs")
            }
            val destination = libraryDirectory.resolve(libraryName)
            if (!destination.normalize().startsWith(rootfs.normalize())) {
                throw IOException("Alpine runtime library path escapes the rootfs")
            }
            Files.deleteIfExists(destination)
            Files.copy(resolvedSource, destination)
        } finally {
            deleteTree(extraction)
        }
    }

    private fun copyHelperAsset(context: Context, root: Path, name: String) {
        val destination = root.resolve(name)
        if (Files.isSymbolicLink(destination)) {
            throw IOException("runtime helper destination is a symbolic link: $name")
        }
        val partial = Files.createTempFile(root, ".$name-", ".part")
        try {
            context.assets.open("runtime-libs/$name").use { input ->
                Files.newOutputStream(partial, WRITE).use { output -> input.copyTo(output) }
            }
            try {
                Files.move(partial, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (failure: UnsupportedOperationException) {
                throw IOException("runtime helper could not be atomically installed: $name", failure)
            }
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun downloadVerified(
        item: String,
        url: String,
        sha256: String,
        destination: Path,
        progress: (String, Long, Long) -> Unit,
    ): Path {
        if (!sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            throw IOException("invalid SHA-256 pin for $item")
        }
        ensureDirectory(destination.parent)
        if (Files.isSymbolicLink(destination)) {
            throw IOException("refusing symbolic-link download destination for $item")
        }
        if (Files.isRegularFile(destination, NOFOLLOW_LINKS)) {
            if (fileSha256(destination).equals(sha256, ignoreCase = true)) {
                progress(item, Files.size(destination), Files.size(destination))
                return destination
            }
            Files.delete(destination)
        }

        val partial = Files.createTempFile(destination.parent, ".download-", ".part")
        try {
            val connection = openHttpsConnection(url)
            try {
                val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                if (contentLength > MAX_DOWNLOAD_BYTES) {
                    throw IOException("$item exceeds the ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MiB limit")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                connection.inputStream.use { input ->
                    FileChannel.open(partial, WRITE).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > MAX_DOWNLOAD_BYTES) {
                                throw IOException("$item exceeds the ${MAX_DOWNLOAD_BYTES / 1024 / 1024} MiB limit")
                            }
                            digest.update(buffer, 0, count)
                            var bytes = ByteBuffer.wrap(buffer, 0, count)
                            while (bytes.hasRemaining()) output.write(bytes)
                            progress(item, received, contentLength)
                        }
                        output.force(true)
                    }
                }
                if (contentLength >= 0 && received != contentLength) {
                    throw IOException("incomplete $item download: received $received of $contentLength bytes")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(sha256, ignoreCase = true)) {
                    throw IOException("$item SHA-256 mismatch: expected $sha256, received $actual")
                }
            } finally {
                connection.disconnect()
            }
            Files.move(partial, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            return destination
        } finally {
            Files.deleteIfExists(partial)
        }
    }

    private fun openHttpsConnection(initialUrl: String): HttpsURLConnection {
        var url = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            if (url.protocol != "https") throw IOException("runtime downloads require HTTPS")
            val connection = url.openConnection() as? HttpsURLConnection
                ?: throw IOException("runtime download did not use HTTPS")
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status in 200..299) return connection
            if (status in REDIRECT_STATUS_CODES && redirectCount < MAX_REDIRECTS) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw IOException("runtime download redirect has no location")
                url = URL(url, location)
            } else {
                connection.disconnect()
                throw IOException("runtime download failed with HTTP $status")
            }
        }
        throw IOException("runtime download exceeded the redirect limit")
    }

    private fun ensureDirectory(path: Path) {
        Files.createDirectories(path)
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw IOException("runtime directory is not a real directory: $path")
        }
    }

    private fun fileSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { entry -> Files.delete(entry) }
        }
    }

    private const val MAX_REDIRECTS = 5
    private const val MAX_PARALLEL_DOWNLOADS = 4
    private val REDIRECT_STATUS_CODES = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307,
        308,
    )

    private data class DownloadSpec(
        val item: String,
        val url: String,
        val sha256: String,
        val destination: Path,
    )
}
