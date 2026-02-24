package com.assistant.agent

import com.assistant.ports.*

class CompactionService(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val threshold: Int = 15,
    private val compactCount: Int = 10
) {
    suspend fun maybeCompact(sessionId: String, userId: String) {
        val history = memory.history(sessionId, 100)
        if (history.size < threshold) return

        val oldest = history.take(compactCount)
        val conversation = oldest.joinToString("\n") { "${it.sender}: ${it.text}" }

        val prompt = listOf(
            ChatMessage("system", "You extract key facts from conversations."),
            ChatMessage(
                "user",
                "Summarize the key facts from this conversation in 3-5 bullet points. " +
                "Each fact on its own line starting with '- '. Be concise.\n\n$conversation"
            )
        )

        val summary = llm.complete(prompt)
        val facts = summary.lines()
            .map { it.trimStart('-', ' ') }
            .filter { it.isNotBlank() }

        facts.forEach { memory.saveFact(userId, it) }
        memory.trimHistory(sessionId, compactCount)
    }
}
