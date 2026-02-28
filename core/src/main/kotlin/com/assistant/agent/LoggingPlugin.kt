package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import java.util.logging.Logger

class LoggingPlugin(
    private val logger: Logger = Logger.getLogger("AgentEngine")
) : EnginePlugin {

    override suspend fun beforeTool(session: Session, call: ToolCall) {
        logger.info("[${session.id}] → tool: ${call.name}")
    }

    override suspend fun afterTool(session: Session, call: ToolCall, result: Observation, durationMs: Long) {
        val status = if (result is Observation.Success) "ok" else "error"
        logger.info("[${session.id}] ← tool: ${call.name} ($status, ${durationMs}ms)")
    }

    override suspend fun afterLlm(session: Session, stepIndex: Int, usage: TokenUsage?, durationMs: Long) {
        logger.fine("[${session.id}] llm step=$stepIndex in=${usage?.inputTokens} out=${usage?.outputTokens} (${durationMs}ms)")
    }

    override suspend fun onResponse(session: Session, text: String, steps: Int) {
        logger.info("[${session.id}] responded after $steps step(s)")
    }
}
