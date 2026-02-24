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
    private val compactionService: CompactionService? = null,
    private val tokenTracker: TokenTracker? = null
) {
    private val logger = java.util.logging.Logger.getLogger(AgentEngine::class.java.name)

    suspend fun process(
        session: Session,
        message: Message,
        onProgress: ((String) -> Unit)? = null
    ): String {
        memory.append(session.id, message)
        try {
            compactionService?.maybeCompact(session.id, session.userId)
        } catch (e: Exception) {
            logger.warning("Compaction failed for session ${session.id}: ${e.message}")
        }
        val context = assembler.build(session, message).toMutableList()
        val commands = toolRegistry.allCommands()

        repeat(maxSteps) {
            val completion = llm.completeWithFunctions(context, commands)
            when (completion) {
                is FunctionCompletion.FunctionCall -> {
                    tokenTracker?.record(session.id, completion.usage)
                    onProgress?.invoke("Using ${completion.name}...")
                    val args = parseArgsJson(completion.argsJson)
                    val observation = toolRegistry.execute(ToolCall(completion.name, args))
                    val obs = when (observation) {
                        is Observation.Success -> "Result: ${observation.result}"
                        is Observation.Error -> "Error: ${observation.message}"
                    }
                    context.add(ChatMessage("assistant", "Used ${completion.name}"))
                    context.add(ChatMessage("user", obs))
                }
                is FunctionCompletion.Text -> {
                    tokenTracker?.record(session.id, completion.usage)
                    val answer = completion.content
                    memory.append(session.id, Message("assistant", answer, session.channel))
                    return answer
                }
            }
        }

        return "I reached the maximum reasoning steps. Please try a simpler request."
    }

    private fun parseArgsJson(json: String): Map<String, Any> = runCatching {
        Json.parseToJsonElement(json).jsonObject.entries.associate { (k, v) ->
            k to (if (v is JsonPrimitive) v.content else v.toString())
        }
    }.getOrElse { emptyMap() }
}
