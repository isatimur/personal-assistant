package com.assistant.ports

import com.assistant.domain.*

data class ChatMessage(val role: String, val content: String)

interface LlmPort {
    suspend fun complete(messages: List<ChatMessage>): String
}

interface ToolPort {
    val name: String
    val description: String
    suspend fun execute(call: ToolCall): Observation
}

interface EmbeddingPort {
    suspend fun embed(text: String): FloatArray
}

interface MemoryPort {
    suspend fun append(sessionId: String, message: Message)
    suspend fun history(sessionId: String, limit: Int): List<Message>
    suspend fun facts(userId: String): List<String>
    suspend fun saveFact(userId: String, fact: String)
    suspend fun search(userId: String, query: String, limit: Int = 5): List<String>
    suspend fun clearHistory(sessionId: String)
}
