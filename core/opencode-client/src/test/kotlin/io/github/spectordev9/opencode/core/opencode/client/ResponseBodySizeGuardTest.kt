package io.github.spectordev9.opencode.core.opencode.client

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ResponseBodySizeGuardTest {
    @Test
    fun `uses larger limits for messages and smaller limit for health`() {
        assertEquals(
            ResponseBodySizeGuard.MESSAGE_MAX_BYTES,
            ResponseBodySizeGuard.limitForPath("/session/ses_123/message"),
        )
        assertEquals(
            ResponseBodySizeGuard.HEALTH_MAX_BYTES,
            ResponseBodySizeGuard.limitForPath("/global/health"),
        )
        assertEquals(
            ResponseBodySizeGuard.DEFAULT_MAX_BYTES,
            ResponseBodySizeGuard.limitForPath("/session"),
        )
    }

    @Test
    fun `accepts a response exactly at its byte limit`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

        val result = ResponseBodySizeGuard.read(input, contentLength = 4, limitBytes = 4)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), result)
    }

    @Test
    fun `rejects declared oversized response before reading its body`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

        val error = assertThrows(ResponseBodyTooLargeException::class.java) {
            ResponseBodySizeGuard.read(input, contentLength = 5, limitBytes = 4)
        }

        assertEquals(4L, error.limitBytes)
        assertEquals(4, input.available())
    }

    @Test
    fun `counts streamed bytes when content length is unknown`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))

        val error = assertThrows(ResponseBodyTooLargeException::class.java) {
            ResponseBodySizeGuard.read(input, contentLength = -1, limitBytes = 4)
        }

        assertEquals(4L, error.limitBytes)
        assertEquals(0, input.available())
    }

    @Test
    fun `rejects streamed bytes when content length is underreported`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))

        assertThrows(ResponseBodyTooLargeException::class.java) {
            ResponseBodySizeGuard.read(input, contentLength = 4, limitBytes = 4)
        }
    }
}
