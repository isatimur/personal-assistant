package com.assistant.agent

data class AgentProfile(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val systemPromptExtension: String
)

object AgentRouter {
    /** Returns the first profile whose any trigger appears (case-insensitive) in [message], or null. */
    fun route(message: String, profiles: List<AgentProfile>): AgentProfile? {
        val lower = message.lowercase()
        return profiles.firstOrNull { profile ->
            profile.triggers.any { trigger -> lower.contains(trigger.lowercase()) }
        }
    }
}
