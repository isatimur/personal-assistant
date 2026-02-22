package com.assistant.tools.filesystem

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileSystemToolTest {
    private lateinit var tmpDir: java.nio.file.Path
    private lateinit var tool: FileSystemTool

    @BeforeAll fun setup() {
        tmpDir = Files.createTempDirectory("assistant-test")
        tool = FileSystemTool(allowedPaths = listOf(tmpDir.toString()))
    }
    @AfterAll fun cleanup() { tmpDir.toFile().deleteRecursively() }

    @Test
    fun `write then read file`() = runTest {
        val path = "$tmpDir/hello.txt"
        val writeResult = tool.execute(ToolCall("file_write", mapOf("path" to path, "content" to "hello world")))
        assertTrue(writeResult is Observation.Success)
        val readResult = tool.execute(ToolCall("file_read", mapOf("path" to path)))
        assertEquals("hello world", (readResult as Observation.Success).result)
    }

    @Test
    fun `list directory`() = runTest {
        Files.createFile(tmpDir.resolve("a.txt"))
        Files.createFile(tmpDir.resolve("b.txt"))
        val result = tool.execute(ToolCall("file_list", mapOf("path" to tmpDir.toString()))) as Observation.Success
        assertTrue(result.result.contains("a.txt"))
        assertTrue(result.result.contains("b.txt"))
    }

    @Test
    fun `read non-existent file returns error`() = runTest {
        val result = tool.execute(ToolCall("file_read", mapOf("path" to "/tmp/does_not_exist_xyz.txt")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("file_unknown", mapOf())) is Observation.Error)
    }

    // ── sandboxing ────────────────────────────────────────────────────────────

    @Test
    fun `read outside allowed root returns access denied error`() = runTest {
        val result = tool.execute(ToolCall("file_read", mapOf("path" to "/etc/passwd")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Access denied"))
    }

    @Test
    fun `write outside allowed root returns access denied error`() = runTest {
        val result = tool.execute(ToolCall("file_write", mapOf("path" to "/tmp/evil.txt", "content" to "bad")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Access denied"))
    }

    @Test
    fun `list outside allowed root returns access denied error`() = runTest {
        val result = tool.execute(ToolCall("file_list", mapOf("path" to "/etc")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Access denied"))
    }

    // ── oversized file ────────────────────────────────────────────────────────

    @Test
    fun `reading file over 100 KB returns truncated content with notice`() = runTest {
        val bigFile = tmpDir.resolve("big.txt").toFile()
        bigFile.writeBytes(ByteArray(110 * 1024) { 'X'.code.toByte() })
        val result = tool.execute(ToolCall("file_read", mapOf("path" to bigFile.absolutePath)))
        assertTrue(result is Observation.Success)
        val text = (result as Observation.Success).result
        assertTrue(text.contains("truncated"), "Expected truncation notice in: $text")
    }
}
