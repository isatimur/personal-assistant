package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

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
}
