package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.ToolPort
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ToolRegistryTest {
    @Test
    fun `dispatches to correct tool by command prefix`() = runTest {
        val shellTool = mockk<ToolPort>()
        every { shellTool.name } returns "shell"
        coEvery { shellTool.execute(any()) } returns Observation.Success("ok")

        val registry = ToolRegistry(listOf(shellTool))
        val result = registry.execute(ToolCall("shell_run", mapOf("command" to "echo hi")))
        assertTrue(result is Observation.Success)
    }

    @Test
    fun `dispatches when call name exactly matches tool name`() = runTest {
        val shellTool = mockk<ToolPort>()
        every { shellTool.name } returns "shell"
        coEvery { shellTool.execute(any()) } returns Observation.Success("ok")

        val registry = ToolRegistry(listOf(shellTool))
        val result = registry.execute(ToolCall("shell", mapOf()))
        assertTrue(result is Observation.Success)
    }

    @Test
    fun `does not dispatch tool name as prefix of unrelated command`() = runTest {
        val shellTool = mockk<ToolPort>()
        every { shellTool.name } returns "shell"
        coEvery { shellTool.execute(any()) } returns Observation.Success("ok")

        // "shellfish_open" starts with "shell" but is NOT "shell" and does NOT start with "shell_"
        val registry = ToolRegistry(listOf(shellTool))
        val result = registry.execute(ToolCall("shellfish_open", mapOf()))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `returns error for unknown tool`() = runTest {
        val result = ToolRegistry(emptyList()).execute(ToolCall("unknown_cmd", mapOf()))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `describe lists all tools`() {
        val tool = mockk<ToolPort>()
        every { tool.name } returns "file_system"
        every { tool.description } returns "File operations"
        val desc = ToolRegistry(listOf(tool)).describe()
        assertTrue(desc.contains("file_system"))
        assertTrue(desc.contains("File operations"))
    }
}
