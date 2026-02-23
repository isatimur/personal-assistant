package com.assistant.telegram

import com.assistant.ports.MemoryPort
import com.github.kotlintelegrambot.Bot
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OnboardingManagerTest {

    private fun makeMemory(): MemoryPort = mockk<MemoryPort>(relaxed = true)

    // ── needsOnboarding ───────────────────────────────────────────────────────

    @Test
    fun `needsOnboarding returns true when Soul_md is absent`(@TempDir dir: File) {
        File(dir, "IDENTITY.md").writeText("name: Aria\nvibe: direct")
        val mgr = OnboardingManager(makeMemory(), dir)
        assertTrue(mgr.needsOnboarding())
    }

    @Test
    fun `needsOnboarding returns true when IDENTITY_md has no name field`(@TempDir dir: File) {
        File(dir, "IDENTITY.md").writeText("vibe: direct")
        File(dir, "Soul.md").writeText("You are helpful.")
        val mgr = OnboardingManager(makeMemory(), dir)
        assertTrue(mgr.needsOnboarding())
    }

    @Test
    fun `needsOnboarding returns true when IDENTITY_md is absent`(@TempDir dir: File) {
        File(dir, "Soul.md").writeText("You are helpful.")
        val mgr = OnboardingManager(makeMemory(), dir)
        assertTrue(mgr.needsOnboarding())
    }

    @Test
    fun `needsOnboarding returns false when both files are present and valid`(@TempDir dir: File) {
        File(dir, "IDENTITY.md").writeText("name: Aria\nvibe: direct")
        File(dir, "Soul.md").writeText("You are helpful.")
        val mgr = OnboardingManager(makeMemory(), dir)
        assertFalse(mgr.needsOnboarding())
    }

    // ── isActive / start / cancel ─────────────────────────────────────────────

    @Test
    fun `isActive returns false before start`(@TempDir dir: File) {
        val mgr = OnboardingManager(makeMemory(), dir)
        assertFalse(mgr.isActive(42L))
    }

    @Test
    fun `start puts chatId into active state and sends greeting`(@TempDir dir: File) {
        val bot = mockk<Bot>(relaxed = true)
        val mgr = OnboardingManager(makeMemory(), dir)
        mgr.start(bot, 42L)
        assertTrue(mgr.isActive(42L))
        verify { bot.sendMessage(any(), match { it.contains("name", ignoreCase = true) }) }
    }

    @Test
    fun `cancel removes active state`(@TempDir dir: File) {
        val bot = mockk<Bot>(relaxed = true)
        val mgr = OnboardingManager(makeMemory(), dir)
        mgr.start(bot, 42L)
        assertTrue(mgr.isActive(42L))
        mgr.cancel(42L)
        assertFalse(mgr.isActive(42L))
    }

    // ── handle: non-wizard message is ignored ─────────────────────────────────

    @Test
    fun `handle returns false when chatId is not in wizard`(@TempDir dir: File) = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val mgr = OnboardingManager(makeMemory(), dir)
        val handled = mgr.handle(bot, 99L, "hello")
        assertFalse(handled)
    }

    // ── happy-path: full 6-step walk ──────────────────────────────────────────

    @Test
    fun `full 6-step wizard writes IDENTITY_md Soul_md USER_md and saves facts`(@TempDir dir: File) = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = makeMemory()
        val mgr = OnboardingManager(memory, dir)

        mgr.start(bot, 1L)
        assertTrue(mgr.isActive(1L))

        // step 1: user name
        assertTrue(mgr.handle(bot, 1L, "Timur"))
        // step 2: timezone
        assertTrue(mgr.handle(bot, 1L, "Europe/Bratislava"))
        // step 3: goals
        assertTrue(mgr.handle(bot, 1L, "ship product, stay healthy"))
        // step 4: bot name
        assertTrue(mgr.handle(bot, 1L, "Aria"))
        // step 5: vibe
        assertTrue(mgr.handle(bot, 1L, "direct, a bit dry"))
        // step 6: soul — completion
        assertTrue(mgr.handle(bot, 1L, "You are Aria, a focused assistant."))

        assertFalse(mgr.isActive(1L))

        // IDENTITY.md
        val identity = File(dir, "IDENTITY.md").readText()
        assertTrue(identity.contains("name: Aria"))
        assertTrue(identity.contains("vibe: direct, a bit dry"))

        // Soul.md
        assertEquals("You are Aria, a focused assistant.", File(dir, "Soul.md").readText().trim())

        // USER.md has timezone and goals
        val userMd = File(dir, "USER.md").readText()
        assertTrue(userMd.contains("name: Timur"))
        assertTrue(userMd.contains("timezone: Europe/Bratislava"))
        assertTrue(userMd.contains("goals: ship product, stay healthy"))

        // facts saved for name and timezone
        coVerify { memory.saveFact("1", match { it.contains("Timur") }) }
        coVerify { memory.saveFact("1", match { it.contains("Europe/Bratislava") }) }

        // completion message
        verify { bot.sendMessage(any(), match { it.contains("Aria") && it.contains("Timur") }) }
    }

    @Test
    fun `USER_md contains timezone and goals after wizard`(@TempDir dir: File) = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val mgr = OnboardingManager(makeMemory(), dir)

        mgr.start(bot, 2L)
        mgr.handle(bot, 2L, "Alice")
        mgr.handle(bot, 2L, "America/New_York")
        mgr.handle(bot, 2L, "write a novel")
        mgr.handle(bot, 2L, "Maya")
        mgr.handle(bot, 2L, "warm and encouraging")
        mgr.handle(bot, 2L, "You are Maya, a creative companion.")

        val userMd = File(dir, "USER.md").readText()
        assertTrue(userMd.contains("timezone: America/New_York"))
        assertTrue(userMd.contains("goals: write a novel"))
    }
}
