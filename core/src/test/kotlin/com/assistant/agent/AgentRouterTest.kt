package com.assistant.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentRouterTest {

    private fun profile(name: String, vararg triggers: String) = AgentProfile(
        name = name,
        description = "",
        triggers = triggers.toList(),
        systemPromptExtension = "Extension for $name"
    )

    @Test
    fun `routes to profile whose trigger appears in message`() {
        val profiles = listOf(profile("researcher", "research", "look up"))
        val result = AgentRouter.route("can you research Kotlin coroutines", profiles)
        assertNotNull(result)
        assertEquals("researcher", result!!.name)
    }

    @Test
    fun `routing is case-insensitive`() {
        val profiles = listOf(profile("researcher", "Research"))
        val result = AgentRouter.route("RESEARCH this topic", profiles)
        assertNotNull(result)
        assertEquals("researcher", result!!.name)
    }

    @Test
    fun `returns null when no trigger matches`() {
        val profiles = listOf(profile("researcher", "research", "look up"))
        val result = AgentRouter.route("hello world", profiles)
        assertNull(result)
    }

    @Test
    fun `returns first matching profile when multiple could match`() {
        val profiles = listOf(
            profile("researcher", "code", "research"),
            profile("engineer", "code", "debug")
        )
        val result = AgentRouter.route("code review please", profiles)
        assertNotNull(result)
        assertEquals("researcher", result!!.name)
    }

    @Test
    fun `returns null for empty profile list`() {
        assertNull(AgentRouter.route("research something", emptyList()))
    }

    @Test
    fun `returns null for empty message`() {
        val profiles = listOf(profile("researcher", "research"))
        assertNull(AgentRouter.route("", profiles))
    }
}
