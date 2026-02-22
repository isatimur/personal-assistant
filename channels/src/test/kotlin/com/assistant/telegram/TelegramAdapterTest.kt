package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import io.mockk.mockk
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
}
