package io.github.spectordev9.opencode.core.opencode.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/*
 * Adapted from
 * https://github.com/hjinlabs-netizen/opencode-android/blob/1911ea29715d1ee3ca02742bd66ddde8dee759e9/app/src/main/java/com/anomalyco/opencode/data/remote/ResponseSizeGuard.kt
 * (MIT, Copyright 2026 hjinlabs-netizen); see the bundled notice in docs/licenses.
 */
internal object ResponseBodySizeGuard {
    const val MEBIBYTE = 1024L * 1024L
    const val HEALTH_MAX_BYTES = MEBIBYTE
    const val DEFAULT_MAX_BYTES = 8L * MEBIBYTE
    const val MESSAGE_MAX_BYTES = 16L * MEBIBYTE

    fun limitForPath(path: String): Long {
        val segments = path.split('/').filter(String::isNotEmpty)
        return when {
            segments == listOf("global", "health") -> HEALTH_MAX_BYTES
            segments.size == 3 &&
                segments[0] == "session" &&
                segments[2] == "message" -> MESSAGE_MAX_BYTES
            else -> DEFAULT_MAX_BYTES
        }
    }

    fun read(input: InputStream, contentLength: Long, limitBytes: Long): ByteArray {
        require(limitBytes >= 0) { "limitBytes must not be negative" }
        if (contentLength > limitBytes) {
            throw ResponseBodyTooLargeException(limitBytes)
        }

        val initialCapacity = if (contentLength in 1..limitBytes) {
            contentLength.toInt()
        } else {
            8 * 1024
        }
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val remaining = limitBytes - output.size()
            val requested = minOf(buffer.size.toLong(), remaining + 1).toInt()
            val count = input.read(buffer, 0, requested)
            if (count < 0) break
            if (count == 0) continue
            if (count.toLong() > remaining) {
                throw ResponseBodyTooLargeException(limitBytes)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

internal class ResponseBodyTooLargeException(
    val limitBytes: Long,
) : IOException("Response body exceeds $limitBytes bytes")
