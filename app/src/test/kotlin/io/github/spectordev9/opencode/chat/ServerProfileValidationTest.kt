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

    @Test
    fun `remote profiles require https while loopback may use http`() {
        assertEquals(
            "https://example.com",
            SharedPrefsServerProfileStore.validateProfileEndpoint("https://example.com/"),
        )
        assertEquals(
            "http://127.0.0.1:42141",
            SharedPrefsServerProfileStore.validateProfileEndpoint("http://127.0.0.1:42141"),
        )
        assertEquals(
            "http://localhost:8080",
            SharedPrefsServerProfileStore.validateProfileEndpoint("localhost:8080"),
        )
        assertNull(SharedPrefsServerProfileStore.validateProfileEndpoint("http://example.com"))
        assertNull(SharedPrefsServerProfileStore.validateProfileEndpoint("http://192.168.1.20:4096"))
        assertNull(SharedPrefsServerProfileStore.validateProfileEndpoint("example.com:4096"))
        assertNull(SharedPrefsServerProfileStore.validateProfileEndpoint("http://[2001:db8::1]"))
    }

    @Test
    fun `loopback detection handles literals and local aliases`() {
        assertEquals(true, SharedPrefsServerProfileStore.isLoopbackHost("localhost"))
        assertEquals(true, SharedPrefsServerProfileStore.isLoopbackHost("127.0.0.1"))
        assertEquals(true, SharedPrefsServerProfileStore.isLoopbackHost("127.4.3.2"))
        assertEquals(true, SharedPrefsServerProfileStore.isLoopbackHost("[::1]"))
        assertEquals(true, SharedPrefsServerProfileStore.isLoopbackHost("10.0.2.2"))
        assertEquals(false, SharedPrefsServerProfileStore.isLoopbackHost("example.com"))
        assertEquals(false, SharedPrefsServerProfileStore.isLoopbackHost(null))
    }
}
