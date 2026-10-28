package io.github.spectordev9.opencode.chat

import io.github.spectordev9.opencode.core.model.MessageInfo
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MessageUsageTest {
    @Test
    fun `reports only usage values supplied by the server`() {
        val info = MessageInfo(
            id = "msg_1",
            sessionID = "ses_1",
            role = "assistant",
            time = buildJsonObject {},
            cost = 0.125,
            tokens = buildJsonObject {
                put("input", 24)
                put("output", 8)
                put(
                    "cache",
                    buildJsonObject {
                        put("read", 4)
                    },
                )
            },
        )

        assertEquals("24 in · 8 out · 4 cached  |  cost 0.1250", messageUsageSummary(info))
    }

    @Test
    fun `omits missing malformed and negative usage values`() {
        val info = MessageInfo(
            id = "msg_2",
            sessionID = "ses_1",
            role = "assistant",
            time = buildJsonObject {},
            cost = Double.NaN,
            tokens = buildJsonObject {
                put("input", -1)
                put("output", "unknown")
                put("cache", "unavailable")
            },
        )

        assertNull(messageUsageSummary(info))
    }
}
