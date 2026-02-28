package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.TokenUsage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.logging.*

class LoggingPluginTest {
    private val session = Session("s1", "user1", Channel.TELEGRAM)
    private val call = ToolCall("shell_run", mapOf("command" to "echo hi"))

    @Test
    fun `beforeTool does not throw`() = runTest {
        LoggingPlugin().beforeTool(session, call)
    }

    @Test
    fun `afterTool does not throw on success`() = runTest {
        LoggingPlugin().afterTool(session, call, Observation.Success("hi"), 42L)
    }

    @Test
    fun `afterTool does not throw on error`() = runTest {
        LoggingPlugin().afterTool(session, call, Observation.Error("boom"), 10L)
    }

    @Test
    fun `afterLlm does not throw with null usage`() = runTest {
        LoggingPlugin().afterLlm(session, 0, null, 100L)
    }

    @Test
    fun `afterLlm does not throw with real usage`() = runTest {
        LoggingPlugin().afterLlm(session, 1, TokenUsage(100, 50), 200L)
    }

    @Test
    fun `onResponse does not throw`() = runTest {
        LoggingPlugin().onResponse(session, "Hello!", 2)
    }

    @Test
    fun `name returns class simple name`() {
        assertEquals("LoggingPlugin", LoggingPlugin().name)
    }
}
