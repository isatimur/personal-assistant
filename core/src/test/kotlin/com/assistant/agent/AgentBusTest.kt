package com.assistant.agent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentBusTest {

    @Test
    fun `request routes to registered agent`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("worker") { _, message -> "response to: $message" }

        val result = bus.request(from = "caller", to = "worker", message = "hello")
        assertEquals("response to: hello", result)
    }

    @Test
    fun `unknown agent returns error string`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)

        val result = bus.request(from = "caller", to = "no-such-agent", message = "hello")
        assertTrue(result.startsWith("Error:"), "Expected error but got: $result")
        assertTrue(result.contains("no-such-agent"))
    }

    @Test
    fun `timeout returns error string`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("slow") { _, _ ->
            kotlinx.coroutines.delay(60_000)
            "too late"
        }

        val result = bus.request(from = "caller", to = "slow", message = "hurry", timeoutMs = 100)
        assertTrue(result.startsWith("Error:"), "Expected timeout error but got: $result")
        assertTrue(result.contains("timed out"))
    }

    @Test
    fun `passes caller name to handler`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("worker") { from, _ -> "echo:$from" }

        val result = bus.request(from = "personal", to = "worker", message = "ping")
        assertEquals("echo:personal", result)
    }

    @Test
    fun `multiple agents can be registered independently`() = runTest {
        val bus = InProcessAgentBus(backgroundScope)
        bus.registerAgent("agent-a") { _, _ -> "from-a" }
        bus.registerAgent("agent-b") { _, _ -> "from-b" }

        assertEquals("from-a", bus.request(from = "caller", to = "agent-a", message = "ping"))
        assertEquals("from-b", bus.request(from = "caller", to = "agent-b", message = "ping"))
    }
}
