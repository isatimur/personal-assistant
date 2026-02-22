package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.ToolPort

class ToolRegistry(private val tools: List<ToolPort>) {
    suspend fun execute(call: ToolCall): Observation {
        val tool = tools.find { call.name.startsWith(it.name.substringBefore("_")) }
            ?: return Observation.Error("No tool found for command: ${call.name}")
        return tool.execute(call)
    }

    fun describe(): String = tools.joinToString("\n\n") { "Tool: ${it.name}\n${it.description}" }
}
