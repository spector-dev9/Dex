package io.github.spectordev9.opencode.features

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecretRedactingScrubberTest {
    private val scrubber = SecretRedactingScrubber()

    @Test
    fun `redacts common credential shapes`() {
        val scrubbed = scrubber.scrub(
            "Authorization: Bearer abcdef123456 api_key=sk-live-123456 password=hunter2 " +
                "https://example.com?token=secret-token&x=1",
        )

        assertFalse(scrubbed.contains("abcdef123456"))
        assertFalse(scrubbed.contains("hunter2"))
        assertFalse(scrubbed.contains("secret-token"))
        assertTrue(scrubbed.contains("[redacted]"))
        assertTrue(scrubbed.contains("x=1"))
    }

    @Test
    fun `keeps ordinary messages and caps length`() {
        assertTrue(scrubber.scrub("The build finished in 4 seconds").contains("4 seconds"))
        assertTrue(scrubber.scrub("x".repeat(10_000)).length <= SecretRedactingScrubber.MAX_MESSAGE_CHARS)
    }
}
