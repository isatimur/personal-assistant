package com.assistant

import com.charleskorn.kaml.Yaml
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

    private fun minimalAppConfig(routingBlock: String) = """
        telegram:
          token: "PLACEHOLDER"
        llm:
          provider: openai
          model: gpt-4o
          api-key: "PLACEHOLDER"
        memory:
          db-path: /tmp/test.db
          window-size: 20
        tools: {}
        routing:
${routingBlock.trimIndent().lines().joinToString("\n") { "          $it" }}
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

    @Test
    fun `grpc defaults to disabled on port 9090`() {
        val yaml = minimalAppConfig("default: personal")
        val config = Yaml.default.decodeFromString(AppConfig.serializer(), yaml)
        assertFalse(config.routing!!.grpc.enabled)
        assertEquals(9090, config.routing!!.grpc.port)
    }

    @Test
    fun `grpc config parses enabled and port`() {
        val yaml = minimalAppConfig("""
            grpc:
              enabled: true
              port: 8080
        """)
        val config = Yaml.default.decodeFromString(AppConfig.serializer(), yaml)
        assertTrue(config.routing!!.grpc.enabled)
        assertEquals(8080, config.routing!!.grpc.port)
    }

    @Test
    fun `remote-agents parsed from yaml`() {
        val yaml = minimalAppConfig("""
            remote-agents:
              work-agent: "localhost:9091"
        """)
        val config = Yaml.default.decodeFromString(AppConfig.serializer(), yaml)
        assertEquals("localhost:9091", config.routing!!.remoteAgents["work-agent"])
    }

    @Test
    fun `discovery defaults to static`() {
        val yaml = minimalAppConfig("default: personal")
        val config = Yaml.default.decodeFromString(AppConfig.serializer(), yaml)
        assertEquals("static", config.routing!!.discovery)
    }
}
