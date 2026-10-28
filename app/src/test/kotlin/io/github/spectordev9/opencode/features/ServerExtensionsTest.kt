package io.github.spectordev9.opencode.features

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerExtensionsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses skills and truncates long previews`() {
        val skills = parseSkills(
            json,
            """
            [
              {"name":"deploy","description":"Ship it","location":"skills/deploy.md","content":"line one\nline two"},
              {"name":"review","location":"skills/review.md","content":"Review code"},
              {"description":"missing name","location":"x","content":"y"}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("deploy", "review"), skills.map { it.name })
        assertEquals("line one line two", skills.first().preview)
        assertEquals(2, skills.first().description?.split(' ')?.size)
    }

    @Test
    fun `parses MCP statuses including failure detail`() {
        val servers = parseMcpServers(
            json,
            """
            {
              "github":{"status":"connected"},
              "broken":{"status":"failed","error":"spawn ENOENT"},
              "auth":{"status":"needs_auth"}
            }
            """.trimIndent(),
        )

        assertEquals(listOf("auth", "broken", "github"), servers.map { it.name })
        assertTrue(servers.first { it.name == "github" }.connected)
        assertTrue(servers.first { it.name == "broken" }.needsAttention)
        assertEquals("spawn ENOENT", servers.first { it.name == "broken" }.detail)
    }

    @Test
    fun `parses commands with source and configuration summary`() {
        val commands = parseCommands(
            json,
            """
            [
              {"name":"plan","description":"Plan work","source":"command","template":"x","hints":[]},
              {"name":"deploy","source":"skill","template":"y","hints":[]}
            ]
            """.trimIndent(),
        )
        val parsed = parseServerConfig(
            json,
            """
            {
              "shell":"bash",
              "logLevel":"info",
              "plugin":["github.com/acme/plugin",["npm:other",{}]],
              "mcp":{"a":{},"b":{}},
              "command":{"one":{}},
              "skills":{"paths":["skills","extra"]},
              "share":"manual"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("deploy", "plan"), commands.map { it.name })
        assertEquals("skill", commands.first().source)
        assertEquals(2, parsed?.config?.pluginCount)
        assertEquals(2, parsed?.config?.mcpCount)
        assertEquals(1, parsed?.config?.commandCount)
        assertEquals(2, parsed?.config?.skillPathCount)
        assertEquals("bash", parsed?.config?.shell)
        assertEquals(listOf("plugin", "other"), parsed?.plugins?.map { it.name })
    }

    @Test
    fun `parses experimental capabilities and rejects malformed documents`() {
        assertEquals(true, parseCapabilities(json, """{"backgroundSubagents":true}"""))
        assertFalse(parseCapabilities(json, """{"backgroundSubagents":false}""") ?: true)
        assertNull(parseCapabilities(json, """{"other":true}"""))
        assertTrue(parseSkills(json, """{"not":"a list"}""").isEmpty())
        assertTrue(parseMcpServers(json, "not-json").isEmpty())
    }

    @Test
    fun `validates MCP server names`() {
        assertEquals("github", ServerExtensionsController.validateMcpName(" github "))
        assertNull(ServerExtensionsController.validateMcpName(""))
        assertNull(ServerExtensionsController.validateMcpName("bad name"))
        assertNull(ServerExtensionsController.validateMcpName("../escape"))
        assertNull(ServerExtensionsController.validateMcpName("x".repeat(65)))
    }
}
