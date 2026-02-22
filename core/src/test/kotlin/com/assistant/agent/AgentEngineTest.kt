package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AgentEngineTest {
    private val llm = mockk<LlmPort>()
    private val memory = mockk<MemoryPort>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val assembler = mockk<ContextAssembler>()

    @Test
    fun `returns FINAL answer directly`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "hi"))
        coEvery { llm.complete(any()) } returns "FINAL: Hello!"
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))
        assertEquals("Hello!", response)
    }

    @Test
    fun `executes tool then returns FINAL`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "list files"))
        coEvery { llm.complete(any()) } returnsMany listOf(
            "THOUGHT: list files\nACTION: file_list\nARGS: {\"path\": \"/tmp\"}",
            "FINAL: Found files in /tmp"
        )
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt\nb.txt")
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "list files", Channel.TELEGRAM))
        assertEquals("Found files in /tmp", response)
        coVerify { toolRegistry.execute(ToolCall("file_list", mapOf("path" to "/tmp"))) }
    }

    @Test
    fun `stops after max steps`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "loop"))
        coEvery { llm.complete(any()) } returns "ACTION: file_list\nARGS: {\"path\": \"/\"}"
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("result")
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 2)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "loop", Channel.TELEGRAM))
        assertTrue(response.isNotBlank())
    }
}
