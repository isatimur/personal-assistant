package com.assistant.agent

import com.assistant.domain.Observation
import com.assistant.domain.Session
import com.assistant.domain.ToolCall
import com.assistant.ports.EnginePlugin
import com.assistant.ports.FeedbackPort
import com.assistant.ports.Signal
import com.assistant.ports.SignalType

class FeedbackPlugin(
    private val store: FeedbackPort,
    private val userId: String
) : EnginePlugin {

    override val name = "FeedbackPlugin"

    override suspend fun afterTool(session: Session, call: ToolCall, result: Observation, durationMs: Long) {
        if (result is Observation.Error) {
            store.recordSignal(Signal(
                sessionId = session.id,
                userId    = userId,
                type      = SignalType.TOOL_ERROR,
                context   = "${call.name}: ${result.message.take(120)}"
            ))
        }
    }

    override suspend fun onResponse(session: Session, text: String, steps: Int) {
        when {
            steps > 5 -> store.recordSignal(Signal(
                sessionId = session.id,
                userId    = userId,
                type      = SignalType.HIGH_STEPS,
                context   = "steps=$steps"
            ))
            steps == 1 -> store.recordSignal(Signal(
                sessionId = session.id,
                userId    = userId,
                type      = SignalType.APPROVAL,
                context   = "single-step response"
            ))
        }
    }

    suspend fun recordUserMessage(sessionId: String, text: String) {
        val correctionKeywords = listOf(
            "no,", "wrong", "actually", "that's not", "correction:", "incorrect", "mistake"
        )
        if (correctionKeywords.any { text.lowercase().contains(it) }) {
            store.recordSignal(Signal(
                sessionId = sessionId,
                userId    = userId,
                type      = SignalType.CORRECTION,
                context   = text.take(120)
            ))
        }
    }
}
