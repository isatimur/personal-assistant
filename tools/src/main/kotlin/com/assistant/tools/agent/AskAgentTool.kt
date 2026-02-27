package com.assistant.tools.agent

import com.assistant.agent.AgentBus
import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort

class AskAgentTool(
    private val bus: AgentBus,
    private val callerName: String,
    private val timeoutMs: Long = 30_000
) : ToolPort {

    override val name = "agent"
    override val description = "Delegate a task or question to another named agent and get its response."

    override fun commands() = listOf(
        CommandSpec(
            name = "agent_ask",
            description = "Send a message to another agent and wait for its response.",
            params = listOf(
                ParamSpec("to", "string", "Name of the target agent (e.g. 'work-agent')", required = true),
                ParamSpec("message", "string", "The message or task to send", required = true)
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        val to = call.arguments["to"]?.toString()
            ?: return Observation.Error("Missing 'to' argument")
        val message = call.arguments["message"]?.toString()
            ?: return Observation.Error("Missing 'message' argument")
        if (to == callerName) return Observation.Error("Agent cannot message itself")
        return Observation.Success(bus.request(from = callerName, to = to, message = message, timeoutMs = timeoutMs))
    }
}
