package com.assistant.telegram

import com.assistant.domain.*
import com.assistant.gateway.Gateway
import com.assistant.ports.MemoryPort
import com.assistant.ports.MemoryStats
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

    // ── /memory ───────────────────────────────────────────────────────────────

    @Test
    fun `slash memory sends numbered list of facts`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = mockk<MemoryPort>(relaxed = true)
        coEvery { memory.facts(any()) } returns listOf("User's name is Timur", "Prefers direct communication")
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = memory)

        val result = adapter.handleCommand(bot, 1L, "/memory")

        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("1. User's name is Timur") && it.contains("2. Prefers direct communication") }) }
    }

    @Test
    fun `slash memory sends empty message when no facts`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = mockk<MemoryPort>(relaxed = true)
        coEvery { memory.facts(any()) } returns emptyList()
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = memory)

        val result = adapter.handleCommand(bot, 1L, "/memory")

        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("don't know", ignoreCase = true) }) }
    }

    // ── /forget ───────────────────────────────────────────────────────────────

    @Test
    fun `slash forget 1 calls deleteFact with correct fact`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = mockk<MemoryPort>(relaxed = true)
        coEvery { memory.facts(any()) } returns listOf("User's name is Timur", "Prefers direct communication")
        coEvery { memory.deleteFact(any(), any()) } just Runs
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = memory)

        val result = adapter.handleCommand(bot, 1L, "/forget 1")

        assertTrue(result)
        coVerify { memory.deleteFact("1", "User's name is Timur") }
        verify { bot.sendMessage(any(), match { it.contains("User's name is Timur") }) }
    }

    @Test
    fun `slash forget with out-of-range index sends no fact message`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = mockk<MemoryPort>(relaxed = true)
        coEvery { memory.facts(any()) } returns listOf("only fact")
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = memory)

        val result = adapter.handleCommand(bot, 1L, "/forget 99")

        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("No fact #99") }) }
    }

    // ── /remind ───────────────────────────────────────────────────────────────

    @Test
    fun `slash remind sends confirmation when reminder manager present`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = mockk<MemoryPort>(relaxed = true)
        val reminderManager = mockk<com.assistant.reminder.ReminderManager>(relaxed = true)
        every { reminderManager.schedule(any(), any(), any()) } returns "reminder-id"
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = memory)
        adapter.reminderManager = reminderManager

        val result = adapter.handleCommand(bot, 1L, "/remind 10m buy milk")

        assertTrue(result)
        verify { reminderManager.schedule(1L, any(), "buy milk") }
        verify { bot.sendMessage(any(), match { it.contains("10m") }) }
    }

    @Test
    fun `slash remind sends usage when no reminder manager`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk(relaxed = true))

        val result = adapter.handleCommand(bot, 1L, "/remind 10m buy milk")

        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("Usage", ignoreCase = true) }) }
    }

    // ── /user ─────────────────────────────────────────────────────────────────

    @Test
    fun `slash user shows USER_md content`(@TempDir tmpDir: File) = runTest {
        val workspaceDir = File(tmpDir, "workspace").also { it.mkdirs() }
        File(workspaceDir, "USER.md").writeText("name: Timur\ntimezone: Europe/Bratislava")
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk(relaxed = true), workspaceDir = workspaceDir)

        val result = adapter.handleCommand(bot, 1L, "/user")

        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("name: Timur") }) }
    }

    @Test
    fun `slash user sends message when USER_md absent`(@TempDir tmpDir: File) = runTest {
        val workspaceDir = File(tmpDir, "workspace").also { it.mkdirs() }
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk(relaxed = true), workspaceDir = workspaceDir)

        val result = adapter.handleCommand(bot, 1L, "/user")

        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("No USER.md", ignoreCase = true) }) }
    }

    @Test
    fun `slash user set updates field and confirms`(@TempDir tmpDir: File) = runTest {
        val workspaceDir = File(tmpDir, "workspace").also { it.mkdirs() }
        File(workspaceDir, "USER.md").writeText("name: Timur\ntimezone: Europe/Bratislava\n")
        val bot = mockk<Bot>(relaxed = true)
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk(relaxed = true), workspaceDir = workspaceDir)

        val result = adapter.handleCommand(bot, 1L, "/user set timezone Europe/London")

        assertTrue(result)
        verify { bot.sendMessage(any(), match { it.contains("timezone") && it.contains("Europe/London") }) }
        assertTrue(File(workspaceDir, "USER.md").readText().contains("timezone: Europe/London"))
    }

    // ── /status ───────────────────────────────────────────────────────────────

    @Test
    fun `status command sends formatted message with stats`() = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = mockk<MemoryPort>(relaxed = true)
        val stats = MemoryStats(factsCount = 5, chunkCount = 100, messageCount = 42)
        coEvery { memory.stats(any()) } returns stats
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = memory)

        adapter.handleCommand(bot, 1L, "/status")

        verify { bot.sendMessage(any(), match { msg ->
            msg.contains("Facts: 5") &&
            msg.contains("Chunks: 100") &&
            msg.contains("Messages: 42") &&
            msg.contains("Bot status")
        }) }
    }

    // ── ChannelPort ───────────────────────────────────────────────────────────

    @Test
    fun `name returns telegram`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        assertEquals("telegram", adapter.name)
    }

    @Test
    fun `send parses sessionId and calls sendProactive`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        // sendProactive is a no-op when telegramBot is null — just must not throw
        assertDoesNotThrow { adapter.send("TELEGRAM:99887766", "hello") }
    }

    @Test
    fun `send with malformed sessionId does nothing`() {
        val adapter = TelegramAdapter(token = "fake", gateway = mockk(), memory = mockk())
        assertDoesNotThrow { adapter.send("BAD_FORMAT", "hello") }
    }
}
