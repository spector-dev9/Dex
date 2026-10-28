package io.github.spectordev9.opencode.chat

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ServerProfileValidationTest {
    @Test
    fun `name is trimmed and length capped`() {
        assertEquals("Homelab", SharedPrefsServerProfileStore.validateProfileName("  Homelab  "))
        assertEquals("x".repeat(64), SharedPrefsServerProfileStore.validateProfileName("x".repeat(64)))
        assertNull(SharedPrefsServerProfileStore.validateProfileName("x".repeat(65)))
        assertNull(SharedPrefsServerProfileStore.validateProfileName("   "))
        assertNull(SharedPrefsServerProfileStore.validateProfileName(null))
    }

    @Test
    fun `urls normalize with explicit or implicit scheme`() {
        assertEquals("http://127.0.0.1:42141", SharedPrefsServerProfileStore.normalizeProfileUrl(" 127.0.0.1:42141 "))
        assertEquals("https://example.com/api", SharedPrefsServerProfileStore.normalizeProfileUrl("https://example.com/api/"))
        assertEquals("http://example.com", SharedPrefsServerProfileStore.normalizeProfileUrl("http://example.com:80"))
        assertEquals("https://example.com", SharedPrefsServerProfileStore.normalizeProfileUrl("https://example.com:443"))
        assertNull(SharedPrefsServerProfileStore.normalizeProfileUrl("ftp://example.com"))
        assertNull(SharedPrefsServerProfileStore.normalizeProfileUrl(""))
        assertNull(SharedPrefsServerProfileStore.normalizeProfileUrl(null))
    }
}
