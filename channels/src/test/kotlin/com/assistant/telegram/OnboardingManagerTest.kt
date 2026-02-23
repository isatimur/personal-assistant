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

    // ── happy-path: full 4-step walk ──────────────────────────────────────────

    @Test
    fun `full happy-path wizard writes IDENTITY_md and Soul_md and saves fact`(@TempDir dir: File) = runTest {
        val bot = mockk<Bot>(relaxed = true)
        val memory = makeMemory()
        val mgr = OnboardingManager(memory, dir)

        // step 0: start
        mgr.start(bot, 1L)
        assertTrue(mgr.isActive(1L))

        // step 1: user name
        var handled = mgr.handle(bot, 1L, "Timur")
        assertTrue(handled)

        // step 2: bot name
        handled = mgr.handle(bot, 1L, "Aria")
        assertTrue(handled)

        // step 3: vibe
        handled = mgr.handle(bot, 1L, "direct, a bit dry")
        assertTrue(handled)

        // step 4: soul — completion
        handled = mgr.handle(bot, 1L, "You are Aria, a focused assistant.")
        assertTrue(handled)

        // wizard must be finished
        assertFalse(mgr.isActive(1L))

        // IDENTITY.md written correctly
        val identityFile = File(dir, "IDENTITY.md")
        assertTrue(identityFile.exists(), "IDENTITY.md must be written")
        val identity = identityFile.readText()
        assertTrue(identity.contains("name: Aria"), "IDENTITY.md must contain 'name: Aria'")
        assertTrue(identity.contains("vibe: direct, a bit dry"), "IDENTITY.md must contain vibe")

        // Soul.md written correctly
        val soulFile = File(dir, "Soul.md")
        assertTrue(soulFile.exists(), "Soul.md must be written")
        assertEquals("You are Aria, a focused assistant.", soulFile.readText().trim())

        // fact saved with user name
        coVerify { memory.saveFact("1", match { it.contains("Timur") }) }

        // completion message sent
        verify { bot.sendMessage(any(), match { it.contains("Aria") && it.contains("Timur") }) }
    }
}
