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
        val fileTool = mockk<ToolPort>()
        every { fileTool.name } returns "file_system"
        coEvery { fileTool.execute(any()) } returns Observation.Success("ok")

        val registry = ToolRegistry(listOf(fileTool))
        val result = registry.execute(ToolCall("file_read", mapOf("path" to "/tmp/test.txt")))
        assertTrue(result is Observation.Success)
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
