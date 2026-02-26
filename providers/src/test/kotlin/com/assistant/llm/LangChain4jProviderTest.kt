package com.assistant.llm

import com.assistant.ports.ChatMessage
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

    @Test
    fun `ChatMessage with imageUrl carries url through data class`() {
        val msg = ChatMessage(role = "user", content = "describe this image", imageUrl = "https://example.com/photo.jpg")
        assertEquals("user", msg.role)
        assertEquals("describe this image", msg.content)
        assertEquals("https://example.com/photo.jpg", msg.imageUrl)
    }

    @Test
    fun `ChatMessage imageUrl defaults to null`() {
        val msg = ChatMessage(role = "user", content = "hello")
        assertNull(msg.imageUrl)
    }

    @Test
    fun `provider builds with anthropic config for vision model`() {
        val config = ModelConfig(provider = "anthropic", model = "claude-opus-4-6", apiKey = "test-key", baseUrl = null)
        assertNotNull(LangChain4jProvider(config))
    }

    @Test
    fun `LangChain4jProvider constructs with fast model configured`() {
        val config = ModelConfig(
            provider = "anthropic",
            model = "claude-sonnet-4-6",
            apiKey = "test-key",
            baseUrl = null,
            fastModel = "claude-haiku-4-5-20251001"
        )
        assertDoesNotThrow { LangChain4jProvider(config) }
    }
}
