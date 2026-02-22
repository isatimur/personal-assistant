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
}
