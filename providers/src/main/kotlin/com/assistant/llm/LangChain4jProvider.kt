package com.assistant.llm

import com.assistant.ports.ChatMessage
import com.assistant.ports.LlmPort
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel

class LangChain4jProvider(private val config: ModelConfig) : LlmPort {
    private val model: ChatLanguageModel = when (config.provider.lowercase()) {
        "openai" -> OpenAiChatModel.builder().apiKey(config.apiKey).modelName(config.model).temperature(1.0).build()
        "anthropic" -> AnthropicChatModel.builder().apiKey(config.apiKey).modelName(config.model).build()
        "ollama" -> OllamaChatModel.builder()
            .baseUrl(config.baseUrl ?: "http://localhost:11434")
            .modelName(config.model).build()
        else -> throw IllegalArgumentException("Unknown provider: ${config.provider}")
    }

    override suspend fun complete(messages: List<ChatMessage>): String {
        val lc4jMessages: List<dev.langchain4j.data.message.ChatMessage> = messages.map { msg ->
            when (msg.role) {
                "system" -> SystemMessage.from(msg.content)
                "assistant" -> AiMessage.from(msg.content)
                else -> UserMessage.from(msg.content)
            }
        }
        return model.generate(lc4jMessages).content().text()
    }
}
