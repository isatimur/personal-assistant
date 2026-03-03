package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import kotlinx.serialization.json.*
import java.util.logging.Logger

private const val MAX_SAFE_RESPONSE_LEN = 8_000

class AgentEngine(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val assembler: ContextAssembler,
    private val maxSteps: Int = 10,
    private val compactionService: CompactionService? = null,
    private val tokenTracker: TokenTracker? = null,
    private val plugins: List<EnginePlugin> = emptyList()
) {
    private val logger = Logger.getLogger(AgentEngine::class.java.name)

    suspend fun process(
        session: Session,
        message: Message,
        onProgress: ((String) -> Unit)? = null
    ): String {
        plugins.fireOnInput(session, message, logger)
        // Build context BEFORE appending to memory so history doesn't contain a duplicate
        // of the current user message (assembler adds it explicitly at the end).
        val context = assembler.build(session, message).toMutableList()
        memory.append(session.id, message)
        try {
            compactionService?.maybeCompact(session.id, session.userId)
        } catch (e: Exception) {
            logger.warning("Compaction failed for session ${session.id}: ${e.message}")
        }
        val commands = toolRegistry.allCommands()

        try {
            var toolCallsMade = false
            repeat(maxSteps) { stepIndex ->
                plugins.fireBeforeLlm(session, stepIndex, logger)
                val llmStart = System.currentTimeMillis()
                val completion = llm.completeWithFunctionsFast(context, commands)
                plugins.fireAfterLlm(session, stepIndex, completion.usage(), System.currentTimeMillis() - llmStart, logger)

                when (completion) {
                    is FunctionCompletion.FunctionCall -> {
                        toolCallsMade = true
                        tokenTracker?.record(session.id, completion.usage)
                        onProgress?.invoke("Using ${completion.name}...")
                        val args = parseArgsJson(completion.argsJson)
                        val call = ToolCall(completion.name, args)
                        plugins.fireBeforeTool(session, call, logger)
                        val toolStart = System.currentTimeMillis()
                        val observation = toolRegistry.execute(call)
                        plugins.fireAfterTool(session, call, observation, System.currentTimeMillis() - toolStart, logger)
                        val obs = when (observation) {
                            is Observation.Success -> "Result: ${observation.result}"
                            is Observation.Error   -> "Error: ${observation.message}"
                        }
                        context.add(ChatMessage("assistant", "Used ${completion.name}"))
                        context.add(ChatMessage("user", obs))
                    }
                    is FunctionCompletion.Text -> {
                        // If tool calls were made, re-invoke the standard model for the final synthesis.
                        // Streaming is used so channel adapters can display tokens as they arrive.
                        // For simple queries that needed no tools, the fast model response is used directly.
                        val finalText = if (toolCallsMade) {
                            // Final synthesis — fires LLM hooks with the same stepIndex as the reasoning
                            // step that produced the Text completion (synthesis continues the same step).
                            plugins.fireBeforeLlm(session, stepIndex, logger)
                            val start = System.currentTimeMillis()
                            val text = llm.stream(context) { token ->
                                onProgress?.invoke("$STREAM_TOKEN_PREFIX$token")
                            }
                            plugins.fireAfterLlm(session, stepIndex, null, System.currentTimeMillis() - start, logger)
                            text
                        } else {
                            completion.content
                        }
                        tokenTracker?.record(session.id, if (toolCallsMade) null else completion.usage)
                        memory.append(session.id, Message("assistant", finalText, session.channel))
                        plugins.fireOnResponse(session, finalText, stepIndex + 1, logger)
                        return finalText
                    }
                }
            }

            return "I reached the maximum reasoning steps. Please try a simpler request."
        } catch (e: Exception) {
            plugins.fireOnError(session, e, logger)
            throw e
        }
    }

    private fun parseArgsJson(json: String): Map<String, Any> = runCatching {
        Json.parseToJsonElement(json).jsonObject.entries.associate { (k, v) ->
            k to (if (v is JsonPrimitive) v.content else v.toString())
        }
    }.getOrElse { emptyMap() }

    private fun FunctionCompletion.usage(): TokenUsage? = when (this) {
        is FunctionCompletion.Text         -> usage
        is FunctionCompletion.FunctionCall -> usage
    }
}

// --- plugin fire helpers (file-private, swallow all exceptions) ---

private suspend fun List<EnginePlugin>.fireOnInput(session: Session, message: Message, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.onInput(session, message) }
            .onFailure { log.warning("Plugin '$pluginName' onInput: ${it.message}") }
    }
}

private suspend fun List<EnginePlugin>.fireBeforeTool(session: Session, call: ToolCall, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.beforeTool(session, call) }
            .onFailure { log.warning("Plugin '$pluginName' beforeTool: ${it.message}") }
    }
}

private suspend fun List<EnginePlugin>.fireAfterTool(session: Session, call: ToolCall, obs: Observation, ms: Long, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.afterTool(session, call, obs, ms) }
            .onFailure { log.warning("Plugin '$pluginName' afterTool: ${it.message}") }
    }
}

private suspend fun List<EnginePlugin>.fireBeforeLlm(session: Session, step: Int, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.beforeLlm(session, step) }
            .onFailure { log.warning("Plugin '$pluginName' beforeLlm: ${it.message}") }
    }
}

private suspend fun List<EnginePlugin>.fireAfterLlm(session: Session, step: Int, usage: TokenUsage?, ms: Long, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.afterLlm(session, step, usage, ms) }
            .onFailure { log.warning("Plugin '$pluginName' afterLlm: ${it.message}") }
    }
}

private suspend fun List<EnginePlugin>.fireOnResponse(session: Session, text: String, steps: Int, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.onResponse(session, text, steps) }
            .onFailure { log.warning("Plugin '$pluginName' onResponse: ${it.message}") }
    }
}

private suspend fun List<EnginePlugin>.fireOnError(session: Session, error: Exception, log: Logger) {
    for (plugin in this) {
        val pluginName = plugin.name
        runCatching { plugin.onError(session, error) }
            .onFailure { log.warning("Plugin '$pluginName' onError: ${it.message}") }
    }
}
