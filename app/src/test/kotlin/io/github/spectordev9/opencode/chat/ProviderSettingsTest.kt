package io.github.spectordev9.opencode.chat

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderSettingsTest {
    @Test
    fun `custom provider config selects the configured model and keeps the mock out`() {
        val config = buildProviderConfig(
            LocalProviderSettings(
                baseUrl = "https://api.example.com/v1",
                modelId = "vendor/model-1",
                name = "Example",
            ),
            apiKey = "sk-test",
            mockBaseUrl = "http://127.0.0.1:1234/v1",
        )

        assertTrue(config.contains("\"model\": \"device/vendor/model-1\""))
        assertTrue(config.contains("\"baseURL\": \"https://api.example.com/v1\""))
        assertTrue(config.contains("\"apiKey\": \"sk-test\""))
        assertFalse(config.contains("opencode-mobile-mock"))
    }

    @Test
    fun `unconfigured provider falls back to the offline mock`() {
        val config = buildProviderConfig(
            LocalProviderSettings(),
            apiKey = null,
            mockBaseUrl = "http://127.0.0.1:1234/v1",
        )

        assertTrue(config.contains("opencode-mobile-mock/mock"))
        assertTrue(config.contains("http://127.0.0.1:1234/v1"))
    }

    @Test
    fun `guest shell path is written for both provider configurations`() {
        val shell = "/data/app/lib/arm64/libopencode_shell.so"
        val custom = buildProviderConfig(
            LocalProviderSettings(
                baseUrl = "https://api.example.com/v1",
                modelId = "vendor/model-1",
            ),
            apiKey = null,
            mockBaseUrl = "http://127.0.0.1:1234/v1",
            shellPath = shell,
        )
        val mock = buildProviderConfig(
            LocalProviderSettings(),
            apiKey = null,
            mockBaseUrl = "http://127.0.0.1:1234/v1",
            shellPath = shell,
        )

        assertTrue(custom.contains("\"shell\": \"$shell\""))
        assertTrue(mock.contains("\"shell\": \"$shell\""))
    }

    @Test
    fun `model identifiers reject control characters and empty values`() {
        assertNull(SharedPrefsProviderSettingsStore.validateModelId(""))
        assertNull(SharedPrefsProviderSettingsStore.validateModelId("bad\nmodel"))
        assertTrue(SharedPrefsProviderSettingsStore.validateModelId("vendor/model-1") != null)
    }
}
