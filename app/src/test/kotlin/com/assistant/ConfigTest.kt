package com.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConfigTest {

    @TempDir
    lateinit var tmpDir: File

    private fun baseYml() = """
        telegram:
          token: "PLACEHOLDER"
          timeout-ms: 120000
        llm:
          provider: openai
          model: gpt-4o
          api-key: "PLACEHOLDER"
        memory:
          db-path: /tmp/test.db
          window-size: 20
        tools:
          email:
            enabled: false
    """.trimIndent()

    @Test
    fun `loads base config without secrets file`() {
        val base = File(tmpDir, "application.yml").also { it.writeText(baseYml()) }
        val secrets = File(tmpDir, "secrets.yml") // does not exist
        val config = loadConfig(base.absolutePath, secrets.absolutePath)
        assertEquals("PLACEHOLDER", config.telegram.token)
        assertEquals("PLACEHOLDER", config.llm.apiKey)
    }

    @Test
    fun `secrets file overlays base config`() {
        val base = File(tmpDir, "application.yml").also { it.writeText(baseYml()) }
        val secrets = File(tmpDir, "secrets.yml").also {
            it.writeText("telegram:\n  token: \"real-token\"\nllm:\n  api-key: \"real-key\"\n")
        }
        val config = loadConfig(base.absolutePath, secrets.absolutePath)
        assertEquals("real-token", config.telegram.token)
        assertEquals("real-key", config.llm.apiKey)
    }

    @Test
    fun `missing secrets file does not crash`() {
        val base = File(tmpDir, "application.yml").also { it.writeText(baseYml()) }
        assertDoesNotThrow {
            loadConfig(base.absolutePath, File(tmpDir, "nonexistent.yml").absolutePath)
        }
    }

    @Test
    fun `partial secrets file only overrides provided keys`() {
        val base = File(tmpDir, "application.yml").also { it.writeText(baseYml()) }
        val secrets = File(tmpDir, "secrets.yml").also {
            it.writeText("telegram:\n  token: \"real-token\"\n")
        }
        val config = loadConfig(base.absolutePath, secrets.absolutePath)
        assertEquals("real-token", config.telegram.token)
        assertEquals("PLACEHOLDER", config.llm.apiKey) // llm not in secrets
    }
}
