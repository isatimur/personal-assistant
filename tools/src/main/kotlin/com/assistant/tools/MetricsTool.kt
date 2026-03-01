package com.assistant.tools

import com.assistant.agent.TokenTracker
import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.FeedbackPort
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort

class MetricsTool(
    private val feedbackStore: FeedbackPort,
    private val tokenTracker: TokenTracker,
    private val userId: String
) : ToolPort {

    override val name = "metrics"
    override val description = "View assistant performance metrics and learning stats"

    override fun commands() = listOf(
        CommandSpec(
            name        = "metrics_summary",
            description = "Show performance metrics: sessions, correction rate, top tools, token cost",
            params      = listOf(
                ParamSpec(
                    name        = "period",
                    type        = "string",
                    description = "Time period: 'today', 'week', 'month' (default: week)",
                    required    = false
                )
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        val period = call.arguments["period"]?.toString() ?: "week"
        val sinceMs = when (period) {
            "today" -> System.currentTimeMillis() - 86_400_000L
            "month" -> System.currentTimeMillis() - 30 * 86_400_000L
            else    -> System.currentTimeMillis() - 7 * 86_400_000L
        }
        val stats = feedbackStore.stats(userId, sinceMs)
        val tokenStats = tokenTracker.globalStats()
        val correctionRate = if (stats.totalSessions > 0)
            "%.0f%%".format(stats.corrections.toDouble() / stats.totalSessions * 100)
        else "0%"
        val costUsd = (tokenStats.inputTokens * 3.0 + tokenStats.outputTokens * 15.0) / 1_000_000.0

        return Observation.Success("""
            📊 Metrics ($period)
            Sessions: ${stats.totalSessions}
            Corrections: ${stats.corrections} (rate: $correctionRate)
            Tool errors: ${stats.toolErrors}
            High-step responses: ${stats.highSteps}
            Avg response quality: ${if (stats.approvals > stats.corrections) "good ✓" else "needs work ⚠️"}
            Tokens used: ${tokenStats.inputTokens} in / ${tokenStats.outputTokens} out
            Estimated cost: ${"$"}${"%.4f".format(costUsd)}
        """.trimIndent())
    }
}
