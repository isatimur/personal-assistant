package com.assistant.llm

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LangChain4jProviderTest {
    @Test
    fun `provider builds with openai config`() {
        val config = ModelConfig(provider = "openai", model = "gpt-4o-mini", apiKey = "test-key", baseUrl = null)
        assertNotNull(LangChain4jProvider(config))
    }

    @Test
    fun `provider builds with anthropic config`() {
        val config = ModelConfig(provider = "anthropic", model = "claude-haiku-4-5-20251001", apiKey = "test-key", baseUrl = null)
        assertNotNull(LangChain4jProvider(config))
    }

    @Test
    fun `provider builds with ollama config`() {
        val config = ModelConfig(provider = "ollama", model = "llama3.2", apiKey = null, baseUrl = "http://localhost:11434")
        assertNotNull(LangChain4jProvider(config))
    }

    @Test
    fun `unknown provider throws`() {
        val config = ModelConfig(provider = "unknown", model = "x", apiKey = null, baseUrl = null)
        assertThrows(IllegalArgumentException::class.java) { LangChain4jProvider(config) }
    }
}
