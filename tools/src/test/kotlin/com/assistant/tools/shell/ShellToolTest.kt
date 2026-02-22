package com.assistant.tools.shell

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ShellToolTest {
    private val tool = ShellTool(timeoutSeconds = 5, maxOutputChars = 1000)

    @Test
    fun `echo command returns output`() = runTest {
        val result = tool.execute(ToolCall("shell_run", mapOf("command" to "echo hello")))
        assertEquals("hello", (result as Observation.Success).result.trim())
    }

    @Test
    fun `output is truncated when too long`() = runTest {
        val result = tool.execute(ToolCall("shell_run", mapOf("command" to "yes | head -c 2000")))
        assertTrue((result as Observation.Success).result.length <= 1000)
    }

    @Test
    fun `unknown command name returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("unknown_cmd", mapOf())) is Observation.Error)
    }
}
