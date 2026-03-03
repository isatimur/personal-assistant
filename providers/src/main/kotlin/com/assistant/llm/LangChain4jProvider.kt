package com.assistant.llm

import com.assistant.ports.ChatMessage
import com.assistant.ports.CommandSpec
import com.assistant.ports.FunctionCompletion
import com.assistant.ports.LlmPort
import com.assistant.ports.TokenUsage
import dev.langchain4j.agent.tool.ToolParameters
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.TextContent
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.anthropic.AnthropicChatModel
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.chat.StreamingChatLanguageModel
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.model.ollama.OllamaStreamingChatModel
import dev.langchain4j.model.openai.OpenAiChatModel
import dev.langchain4j.model.openai.OpenAiStreamingChatModel
import dev.langchain4j.model.output.Response
import dev.langchain4j.model.StreamingResponseHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

class LangChain4jProvider(private val config: ModelConfig) : LlmPort {
    private val model: ChatLanguageModel = buildModel(config.provider, config.model, config.apiKey, config.baseUrl)
    private val streamingModel: StreamingChatLanguageModel = buildStreamingModel(config.provider, config.model, config.apiKey, config.baseUrl)

    private val fastLlm: ChatLanguageModel? = if (!config.fastModel.isNullOrBlank()) {
        buildModel(config.provider, config.fastModel, config.apiKey, config.baseUrl)
    } else {
        null
    }

    private fun buildModel(provider: String, modelName: String, apiKey: String?, baseUrl: String?): ChatLanguageModel =
        when (provider.lowercase()) {
            "openai" -> OpenAiChatModel.builder().apiKey(apiKey).modelName(modelName).temperature(1.0).build()
            "anthropic" -> AnthropicChatModel.builder().apiKey(apiKey).modelName(modelName).build()
            "ollama" -> OllamaChatModel.builder()
                .baseUrl(baseUrl ?: "http://localhost:11434")
                .modelName(modelName).build()
            else -> throw IllegalArgumentException("Unknown provider: $provider")
        }

    private fun buildStreamingModel(provider: String, modelName: String, apiKey: String?, baseUrl: String?): StreamingChatLanguageModel =
        when (provider.lowercase()) {
            "openai" -> OpenAiStreamingChatModel.builder().apiKey(apiKey).modelName(modelName).temperature(1.0).build()
            "anthropic" -> AnthropicStreamingChatModel.builder().apiKey(apiKey).modelName(modelName).build()
            "ollama" -> OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl ?: "http://localhost:11434")
                .modelName(modelName).build()
            else -> throw IllegalArgumentException("Unknown provider for streaming: $provider")
        }

    override suspend fun complete(messages: List<ChatMessage>): String {
        val lc4jMessages = messages.toLc4j()
        return withContext(Dispatchers.IO) { model.generate(lc4jMessages).content().text() }
    }

    override suspend fun completeWithFunctions(
        messages: List<ChatMessage>,
        commands: List<CommandSpec>
    ): FunctionCompletion = completeWithModel(model, messages, commands)

    override suspend fun completeWithFunctionsFast(
        messages: List<ChatMessage>,
        commands: List<CommandSpec>
    ): FunctionCompletion = completeWithModel(fastLlm ?: model, messages, commands)

    override suspend fun stream(messages: List<ChatMessage>, onToken: suspend (String) -> Unit): String {
        val lc4jMessages = messages.toLc4j()
        // UNLIMITED capacity so callbacks never block
        val tokenChannel = Channel<String?>(capacity = Channel.UNLIMITED)

        // Start streaming — generate() is non-blocking, fires callbacks on HTTP threads
        withContext(Dispatchers.IO) {
            streamingModel.generate(lc4jMessages, object : StreamingResponseHandler<AiMessage> {
                override fun onNext(token: String) {
                    tokenChannel.trySend(token)
                }
                override fun onComplete(response: Response<AiMessage>) {
                    tokenChannel.trySend(null)  // sentinel: streaming done
                    tokenChannel.close()
                }
                override fun onError(error: Throwable) {
                    tokenChannel.close(Exception(error.message ?: "Streaming error"))
                }
            })
        }

        // Collect tokens in the calling coroutine; channel closes when done
        val accumulated = StringBuilder()
        for (token in tokenChannel) {
            if (token == null) break  // sentinel reached (belt-and-suspenders with close())
            accumulated.append(token)
            onToken(token)
        }
        return accumulated.toString()
    }

    private suspend fun completeWithModel(
        model: ChatLanguageModel,
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
                else -> {
                    val imgUrl = msg.imageUrl
                    if (imgUrl != null) {
                        UserMessage.from(
                            ImageContent.from(imgUrl),
                            TextContent.from(msg.content)
                        )
                    } else {
                        UserMessage.from(msg.content)
                    }
                }
            }
        }
}
