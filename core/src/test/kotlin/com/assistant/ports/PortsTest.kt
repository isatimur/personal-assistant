package com.assistant.ports

import com.assistant.domain.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PortsTest {
    @Test
    fun `LlmPort complete returns a string`() = runTest {
        val port = mockk<LlmPort>()
        coEvery { port.complete(any()) } returns "Hello!"
        val result = port.complete(listOf(ChatMessage(role = "user", content = "Hi")))
        assertEquals("Hello!", result)
    }

    @Test
    fun `ToolPort execute returns Observation`() = runTest {
        val port = mockk<ToolPort>()
        val call = ToolCall("file_read", mapOf("path" to "/tmp/test.txt"))
        coEvery { port.execute(call) } returns Observation.Success("content")
        val result = port.execute(call)
        assertTrue(result is Observation.Success)
    }

    @Test
    fun `MemoryPort stores and retrieves messages`() = runTest {
        val port = mockk<MemoryPort>()
        val msg = Message("user1", "hi", Channel.TELEGRAM)
        coEvery { port.append(any(), msg) } just runs
        coEvery { port.history(any(), any()) } returns listOf(msg)
        port.append("session1", msg)
        val history = port.history("session1", 10)
        assertEquals(1, history.size)
        assertEquals("hi", history.first().text)
    }

    @Test
    fun `EmbeddingPort embed returns FloatArray`() = runTest {
        val port = mockk<EmbeddingPort>()
        coEvery { port.embed(any()) } returns floatArrayOf(0.1f, 0.2f, 0.3f)
        val result = port.embed("hello world")
        assertEquals(3, result.size)
        assertEquals(0.1f, result[0], 1e-6f)
    }

    @Test
    fun `MemoryPort search returns list of strings`() = runTest {
        val port = mockk<MemoryPort>()
        coEvery { port.search("user1", "kotlin", 5) } returns listOf("Kotlin is great", "Coroutines rock")
        val result = port.search("user1", "kotlin", 5)
        assertEquals(2, result.size)
        assertTrue(result.contains("Kotlin is great"))
    }
}
