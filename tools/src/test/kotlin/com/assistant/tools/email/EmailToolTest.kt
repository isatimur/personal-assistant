package com.assistant.tools.email

import com.assistant.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class EmailToolTest {
    private val config = EmailConfig("imap.example.com", 993, "smtp.example.com", 587, "test@example.com", "test")
    private val tool = EmailTool(config)

    @Test
    fun `unknown command returns error`() = runTest {
        assertTrue(tool.execute(ToolCall("email_unknown", mapOf())) is Observation.Error)
    }

    @Test
    fun `send email with missing body returns error`() = runTest {
        val result = tool.execute(ToolCall("email_send", mapOf("to" to "a@b.com", "subject" to "hi")))
        assertTrue(result is Observation.Error)
        assertTrue((result as Observation.Error).message.contains("Missing"))
    }
}
