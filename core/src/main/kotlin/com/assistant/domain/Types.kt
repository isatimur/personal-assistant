package com.assistant.domain

enum class Channel { TELEGRAM }

data class Message(
    val sender: String,
    val text: String,
    val channel: Channel,
    val attachments: List<String> = emptyList()
)

data class Session(val id: String, val userId: String, val channel: Channel)

data class ToolCall(val name: String, val arguments: Map<String, Any>)

sealed class Observation {
    data class Success(val result: String) : Observation()
    data class Error(val message: String) : Observation()
}

sealed class AgentStep {
    data class Think(val thought: String) : AgentStep()
    data class Act(val toolCall: ToolCall) : AgentStep()
    data class Observe(val observation: Observation) : AgentStep()
    data class Respond(val text: String) : AgentStep()
}
