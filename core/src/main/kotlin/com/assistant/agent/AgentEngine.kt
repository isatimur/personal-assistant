package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import kotlinx.serialization.json.*

class AgentEngine(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val assembler: ContextAssembler,
    private val maxSteps: Int = 10,
    private val compactionService: CompactionService? = null
) {
    private val actionRegex = Regex("""ACTION\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    private val argsRegex   = Regex("""ARGS\s*:\s*([\s\S]+?)(?=\nACTION|\nFINAL|\nTHOUGHT|$)""", RegexOption.IGNORE_CASE)
    private val finalRegex  = Regex("""FINAL\s*(?:ANSWER)?\s*:\s*([\s\S]+)""", RegexOption.IGNORE_CASE)

    suspend fun process(
        session: Session,
        message: Message,
        onProgress: ((String) -> Unit)? = null
    ): String {
        memory.append(session.id, message)
        compactionService?.maybeCompact(session.id, session.userId)
        val context = assembler.build(session, message).toMutableList()

        repeat(maxSteps) {
            val response = llm.complete(context)
            context.add(ChatMessage("assistant", response))

            val finalAnswer = finalRegex.find(response)?.groupValues?.get(1)?.trim()
            if (finalAnswer != null) {
                memory.append(session.id, Message("assistant", finalAnswer, session.channel))
                return finalAnswer
            }

            val toolName = actionRegex.find(response)?.groupValues?.get(1)?.trim()
            if (toolName != null) {
                onProgress?.invoke("🔧 Using $toolName…")
                val argsText = argsRegex.find(response)?.groupValues?.get(1)?.trim() ?: "{}"
                val args = parseArgs(argsText)
                val observation = toolRegistry.execute(ToolCall(toolName, args))
                val obs = when (observation) {
                    is Observation.Success -> "OBSERVATION: ${observation.result}"
                    is Observation.Error -> "OBSERVATION ERROR: ${observation.message}"
                }
                context.add(ChatMessage("user", obs))
            } else {
                memory.append(session.id, Message("assistant", response, session.channel))
                return response
            }
        }

        return "I reached the maximum reasoning steps. Please try a simpler request."
    }

    private fun parseArgs(json: String): Map<String, Any> = runCatching {
        Json.parseToJsonElement(json).jsonObject.entries.associate { (k, v) ->
            k to (if (v is JsonPrimitive) v.content else v.toString())
        }
    }.getOrElse { emptyMap() }
}
