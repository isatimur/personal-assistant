package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class ContextAssembler(
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val windowSize: Int = 20,
    private val searchLimit: Int = 5
) {
    suspend fun build(session: Session, currentMessage: Message): List<ChatMessage> {
        val (facts, history, relevant) = coroutineScope {
            val f = async { memory.facts(session.userId) }
            val h = async { memory.history(session.id, windowSize) }
            val r = async { memory.search(session.userId, currentMessage.text, searchLimit) }
            Triple(f.await(), h.await(), r.await())
        }

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
            if (relevant.isNotEmpty()) {
                appendLine("\nRelevant past context:")
                relevant.forEach { appendLine(it) }
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
