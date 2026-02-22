package com.assistant.tools.filesystem

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileSystemToolTest {
    private val tool = FileSystemTool()
    private lateinit var tmpDir: java.nio.file.Path

    @BeforeAll fun setup() { tmpDir = Files.createTempDirectory("assistant-test") }
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
}
