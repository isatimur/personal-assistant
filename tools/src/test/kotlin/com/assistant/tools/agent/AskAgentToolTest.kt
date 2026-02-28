package com.assistant.tools.agent

import com.assistant.agent.AgentBus
import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AskAgentToolTest {

    private val bus = mockk<AgentBus>()

    @Test
    fun `executes agent_ask command and returns response`() = runTest {
        coEvery { bus.request(from = "personal", to = "work-agent", message = "What is 2+2?", timeoutMs = any(), ephemeral = any()) } returns "4"

        val tool = AskAgentTool(bus, callerName = "personal")
        val result = tool.execute(ToolCall("agent_ask", mapOf("to" to "work-agent", "message" to "What is 2+2?")))

        assertTrue(result is Observation.Success)
        assertEquals("4", (result as Observation.Success).result)
        coVerify { bus.request(from = "personal", to = "work-agent", message = "What is 2+2?", timeoutMs = any(), ephemeral = any()) }
    }

    @Test
    fun `returns error when to is missing`() = runTest {
        val tool = AskAgentTool(bus, callerName = "personal")
        val result = tool.execute(ToolCall("agent_ask", mapOf("message" to "hello")))

        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("to"))
    }

    @Test
    fun `returns error when message is missing`() = runTest {
        val tool = AskAgentTool(bus, callerName = "personal")
        val result = tool.execute(ToolCall("agent_ask", mapOf("to" to "work-agent")))

        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("message"))
    }

    @Test
    fun `returns error when messaging self`() = runTest {
        val tool = AskAgentTool(bus, callerName = "personal")
        val result = tool.execute(ToolCall("agent_ask", mapOf("to" to "personal", "message" to "hello")))

        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("itself"))
    }

    @Test
    fun `passes configured timeout to bus request`() = runTest {
        coEvery { bus.request(from = "personal", to = "work-agent", message = "ping", timeoutMs = 5_000, ephemeral = any()) } returns "pong"

        val tool = AskAgentTool(bus, callerName = "personal", timeoutMs = 5_000)
        val result = tool.execute(ToolCall("agent_ask", mapOf("to" to "work-agent", "message" to "ping")))

        assertTrue(result is Observation.Success)
        coVerify { bus.request(from = "personal", to = "work-agent", message = "ping", timeoutMs = 5_000, ephemeral = any()) }
    }

    @Test
    fun `passes ephemeral flag to bus`() = runTest {
        coEvery { bus.request(from = "personal", to = "work-agent", message = "ping", timeoutMs = any(), ephemeral = true) } returns "pong"

        val tool = AskAgentTool(bus, callerName = "personal", ephemeral = true)
        val result = tool.execute(ToolCall("agent_ask", mapOf("to" to "work-agent", "message" to "ping")))

        assertTrue(result is Observation.Success)
        coVerify { bus.request(from = "personal", to = "work-agent", message = "ping", timeoutMs = any(), ephemeral = true) }
    }
}
