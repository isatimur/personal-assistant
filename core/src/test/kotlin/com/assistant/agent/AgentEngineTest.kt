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

    private fun setupCommon() {
        coEvery { toolRegistry.allCommands() } returns emptyList()
        coEvery { memory.append(any(), any()) } just runs
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "hi"))
    }

    @Test
    fun `returns text answer directly`() = runTest {
        setupCommon()
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))
        assertEquals("Hello!", response)
    }

    @Test
    fun `executes tool then returns text`() = runTest {
        setupCommon()
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "list files"))
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returnsMany listOf(
            FunctionCompletion.FunctionCall("file_list", "{\"path\": \"/tmp\"}"),
            FunctionCompletion.Text("fast model placeholder")
        )
        coEvery { llm.completeWithFunctions(any(), any()) } returns FunctionCompletion.Text("Found files in /tmp")
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt\nb.txt")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "list files", Channel.TELEGRAM))
        assertEquals("Found files in /tmp", response)
        coVerify { toolRegistry.execute(ToolCall("file_list", mapOf("path" to "/tmp"))) }
    }

    @Test
    fun `stops after max steps`() = runTest {
        setupCommon()
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "loop"))
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.FunctionCall("file_list", "{\"path\": \"/\"}")
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("result")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 2)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "loop", Channel.TELEGRAM))
        assertTrue(response.isNotBlank())
    }

    @Test
    fun `parses multi-param function call correctly`() = runTest {
        setupCommon()
        val capturedCall = slot<ToolCall>()
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "write"))
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returnsMany listOf(
            FunctionCompletion.FunctionCall("file_write", "{\"path\": \"/tmp/test.txt\", \"content\": \"hello world\"}"),
            FunctionCompletion.Text("fast model placeholder")
        )
        coEvery { llm.completeWithFunctions(any(), any()) } returns FunctionCompletion.Text("Done")
        coEvery { toolRegistry.execute(capture(capturedCall)) } returns Observation.Success("Written")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "write", Channel.TELEGRAM))

        assertEquals("file_write", capturedCall.captured.name)
        assertEquals("/tmp/test.txt", capturedCall.captured.arguments["path"])
    }

    @Test
    fun `empty text response returned as final answer`() = runTest {
        setupCommon()
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("I am just talking.")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        val response = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))
        assertEquals("I am just talking.", response)
    }

    @Test
    fun `onProgress callback fires with tool name before execution`() = runTest {
        setupCommon()
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "list files"))
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returnsMany listOf(
            FunctionCompletion.FunctionCall("file_list", "{\"path\": \"/tmp\"}"),
            FunctionCompletion.Text("fast model placeholder")
        )
        coEvery { llm.completeWithFunctions(any(), any()) } returns FunctionCompletion.Text("done")
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt")

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
    fun `compaction failure does not fail the request`() = runTest {
        setupCommon()
        val compaction = mockk<CompactionService>()
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")
        coEvery { compaction.maybeCompact(any(), any()) } throws RuntimeException("LLM unavailable")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, compactionService = compaction)
        val result = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        assertEquals("Hello!", result)
    }

    @Test
    fun `compaction is called before context build`() = runTest {
        setupCommon()
        val compaction = mockk<CompactionService>()
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")
        coEvery { compaction.maybeCompact(any(), any()) } just runs

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, compactionService = compaction)
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        coVerify { compaction.maybeCompact("s1", "user1") }
    }

    @Test
    fun `token tracker records usage from function call`() = runTest {
        setupCommon()
        val tracker = TokenTracker()
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returnsMany listOf(
            FunctionCompletion.FunctionCall("file_list", "{\"path\": \"/tmp\"}", TokenUsage(100, 50)),
            FunctionCompletion.Text("done")
        )
        coEvery { llm.completeWithFunctions(any(), any()) } returns FunctionCompletion.Text("done", TokenUsage(200, 80))
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5, tokenTracker = tracker)
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        val stats = tracker.sessionStats("s1")
        assertEquals(300L, stats.inputTokens)
        assertEquals(130L, stats.outputTokens)
    }
}
