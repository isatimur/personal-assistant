package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

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
        coEvery { llm.stream(any(), any()) } coAnswers {
            val onToken = secondArg<suspend (String) -> Unit>()
            onToken("Found files in /tmp")
            "Found files in /tmp"
        }
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
        coEvery { llm.stream(any(), any()) } coAnswers { "Done" }
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
        coEvery { llm.stream(any(), any()) } coAnswers {
            val onToken = secondArg<suspend (String) -> Unit>()
            onToken("done")
            "done"
        }
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt")

        val progressMessages = mutableListOf<String>()
        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5)
        engine.process(
            Session("s1", "user1", Channel.TELEGRAM),
            Message("user1", "list files", Channel.TELEGRAM),
            onProgress = { progressMessages.add(it) }
        )
        // Tool progress message should appear; streaming tokens arrive prefixed with STREAM_TOKEN_PREFIX
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
        // Streaming synthesis step: token usage is not reported (returns null)
        coEvery { llm.stream(any(), any()) } coAnswers { "done" }
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("a.txt")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, maxSteps = 5, tokenTracker = tracker)
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        val stats = tracker.sessionStats("s1")
        // Only the function call usage is tracked (100 in + 50 out); streaming synthesis has no usage
        assertEquals(100L, stats.inputTokens)
        assertEquals(50L, stats.outputTokens)
    }

    @Test
    fun `beforeTool and afterTool fire with correct args`() = runTest {
        setupCommon()
        val plugin = mockk<EnginePlugin>(relaxed = true)
        coEvery { assembler.build(any(), any()) } returns listOf(ChatMessage("user", "do it"))
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returnsMany listOf(
            FunctionCompletion.FunctionCall("shell_run", "{\"command\": \"echo hi\"}"),
            FunctionCompletion.Text("placeholder")
        )
        coEvery { llm.stream(any(), any()) } coAnswers { "done" }
        coEvery { toolRegistry.execute(any()) } returns Observation.Success("hi")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "do it", Channel.TELEGRAM))

        coVerify { plugin.beforeTool(any(), ToolCall("shell_run", mapOf("command" to "echo hi"))) }
        coVerify { plugin.afterTool(any(), ToolCall("shell_run", mapOf("command" to "echo hi")), Observation.Success("hi"), any()) }
    }

    @Test
    fun `onResponse fires with final text and step count`() = runTest {
        setupCommon()
        val plugin = mockk<EnginePlugin>(relaxed = true)
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        coVerify { plugin.onResponse(any(), "Hello!", any()) }
    }

    @Test
    fun `plugin exception does not fail the request`() = runTest {
        setupCommon()
        val plugin = mockk<EnginePlugin>(relaxed = true)
        coEvery { plugin.onResponse(any(), any(), any()) } throws RuntimeException("plugin exploded")
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
        val result = engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        assertEquals("Hello!", result)
    }

    @Test
    fun `onError fires when LLM throws`() = runTest {
        setupCommon()
        val plugin = mockk<EnginePlugin>(relaxed = true)
        coEvery { llm.completeWithFunctionsFast(any(), any()) } throws RuntimeException("LLM down")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
        assertThrows<RuntimeException> {
            engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))
        }
        coVerify { plugin.onError(any(), any()) }
    }

    @Test
    fun `beforeLlm and afterLlm fire for each LLM call`() = runTest {
        setupCommon()
        val plugin = mockk<EnginePlugin>(relaxed = true)
        coEvery { llm.completeWithFunctionsFast(any(), any()) } returns FunctionCompletion.Text("Hello!")

        val engine = AgentEngine(llm, memory, toolRegistry, assembler, plugins = listOf(plugin))
        engine.process(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))

        coVerify(atLeast = 1) { plugin.beforeLlm(any(), any()) }
        coVerify(atLeast = 1) { plugin.afterLlm(any(), any(), any(), any()) }
    }
}
