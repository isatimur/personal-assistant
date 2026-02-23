package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import com.assistant.workspace.WorkspaceLoader
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.system.measureTimeMillis

class ContextAssemblerTest {
    private val memory = mockk<MemoryPort>()
    private val registry = mockk<ToolRegistry>()

    private fun emptyMemory() {
        coEvery { memory.history(any(), any()) } returns emptyList()
        coEvery { memory.facts(any()) } returns emptyList()
        coEvery { memory.search(any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `system message is first`() = runTest {
        emptyMemory()
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
        emptyMemory()
        coEvery { memory.search(any(), any(), any()) } returns listOf("We discussed Kotlin last week")
        every { registry.describe() } returns ""

        val assembler = ContextAssembler(memory, registry, 10, searchLimit = 5)
        val messages = assembler.build(Session("s1", "user1", Channel.TELEGRAM), Message("user1", "remind me", Channel.TELEGRAM))
        val system = messages.first().content
        assertTrue(system.contains("Relevant past context"))
        assertTrue(system.contains("We discussed Kotlin last week"))
    }

    @Test
    fun `build runs facts, history, search, and workspace concurrently`() = runTest {
        // Each call delays 100 ms; sequential would take ~300+ ms, parallel ~100 ms
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

    @Test
    fun `identity line injected when IDENTITY present`(@TempDir tmpDir: File) = runTest {
        emptyMemory()
        every { registry.describe() } returns ""
        File(tmpDir, "IDENTITY.md").writeText("---\nname: Aria\nemoji: 🤖\nvibe: direct\n---\n")

        val workspace = WorkspaceLoader(tmpDir)
        val assembler = ContextAssembler(memory, registry, 10, workspace = workspace)
        val system = assembler.build(
            Session("s1", "user1", Channel.TELEGRAM),
            Message("user1", "hi", Channel.TELEGRAM)
        ).first().content

        assertTrue(system.contains("Aria"), "Identity name should be in system prompt")
        assertTrue(system.contains("🤖"), "Identity emoji should be in system prompt")
        assertTrue(system.contains("direct"), "Identity vibe should be in system prompt")
    }

    @Test
    fun `skills appear in system prompt`(@TempDir tmpDir: File) = runTest {
        emptyMemory()
        every { registry.describe() } returns ""
        val skillsDir = File(tmpDir, "skills").also { it.mkdirs() }
        File(skillsDir, "github.md").writeText(
            "---\nname: github\nenabled: true\ndescription: GitHub\n---\nUse gh CLI for GitHub operations."
        )

        val workspace = WorkspaceLoader(tmpDir)
        val assembler = ContextAssembler(memory, registry, 10, workspace = workspace)
        val system = assembler.build(
            Session("s1", "user1", Channel.TELEGRAM),
            Message("user1", "hi", Channel.TELEGRAM)
        ).first().content

        assertTrue(system.contains("github"), "Skill name should appear in system prompt")
        assertTrue(system.contains("Use gh CLI for GitHub operations."), "Skill body should appear")
    }

    @Test
    fun `bootstrap appears before soul in system prompt`(@TempDir tmpDir: File) = runTest {
        emptyMemory()
        every { registry.describe() } returns ""
        File(tmpDir, "Soul.md").writeText("Soul content here")
        val bootstrapDir = File(tmpDir, "bootstrap").also { it.mkdirs() }
        File(bootstrapDir, "00-context.md").writeText("Bootstrap content here")

        val workspace = WorkspaceLoader(tmpDir)
        val assembler = ContextAssembler(memory, registry, 10, workspace = workspace)
        val system = assembler.build(
            Session("s1", "user1", Channel.TELEGRAM),
            Message("user1", "hi", Channel.TELEGRAM)
        ).first().content

        val idxBootstrap = system.indexOf("Bootstrap content here")
        val idxSoul = system.indexOf("Soul content here")
        assertTrue(idxBootstrap < idxSoul, "Bootstrap should appear before Soul in system prompt")
    }
}
