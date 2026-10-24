package io.github.spectordev9.opencode.core.runtime.impl

import io.github.spectordev9.opencode.core.common.ApiResult
import io.github.spectordev9.opencode.core.common.ErrorCode
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeArtifactDownloaderTest {
    @Test
    fun `downloads pinned bytes following only https redirects`() {
        val payload = "runtime archive".toByteArray()
        val connections = ArrayDeque(
            listOf(
                FakeHttpsConnection(URL("https://github.test/runtime")) {
                    statusCode = 302
                    headers["Location"] = "https://cdn.github.test/runtime"
                },
                FakeHttpsConnection(URL("https://cdn.github.test/runtime")) {
                    bytes = payload
                },
            ),
        )
        val visited = mutableListOf<URL>()
        val downloader = RuntimeArtifactDownloader(
            connectionFactory = HttpsConnectionFactory { url ->
                visited += url
                connections.removeFirst()
            },
        )
        val target = createTempDirectory("runtime-download").resolve("runtime.tar.gz")

        val result = downloader.download(
            "https://github.test/runtime",
            sha256(payload),
            target,
        )

        assertEquals(target, assertIs<ApiResult.Success<Path>>(result).value)
        assertContentEquals(payload, target.readBytes())
        assertEquals(
            listOf("https://github.test/runtime", "https://cdn.github.test/runtime"),
            visited.map { it.toString() },
        )
        assertTrue(connections.isEmpty())
    }

    @Test
    fun `rejects an http redirect and leaves no destination`() {
        val connection = FakeHttpsConnection(URL("https://github.test/runtime")) {
            statusCode = 302
            headers["Location"] = "http://untrusted.test/runtime"
        }
        val downloader = RuntimeArtifactDownloader(
            connectionFactory = HttpsConnectionFactory { connection },
        )
        val target = createTempDirectory("runtime-download-http-redirect").resolve("runtime.tar.gz")

        val result = downloader.download("https://github.test/runtime", "a".repeat(64), target)

        assertEquals(ErrorCode.E_UPSTREAM, assertIs<ApiResult.Failure>(result).error.code)
        assertFalse(Files.exists(target))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `rejects streamed content that exceeds its configured size`() {
        val payload = "12345".toByteArray()
        val connection = FakeHttpsConnection(URL("https://github.test/runtime")) {
            bytes = payload
            declaredLength = -1
        }
        val downloader = RuntimeArtifactDownloader(
            maxBytes = 4,
            connectionFactory = HttpsConnectionFactory { connection },
        )
        val target = createTempDirectory("runtime-download-size").resolve("runtime.tar.gz")

        val result = downloader.download("https://github.test/runtime", sha256(payload), target)

        assertEquals(ErrorCode.E_UPSTREAM, assertIs<ApiResult.Failure>(result).error.code)
        assertFalse(Files.exists(target))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `rejects a digest mismatch without publishing the downloaded file`() {
        val connection = FakeHttpsConnection(URL("https://github.test/runtime")) {
            bytes = "tampered".toByteArray()
        }
        val target = createTempDirectory("runtime-download-digest").resolve("runtime.tar.gz")
        val downloader = RuntimeArtifactDownloader(
            connectionFactory = HttpsConnectionFactory { connection },
        )

        val result = downloader.download("https://github.test/runtime", "a".repeat(64), target)

        assertEquals(ErrorCode.E_UPSTREAM, assertIs<ApiResult.Failure>(result).error.code)
        assertFalse(Files.exists(target))
        assertTrue(connection.disconnected)
    }

    @Test
    fun `rejects non-https source before opening a connection`() {
        val downloader = RuntimeArtifactDownloader(
            connectionFactory = HttpsConnectionFactory { error("connection must not be opened") },
        )

        val result = downloader.download("http://github.test/runtime", "a".repeat(64), Path.of("target"))

        assertEquals(ErrorCode.E_UNSUPPORTED_VERSION, assertIs<ApiResult.Failure>(result).error.code)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

}

internal class FakeHttpsConnection(
    url: URL,
    configure: FakeHttpsConnection.() -> Unit,
) : HttpsURLConnection(url) {
    var statusCode = 200
    var bytes = ByteArray(0)
    var declaredLength = -1L
    val headers = mutableMapOf<String, String>()
    var disconnected = false

    init {
        configure()
    }

    override fun connect() = Unit

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = statusCode

    override fun getContentLengthLong(): Long = if (declaredLength >= 0) declaredLength else bytes.size.toLong()

    override fun getHeaderField(name: String): String? = headers[name]

    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun getCipherSuite(): String = "TLS_FAKE"

    override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null

    override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
}
