package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*

class ContextAssembler(
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val windowSize: Int = 20
) {
    suspend fun build(session: Session, currentMessage: Message): List<ChatMessage> {
        val facts = memory.facts(session.userId)
        val history = memory.history(session.id, windowSize)

        val systemPrompt = buildString {
            appendLine("You are a personal AI assistant running locally. Use tools to take real actions.")
            appendLine("\nAvailable tools:\n${toolRegistry.describe()}")
            appendLine("\nTo use a tool, respond EXACTLY with:")
            appendLine("THOUGHT: <reasoning>")
            appendLine("ACTION: <command_name>")
            appendLine("ARGS: {\"key\": \"value\"}")
            appendLine("\nTo give a final answer: FINAL: <response>")
            if (facts.isNotEmpty()) {
                appendLine("\nKnown facts about this user:")
                facts.forEach { appendLine("- $it") }
            }
        }

        return buildList {
            add(ChatMessage("system", systemPrompt))
            history.forEach { msg ->
                add(ChatMessage(if (msg.sender == session.userId) "user" else "assistant", msg.text))
            }
            add(ChatMessage("user", currentMessage.text))
        }
    }
}
