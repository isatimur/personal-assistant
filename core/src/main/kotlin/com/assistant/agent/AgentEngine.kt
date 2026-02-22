package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import kotlinx.serialization.json.*

class AgentEngine(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val assembler: ContextAssembler,
    private val maxSteps: Int = 10
) {
    suspend fun process(session: Session, message: Message): String {
        memory.append(session.id, message)
        val context = assembler.build(session, message).toMutableList()

        repeat(maxSteps) {
            val response = llm.complete(context)
            context.add(ChatMessage("assistant", response))

            if (response.contains("FINAL:")) {
                val answer = response.substringAfter("FINAL:").trim()
                memory.append(session.id, Message("assistant", answer, session.channel))
                return answer
            }

            if (response.contains("ACTION:")) {
                val toolName = response.substringAfter("ACTION:").lines().first().trim()
                val argsLine = response.substringAfter("ARGS:").lines().first().trim()
                val args = parseArgs(argsLine)
                val observation = toolRegistry.execute(ToolCall(toolName, args))
                val obs = when (observation) {
                    is Observation.Success -> "OBSERVATION: ${observation.result}"
                    is Observation.Error -> "OBSERVATION ERROR: ${observation.message}"
                }
                context.add(ChatMessage("user", obs))
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
