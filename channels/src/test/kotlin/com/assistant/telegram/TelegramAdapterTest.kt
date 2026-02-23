package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

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
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), timeoutMs = 5_000L)
        assertNotNull(adapter)
    }

    @Test
    fun `writeChatId writes chatId to file atomically`(@TempDir tmpDir: File) {
        val chatIdFile = File(tmpDir, "last-chat-id")
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), lastChatIdFile = chatIdFile)
        adapter.writeChatId(987654321L)
        assertTrue(chatIdFile.exists(), "last-chat-id file should exist after write")
        assertEquals("987654321", chatIdFile.readText())
    }

    @Test
    fun `writeChatId overwrites previous value`(@TempDir tmpDir: File) {
        val chatIdFile = File(tmpDir, "last-chat-id")
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), lastChatIdFile = chatIdFile)
        adapter.writeChatId(111L)
        adapter.writeChatId(222L)
        assertEquals("222", chatIdFile.readText())
    }

    @Test
    fun `sendProactive is no-op before start`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk())
        // Should not throw - bot is null before start()
        assertDoesNotThrow { adapter.sendProactive(12345L, "Hello") }
    }
}
