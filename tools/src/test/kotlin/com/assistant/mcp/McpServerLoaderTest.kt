package com.assistant.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class McpServerLoaderTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `loadConfigs returns empty list when file missing`() {
        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadConfigs parses single server correctly`() {
        File(dir, "mcp-servers.json").writeText("""
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/home"],
                  "env": { "FOO": "bar" }
                }
              }
            }
        """.trimIndent())

        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))

        assertEquals(1, result.size)
        val cfg = result[0]
        assertEquals("filesystem", cfg.name)
        assertEquals("npx", cfg.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/home"), cfg.args)
        assertEquals(mapOf("FOO" to "bar"), cfg.env)
    }

    @Test
    fun `loadConfigs parses multiple servers`() {
        File(dir, "mcp-servers.json").writeText("""
            {
              "mcpServers": {
                "filesystem": { "command": "npx", "args": [], "env": {} },
                "notion":     { "command": "npx", "args": [], "env": {} }
              }
            }
        """.trimIndent())

        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertEquals(2, result.size)
        assertEquals(setOf("filesystem", "notion"), result.map { it.name }.toSet())
    }

    @Test
    fun `loadConfigs returns empty list on malformed JSON`() {
        File(dir, "mcp-servers.json").writeText("not json {{{")
        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `loadConfigs handles missing args and env`() {
        File(dir, "mcp-servers.json").writeText("""
            { "mcpServers": { "minimal": { "command": "echo" } } }
        """.trimIndent())

        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertEquals(1, result.size)
        assertEquals(emptyList<String>(), result[0].args)
        assertEquals(emptyMap<String, String>(), result[0].env)
    }

    @Test
    fun `loadConfigs skips malformed server entry but loads valid ones`() {
        File(dir, "mcp-servers.json").writeText("""
            {
              "mcpServers": {
                "broken": { "args": [] },
                "good": { "command": "npx", "args": [], "env": {} }
              }
            }
        """.trimIndent())
        val result = McpServerLoader.loadConfigs(File(dir, "mcp-servers.json"))
        assertEquals(1, result.size)
        assertEquals("good", result[0].name)
    }
}
