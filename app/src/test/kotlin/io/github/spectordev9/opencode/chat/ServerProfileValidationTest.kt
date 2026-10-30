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
    fun `provider endpoints allow private lan http but reject public http`() {
        assertEquals(
            "http://192.168.1.20:11434/v1",
            SharedPrefsServerProfileStore.validateProviderEndpoint("http://192.168.1.20:11434/v1"),
        )
        assertEquals(
            "http://10.0.0.5:1234/v1",
            SharedPrefsServerProfileStore.validateProviderEndpoint("http://10.0.0.5:1234/v1/"),
        )
        assertEquals(
            "http://my-pc.local:11434/v1",
            SharedPrefsServerProfileStore.validateProviderEndpoint("my-pc.local:11434/v1"),
        )
        assertEquals(
            "https://api.example.com/v1",
            SharedPrefsServerProfileStore.validateProviderEndpoint("https://api.example.com/v1"),
        )
        assertNull(SharedPrefsServerProfileStore.validateProviderEndpoint("http://example.com/v1"))
        assertNull(SharedPrefsServerProfileStore.validateProviderEndpoint("ftp://192.168.1.20/v1"))
    }

    @Test
    fun `private host detection covers rfc1918 tailscale and local names`() {
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("10.1.2.3"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("172.16.0.1"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("172.31.255.254"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("192.168.0.2"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("100.100.1.2"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("169.254.10.20"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("fd00::1"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("fe80::1"))
        assertEquals(true, SharedPrefsServerProfileStore.isPrivateNetworkHost("workstation.local"))
        assertEquals(false, SharedPrefsServerProfileStore.isPrivateNetworkHost("172.15.0.1"))
        assertEquals(false, SharedPrefsServerProfileStore.isPrivateNetworkHost("172.32.0.1"))
        assertEquals(false, SharedPrefsServerProfileStore.isPrivateNetworkHost("8.8.8.8"))
        assertEquals(false, SharedPrefsServerProfileStore.isPrivateNetworkHost("2001:db8::1"))
        assertEquals(false, SharedPrefsServerProfileStore.isPrivateNetworkHost("example.com"))
    }

    @Test
    fun `remote directories are validated without local path resolution`() {
        assertEquals(".", SharedPrefsServerProfileStore.validateRemoteDirectory(null))
        assertEquals("/srv/projects/demo", SharedPrefsServerProfileStore.validateRemoteDirectory(" /srv/projects/demo "))
        assertEquals("../server/path", SharedPrefsServerProfileStore.validateRemoteDirectory("../server/path"))
        assertNull(SharedPrefsServerProfileStore.validateRemoteDirectory("bad\u0000path"))
        assertNull(SharedPrefsServerProfileStore.validateRemoteDirectory("x".repeat(513)))
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
