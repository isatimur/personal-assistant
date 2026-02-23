package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import com.assistant.ports.MemoryPort
import com.github.kotlintelegrambot.Bot
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TelegramAdapterTest {
    @Test
    fun `normalize produces correct Message`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        val msg = adapter.normalize(senderId = "123456", text = "hello from telegram")
        assertEquals("123456", msg.sender)
        assertEquals("hello from telegram", msg.text)
        assertEquals(Channel.TELEGRAM, msg.channel)
    }

    @Test
    fun `normalize with different senders produces different messages`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        val msg1 = adapter.normalize("111", "text")
        val msg2 = adapter.normalize("222", "text")
        assertNotEquals(msg1.sender, msg2.sender)
    }

    @Test
    fun `constructor accepts custom timeoutMs`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk(), timeoutMs = 5_000L)
        assertNotNull(adapter)
    }

    @Test
    fun `writeChatId writes chatId to file atomically`(@TempDir tmpDir: File) {
        val chatIdFile = File(tmpDir, "last-chat-id")
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk(), lastChatIdFile = chatIdFile)
        adapter.writeChatId(987654321L)
        assertTrue(chatIdFile.exists(), "last-chat-id file should exist after write")
        assertEquals("987654321", chatIdFile.readText())
    }

    @Test
    fun `writeChatId overwrites previous value`(@TempDir tmpDir: File) {
        val chatIdFile = File(tmpDir, "last-chat-id")
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk(), lastChatIdFile = chatIdFile)
        adapter.writeChatId(111L)
        adapter.writeChatId(222L)
        assertEquals("222", chatIdFile.readText())
    }

    @Test
    fun `sendProactive is no-op before start`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        // Should not throw - bot is null before start()
        assertDoesNotThrow { adapter.sendProactive(12345L, "Hello") }
    }

    // ── handleCommand ─────────────────────────────────────────────────────────

    @Test
    fun `handleCommand returns true for slash new and clears session`() = runTest {
        val gateway = mockk<Gateway>()
        val memory = mockk<MemoryPort>()
        val bot = mockk<Bot>(relaxed = true)
        every { gateway.clearSession(any()) } just Runs
        coEvery { memory.clearHistory(any()) } just Runs

        val adapter = TelegramAdapter(token = "fake", gateway = gateway, memory = memory)
        val result = adapter.handleCommand(bot, 42L, "/new")

        assertTrue(result)
        verify { gateway.clearSession("TELEGRAM:42") }
        coVerify { memory.clearHistory("TELEGRAM:42") }
    }

    @Test
    fun `handleCommand returns true for slash reset and clears session`() = runTest {
        val gateway = mockk<Gateway>()
        val memory = mockk<MemoryPort>()
        val bot = mockk<Bot>(relaxed = true)
        every { gateway.clearSession(any()) } just Runs
        coEvery { memory.clearHistory(any()) } just Runs

        val adapter = TelegramAdapter(token = "fake", gateway = gateway, memory = memory)
        val result = adapter.handleCommand(bot, 99L, "/reset")

        assertTrue(result)
        verify { gateway.clearSession("TELEGRAM:99") }
        coVerify { memory.clearHistory("TELEGRAM:99") }
    }

    @Test
    fun `handleCommand returns true for slash help`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        val result = adapter.handleCommand(bot, 1L, "/help")
        assertTrue(result)
    }

    @Test
    fun `handleCommand returns true for unknown command`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        val result = adapter.handleCommand(bot, 1L, "/unknowncmd")
        assertTrue(result)
    }

    @Test
    fun `handleCommand returns false for plain text`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        val result = adapter.handleCommand(bot, 1L, "hello world")
        assertFalse(result)
    }

    // ── /start onboarding integration ─────────────────────────────────────────

    @Test
    fun `slash start triggers onboarding when Soul_md is absent`(@TempDir tmpDir: File) = runTest {
        val chatIdFile = File(tmpDir, "last-chat-id")
        val workspaceDir = File(tmpDir, "workspace")
        workspaceDir.mkdirs()
        // Soul.md intentionally absent → needsOnboarding = true
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(
            token = "fake",
            gateway = mockk(),
            memory = mockk(relaxed = true),
            lastChatIdFile = chatIdFile,
            workspaceDir = workspaceDir
        )
        val result = adapter.handleCommand(bot, 77L, "/start")
        assertTrue(result)
        // should send an onboarding greeting (asking for name)
        verify { bot.sendMessage(any(), match { it.contains("name", ignoreCase = true) }) }
    }

    @Test
    fun `slash start sends welcome back when already configured`(@TempDir tmpDir: File) = runTest {
        val chatIdFile = File(tmpDir, "last-chat-id")
        val workspaceDir = File(tmpDir, "workspace")
        workspaceDir.mkdirs()
        File(workspaceDir, "IDENTITY.md").writeText("name: Aria\nvibe: direct")
        File(workspaceDir, "Soul.md").writeText("You are Aria.")
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(
            token = "fake",
            gateway = mockk(),
            memory = mockk(relaxed = true),
            lastChatIdFile = chatIdFile,
            workspaceDir = workspaceDir
        )
        val result = adapter.handleCommand(bot, 77L, "/start")
        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("Welcome back", ignoreCase = true) }) }
    }

    @Test
    fun `slash new during onboarding cancels the wizard`(@TempDir tmpDir: File) = runTest {
        val chatIdFile = File(tmpDir, "last-chat-id")
        val workspaceDir = File(tmpDir, "workspace")
        workspaceDir.mkdirs()
        val bot = mockk<Bot>(relaxed = true)
        val gateway = mockk<Gateway>()
        val memory = mockk<MemoryPort>(relaxed = true)
        every { gateway.clearSession(any()) } just Runs
        coEvery { memory.clearHistory(any()) } just Runs

        val adapter = TelegramAdapter(
            token = "fake",
            gateway = gateway,
            memory = memory,
            lastChatIdFile = chatIdFile,
            workspaceDir = workspaceDir
        )
        // Start onboarding
        adapter.handleCommand(bot, 55L, "/start")
        // Then /new should cancel wizard
        val result = adapter.handleCommand(bot, 55L, "/new")
        assertTrue(result)
        verify { gateway.clearSession("TELEGRAM:55") }
    }
}
