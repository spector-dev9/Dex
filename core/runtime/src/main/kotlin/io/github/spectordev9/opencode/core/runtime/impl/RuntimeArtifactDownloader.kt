package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiError
import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

fun interface HttpsConnectionFactory {
    fun open(url: URL): HttpsURLConnection
}

class RuntimeArtifactDownloader(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val connectionFactory: HttpsConnectionFactory = HttpsConnectionFactory { url ->
        url.openConnection() as? HttpsURLConnection
            ?: throw IOException("runtime artifact URL is not HTTPS")
    },
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
    }

    fun download(
        url: String,
        expectedSha256: String,
        destination: Path,
    ): ApiResult<Path> {
        if (!expectedSha256.matches(SHA256_PATTERN)) {
            return failure(ErrorCode.E_UNSUPPORTED_VERSION, "invalid runtime artifact sha-256 pin")
        }
        val source = try {
            URI(url).also {
                require(it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()) {
                    "runtime artifact URL must use HTTPS"
                }
                require(it.userInfo == null && it.fragment == null) {
                    "runtime artifact URL cannot contain credentials or a fragment"
                }
            }
        } catch (invalid: IllegalArgumentException) {
            return failure(ErrorCode.E_UNSUPPORTED_VERSION, invalid.message ?: "invalid runtime artifact URL")
        }

        val target = destination.toAbsolutePath().normalize()
        val parent = target.parent
            ?: return failure(ErrorCode.E_RUNTIME_DOWN, "runtime artifact destination has no parent")
        return try {
            Files.createDirectories(parent)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(target) && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    "runtime artifact destination is not a regular file"
                }
                if (sha256(target).equals(expectedSha256, ignoreCase = true)) {
                    return ApiResult.Success(target)
                }
            }

            val temporary = Files.createTempFile(parent, ".runtime-artifact-", ".part")
            try {
                val actualSha256 = transfer(source.toURL(), temporary)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("runtime artifact sha-256 mismatch")
                }
                Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                ApiResult.Success(target)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (failure: IOException) {
            failure(ErrorCode.E_UPSTREAM, "runtime artifact download failed: ${failure.message}")
        } catch (unsupported: UnsupportedOperationException) {
            failure(ErrorCode.E_RUNTIME_DOWN, "runtime artifact storage does not support atomic install")
        } catch (invalid: IllegalArgumentException) {
            failure(ErrorCode.E_UNSUPPORTED_VERSION, invalid.message ?: "invalid runtime artifact destination")
        }
    }

    private fun transfer(initialUrl: URL, temporary: Path): String {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = connectionFactory.open(url)
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            try {
                when (val responseCode = connection.responseCode) {
                    in 200..299 -> {
                        val contentLength = connection.contentLengthLong
                        if (contentLength > maxBytes) throw IOException("runtime artifact exceeds size limit")
                        val digest = MessageDigest.getInstance("SHA-256")
                        var bytesCopied = 0L
                        connection.inputStream.use { input ->
                            Files.newOutputStream(temporary).use { output ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    bytesCopied += count
                                    if (bytesCopied > maxBytes) {
                                        throw IOException("runtime artifact exceeds size limit")
                                    }
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        return digest.digest().toHex()
                    }
                    in REDIRECT_CODES -> {
                        if (redirectCount == MAX_REDIRECTS) throw IOException("too many runtime artifact redirects")
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("runtime artifact redirect has no location")
                        val redirected = url.toURI().resolve(location)
                        if (!redirected.scheme.equals("https", ignoreCase = true) ||
                            redirected.host.isNullOrBlank() ||
                            redirected.userInfo != null
                        ) {
                            throw IOException("runtime artifact redirect must remain HTTPS")
                        }
                        url = redirected.toURL()
                    }
                    else -> throw IOException("runtime artifact server returned HTTP $responseCode")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("runtime artifact redirect limit exceeded")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun failure(code: ErrorCode, message: String): ApiResult.Failure =
        ApiResult.Failure(ApiError(code, message))

    private companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
    }
}
