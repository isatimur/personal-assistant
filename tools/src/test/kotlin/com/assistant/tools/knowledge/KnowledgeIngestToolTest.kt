package com.assistant.tools.knowledge

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.MemoryPort
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class KnowledgeIngestToolTest {

    private val memory = mockk<MemoryPort>()

    @Test
    fun `ingest txt file stores chunks in memory`(@TempDir tmpDir: File) {
        coJustRun { memory.saveFact(any(), any()) }
        val file = File(tmpDir, "notes.txt").also {
            it.writeText("Hello world. ".repeat(50))  // ~650 chars → 2 chunks
        }
        val tool = KnowledgeIngestTool(memory, "test-user", listOf(tmpDir.absolutePath))
        val result = runBlocking {
            tool.execute(ToolCall("knowledge_ingest", mapOf("path" to file.absolutePath)))
        }
        assertTrue(result is Observation.Success)
        assertTrue((result as Observation.Success).result.contains("chunks"))
        coVerify(exactly = 2) { memory.saveFact("test-user", any()) }
    }

    @Test
    fun `ingest md file stores chunks in memory`(@TempDir tmpDir: File) {
        coJustRun { memory.saveFact(any(), any()) }
        val file = File(tmpDir, "README.md").also { it.writeText("# Title\n\nSome content here.") }
        val tool = KnowledgeIngestTool(memory, allowedPaths = listOf(tmpDir.absolutePath))
        val result = runBlocking {
            tool.execute(ToolCall("knowledge_ingest", mapOf("path" to file.absolutePath)))
        }
        assertTrue(result is Observation.Success)
        coVerify(atLeast = 1) { memory.saveFact("knowledge", any()) }
    }

    @Test
    fun `ingest missing file returns error`(@TempDir tmpDir: File) {
        val tool = KnowledgeIngestTool(memory, allowedPaths = listOf(tmpDir.absolutePath))
        val result = runBlocking {
            tool.execute(ToolCall("knowledge_ingest", mapOf("path" to File(tmpDir, "nonexistent.txt").absolutePath)))
        }
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("not found"))
    }

    @Test
    fun `path outside allowed roots returns access denied`() {
        val tool = KnowledgeIngestTool(memory)
        val result = runBlocking {
            tool.execute(ToolCall("knowledge_ingest", mapOf("path" to "/etc/passwd")))
        }
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Access denied"))
    }

    @Test
    fun `missing path parameter returns error`() {
        val tool = KnowledgeIngestTool(memory)
        val result = runBlocking {
            tool.execute(ToolCall("knowledge_ingest", emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() {
        val tool = KnowledgeIngestTool(memory)
        val result = runBlocking {
            tool.execute(ToolCall("unknown_cmd", emptyMap()))
        }
        assertTrue(result is Observation.Error)
    }
}
