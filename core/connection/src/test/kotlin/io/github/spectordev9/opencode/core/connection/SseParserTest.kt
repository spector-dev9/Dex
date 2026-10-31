package io.github.spectordev9.opencode.core.connection

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SseParserTest {
    @Test
    fun `parse basic data frame`() {
        val p = SseParser()
        val frames = p.feed("data: hello\n\n")
        assertEquals(1, frames.size)
        assertEquals("hello", frames[0].data)
    }

    @Test
    fun `parse multi line data`() {
        val p = SseParser()
        val frames = p.feed("data: line1\ndata: line2\n\n")
        assertEquals(1, frames.size)
        assertEquals("line1\nline2", frames[0].data)
    }

    @Test
    fun `parse event and id`() {
        val p = SseParser()
        val frames = p.feed("event: msg\nid: 1\ndata: x\n\n")
        assertEquals(1, frames.size)
        assertEquals("msg", frames[0].event)
        assertEquals("1", frames[0].id)
        assertEquals("x", frames[0].data)
    }

    @Test
    fun `ignore comments`() {
        val p = SseParser()
        val frames = p.feed(":comment\ndata: ok\n\n")
        assertEquals(1, frames.size)
        assertEquals("ok", frames[0].data)
    }

    @Test
    fun `handle CRLF`() {
        val p = SseParser()
        val frames = p.feed("data: a\r\n\r\n")
        assertEquals(1, frames.size)
        assertEquals("a", frames[0].data)
    }

    @Test
    fun `partial chunk then finish`() {
        val p = SseParser()
        p.feed("data: p")
        val frames = p.finish()
        assertEquals(0, frames.size)
        p.feed("artial\n\n")
        val frames2 = p.finish()
        assertEquals(1, frames2.size)
        assertEquals("partial", frames2[0].data)
    }

    @Test
    fun `bom removed from first line`() {
        val p = SseParser()
        val frames = p.feed("\uFEFFdata: bom\n\n")
        assertEquals(1, frames.size)
        assertEquals("bom", frames[0].data)
    }
}
