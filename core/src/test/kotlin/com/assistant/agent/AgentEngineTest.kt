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

    @Test
    fun `parses multi-line ARGS correctly`() = runTest {
        val multiLineResponse = """
            THOUGHT: need to write a file
            ACTION: file_write
            ARGS: {
              "path": "/tmp/test.txt",
              "content": "hello world"
            }
        """.trimIndent()
        val capturedCall = slot<ToolCall>()
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "write"))
        coEvery { llm.complete(any()) } returnsMany listOf(
            multiLineResponse,
            "FINAL: Done"
        )
        coEvery { toolRegistry.execute(capture(capturedCall)) } returns Observation.Success("Written")
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "write", Channel.TELEGRAM))

        assertEquals("file_write", capturedCall.captured.name)
        assertEquals("/tmp/test.txt", capturedCall.captured.arguments["path"])
    }

    @Test
    fun `malformed response with no markers returned as final answer`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "hi"))
        coEvery { llm.complete(any()) } returns "I am just talking without any markers."
        coEvery { memory.append(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))
        assertEquals("I am just talking without any markers.", response)
    }

    @Test
    fun `onProgress callback fires with tool name before execution`() = runTest {
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "list files"))
        coEvery { llm.complete(any()) } returnsMany listOf(
            "THOUGHT: need files\nACTION: file_list\nARGS: {\"path\": \"/tmp\"}",
            "FINAL: done"
        )
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt")
        coEvery { memory.append(any(), any()) } just runs

        val progressMessages = mutableListOf<String>()
        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        engine.process(
            Session("s1", "user1", Channel.TELEGRAM),
            Message("user1", "list files", Channel.TELEGRAM),
            onProgress = { progressMessages.add(it) }
        )
        assertTrue(progressMessages.any { it.contains("file_list") })
    }

    @Test
    fun `compaction is called before context build`() = runTest {
        val compaction = mockk<CompactionService>()
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "hi"))
        coEvery { llm.complete(any()) } returns "FINAL: Hello!"
        coEvery { memory.append(any(), any()) } just runs
        coEvery { compaction.maybeCompact(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, compactionService = compaction)
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        coVerify { compaction.maybeCompact("s1", "user1") }
    }
}
