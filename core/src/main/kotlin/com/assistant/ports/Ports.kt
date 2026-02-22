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

interface MemoryPort {
    suspend fun append(sessionId: String, message: Message)
    suspend fun history(sessionId: String, limit: Int): List<Message>
    suspend fun facts(userId: String): List<String>
    suspend fun saveFact(userId: String, fact: String)
}
