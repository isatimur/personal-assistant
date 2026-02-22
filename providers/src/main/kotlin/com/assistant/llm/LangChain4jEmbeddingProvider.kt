package com.assistant.llm

import com.assistant.ports.EmbeddingPort
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.ollama.OllamaEmbeddingModel
import dev.langchain4j.model.openai.OpenAiEmbeddingModel

data class EmbeddingConfig(
    val provider: String,
    val model: String,
    val apiKey: String? = null,
    val baseUrl: String? = null
)

class LangChain4jEmbeddingProvider(private val config: EmbeddingConfig) : EmbeddingPort {
    private val model: EmbeddingModel = when (config.provider.lowercase()) {
        "openai" -> OpenAiEmbeddingModel.builder().apiKey(config.apiKey).modelName(config.model).build()
        "ollama" -> OllamaEmbeddingModel.builder()
            .baseUrl(config.baseUrl ?: "http://localhost:11434")
            .modelName(config.model).build()
        else -> throw IllegalArgumentException("Unknown embedding provider: ${config.provider}")
    }

    override suspend fun embed(text: String): FloatArray =
        model.embed(TextSegment.from(text)).content().vector()
}
