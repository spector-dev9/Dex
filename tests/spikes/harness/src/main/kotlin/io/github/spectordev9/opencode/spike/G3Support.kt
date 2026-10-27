package io.github.spectordev9.opencode.spike

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal object G3Support {
    fun g3Dir(context: Context, name: String): File =
        File(context.filesDir, "g3/$name").apply { mkdirs() }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun sha256Generated(run: Int, size: Int): String {
        val header = "G3WS2:$run:$size:".toByteArray(StandardCharsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(header)
        val chunk = ByteArray(64 * 1024) { (0x41 + (run % 26)).toByte() }
        var remaining = size - header.size
        while (remaining > 0) {
            val count = minOf(remaining, chunk.size)
            digest.update(chunk, 0, count)
            remaining -= count
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun basic(secret: String, user: String = "opencode"): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(StandardCharsets.UTF_8))

    fun http(
        url: String,
        method: String = "GET",
        authorization: String? = null,
        body: String? = null,
        timeoutMs: Int = 10_000,
        accept: String = "application/json",
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", accept)
        if (authorization != null) {
            connection.setRequestProperty("Authorization", authorization)
        }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResult(status, text)
        } finally {
            connection.disconnect()
        }
    }

    fun reservePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    fun canBind(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
    } catch (_: IOException) {
        false
    }

    fun waitForPortFree(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (canBind(port)) return true
            Thread.sleep(50)
        }
        return false
    }

    fun readAll(stream: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }
}

internal data class HttpResult(
    val status: Int,
    val body: String,
)
