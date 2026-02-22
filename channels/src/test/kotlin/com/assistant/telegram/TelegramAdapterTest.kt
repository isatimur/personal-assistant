package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TelegramAdapterTest {
    @Test
    fun `normalize produces correct Message`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk())
        val msg = adapter.normalize(senderId = "123456", text = "hello from telegram")
        assertEquals("123456", msg.sender)
        assertEquals("hello from telegram", msg.text)
        assertEquals(Channel.TELEGRAM, msg.channel)
    }

    @Test
    fun `normalize with different senders produces different messages`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk())
        val msg1 = adapter.normalize("111", "text")
        val msg2 = adapter.normalize("222", "text")
        assertNotEquals(msg1.sender, msg2.sender)
    }

    @Test
    fun `constructor accepts custom timeoutMs`() {
        // Verifies the parameter is wired - no exception should be thrown
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), timeoutMs = 5_000L)
        assertNotNull(adapter)
    }
}
