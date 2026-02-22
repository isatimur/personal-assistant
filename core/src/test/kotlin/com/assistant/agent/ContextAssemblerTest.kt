package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlin.system.measureTimeMillis

class ContextAssemblerTest {
    private val memory = mockk<MemoryPort>()
    private val registry = mockk<ToolRegistry>()

    @Test
    fun `system message is first`() = runTest {
        coEvery { memory.history(any(), any()) } returns emptyList()
        coEvery { memory.facts(any()) } returns emptyList()
        coEvery { memory.search(any(), any(), any()) } returns emptyList()
        every { registry.describe() } returns "Tool: file_system\nFile ops"

        val assembler = ContextAssembler(memory, registry, 10)
        val messages = assembler.build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hello", Channel.TELEGRAM))
        assertEquals("system", messages.first().role)
        assertTrue(messages.last().content.contains("hello"))
    }

    @Test
    fun `history and facts are included`() = runTest {
        coEvery { memory.history(any(), any()) } returns listOf(Message("user1", "previous", Channel.TELEGRAM))
        coEvery { memory.facts(any()) } returns listOf("User likes brevity")
        coEvery { memory.search(any(), any(), any()) } returns emptyList()
        every { registry.describe() } returns ""

        val assembler = ContextAssembler(memory, registry, 10)
        val messages = assembler.build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "new", Channel.TELEGRAM))
        val contents = messages.map { it.content }
        assertTrue(contents.any { it.contains("previous") })
        assertTrue(contents.any { it.contains("User likes brevity") })
    }

    @Test
    fun `relevant chunks from search are included in system prompt`() = runTest {
        coEvery { memory.history(any(), any()) } returns emptyList()
        coEvery { memory.facts(any()) } returns emptyList()
        coEvery { memory.search(any(), any(), any()) } returns listOf("We discussed Kotlin last week")
        every { registry.describe() } returns ""

        val assembler = ContextAssembler(memory, registry, 10, searchLimit = 5)
        val messages = assembler.build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "remind me", Channel.TELEGRAM))
        val system = messages.first().content
        assertTrue(system.contains("Relevant past context"))
        assertTrue(system.contains("We discussed Kotlin last week"))
    }

    @Test
    fun `build runs facts, history, and search concurrently`() = runTest {
        // Each call delays 100 ms; sequential would take ~300 ms, parallel ~100 ms
        coEvery { memory.facts(any()) }           coAnswers { delay(100); emptyList() }
        coEvery { memory.history(any(), any()) }  coAnswers { delay(100); emptyList() }
        coEvery { memory.search(any(), any(), any()) } coAnswers { delay(100); emptyList() }
        every { registry.describe() } returns ""

        val elapsed = measureTimeMillis {
            ContextAssembler(memory, registry, 10)
                .build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "hi", Channel.TELEGRAM))
        }
        // Allow generous headroom but must be well under 3 × 100 ms
        assertTrue(elapsed < 250, "Expected parallel execution (<250 ms), got ${elapsed} ms")
    }
}
