package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SysInfoToolTest {

    private val tool = SysInfoTool()

    @Test
    fun `sysinfo_get returns OS, CPU and memory info`() = runTest {
        val result = tool.execute(ToolCall("sysinfo_get", emptyMap()))
        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("OS:"), "Expected OS info, got: $text")
        assertTrue(text.contains("CPUs:"), "Expected CPU info, got: $text")
        assertTrue(text.contains("Memory:"), "Expected memory info, got: $text")
    }

    @Test
    fun `unknown command returns error`() = runTest {
        val result = tool.execute(ToolCall("sysinfo_unknown", emptyMap()))
        assertTrue(result is Observation.Error)
    }
}
