package com.assistant.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TypesTest {
    @Test
    fun `Message holds sender text and channel`() {
        val msg = Message(sender = "user123", text = "hello", channel = Channel.TELEGRAM)
        assertEquals("user123", msg.sender)
        assertEquals("hello", msg.text)
        assertEquals(Channel.TELEGRAM, msg.channel)
    }

    @Test
    fun `ToolCall holds name and arguments`() {
        val call = ToolCall(name = "file_read", arguments = mapOf("path" to "/tmp/test.txt"))
        assertEquals("file_read", call.name)
        assertEquals("/tmp/test.txt", call.arguments["path"])
    }

    @Test
    fun `Observation can be success or error`() {
        val ok = Observation.Success("file contents here")
        val err = Observation.Error("file not found")
        assertTrue(ok is Observation.Success)
        assertTrue(err is Observation.Error)
        assertEquals("file contents here", ok.result)
        assertEquals("file not found", err.message)
    }
}
