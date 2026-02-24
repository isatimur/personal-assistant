package com.assistant.llm

import com.assistant.ports.ChatMessage
import com.assistant.ports.CommandSpec
import com.assistant.ports.FunctionCompletion
import com.assistant.ports.LlmPort
import com.assistant.ports.TokenUsage
import dev.langchain4j.agent.tool.ToolParameters
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val lc4jMessages = messages.toLc4j()
        return withContext(Dispatchers.IO) { model.generate(lc4jMessages).content().text() }
    }

    override suspend fun completeWithFunctions(
        messages: List<ChatMessage>,
        commands: List<CommandSpec>
    ): FunctionCompletion {
        val lc4jMessages = messages.toLc4j()
        val specs = commands.map { cmd ->
            val props: Map<String, Map<String, Any>> = cmd.params.associate { p ->
                p.name to mapOf("type" to p.type, "description" to p.description)
            }
            val required = cmd.params.filter { it.required }.map { it.name }
            ToolSpecification.builder()
                .name(cmd.name)
                .description(cmd.description)
                .parameters(
                    ToolParameters.builder()
                        .properties(props)
                        .required(required)
                        .build()
                )
                .build()
        }

        val response = withContext(Dispatchers.IO) {
            if (specs.isEmpty()) model.generate(lc4jMessages)
            else model.generate(lc4jMessages, specs)
        }

        val usage = response.tokenUsage()?.let {
            TokenUsage(it.inputTokenCount(), it.outputTokenCount())
        }
        val ai = response.content()

        return if (ai.hasToolExecutionRequests()) {
            val req = ai.toolExecutionRequests().first()
            FunctionCompletion.FunctionCall(req.name(), req.arguments(), usage)
        } else {
            FunctionCompletion.Text(ai.text() ?: "", usage)
        }
    }

    private fun List<ChatMessage>.toLc4j(): List<dev.langchain4j.data.message.ChatMessage> =
        map { msg ->
            when (msg.role) {
                "system" -> SystemMessage.from(msg.content)
                "assistant" -> AiMessage.from(msg.content)
                else -> UserMessage.from(msg.content)
            }
        }
}
