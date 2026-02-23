package com.assistant.reminder

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderManagerTest {

    @Test
    fun `schedule fires callback after delay`(@TempDir tmpDir: File) = runTest {
        val fired = mutableListOf<String>()
        val manager = ReminderManager(
            persistFile = File(tmpDir, "reminders.json"),
            send = { _, text -> fired.add(text) },
            scope = this
        )

        manager.schedule(chatId = 1L, delay = 10.seconds, text = "test reminder")
        advanceTimeBy(11.seconds)

        assertEquals(1, fired.size)
        assertEquals("test reminder", fired.first())
    }

    @Test
    fun `cancel prevents fire`(@TempDir tmpDir: File) = runTest {
        val fired = mutableListOf<String>()
        val manager = ReminderManager(
            persistFile = File(tmpDir, "reminders.json"),
            send = { _, text -> fired.add(text) },
            scope = this
        )

        val id = manager.schedule(chatId = 1L, delay = 10.seconds, text = "should not fire")
        val cancelled = manager.cancel(id)
        advanceTimeBy(15.seconds)

        assertTrue(cancelled)
        assertTrue(fired.isEmpty(), "Cancelled reminder should not fire")
    }

    @Test
    fun `persist and reload restores reminder`(@TempDir tmpDir: File) = runTest {
        val persistFile = File(tmpDir, "reminders.json")
        val fired = mutableListOf<String>()

        // Simulate a reminder saved by a previous app session
        val futureFireAt = System.currentTimeMillis() + 2.minutes.inWholeMilliseconds
        val reminder = Reminder(id = "saved-id", chatId = 1L, fireAt = futureFireAt, text = "persisted reminder")
        persistFile.writeText(Json.encodeToString(listOf(reminder)))

        // Load and reschedule as if the app restarted
        val manager = ReminderManager(
            persistFile = persistFile,
            send = { _, text -> fired.add(text) },
            scope = this
        )
        manager.loadAndReschedule()
        advanceTimeBy(3.minutes)

        assertEquals(1, fired.size)
        assertEquals("persisted reminder", fired.first())
    }

    @Test
    fun `past-due reminder fires immediately on reload`(@TempDir tmpDir: File) = runTest {
        val persistFile = File(tmpDir, "reminders.json")
        val fired = mutableListOf<String>()

        val pastFireAt = System.currentTimeMillis() - 60_000L
        val reminder = Reminder(id = "past-1", chatId = 1L, fireAt = pastFireAt, text = "overdue")
        persistFile.writeText(Json.encodeToString(listOf(reminder)))

        val manager = ReminderManager(
            persistFile = persistFile,
            send = { _, text -> fired.add(text) },
            scope = this
        )
        manager.loadAndReschedule()
        advanceTimeBy(1.seconds)

        assertEquals(1, fired.size)
        assertEquals("overdue", fired.first())
    }

    @Test
    fun `cancel returns false for unknown id`(@TempDir tmpDir: File) {
        val manager = ReminderManager(
            persistFile = File(tmpDir, "reminders.json"),
            send = { _, _ -> }
        )
        assertFalse(manager.cancel("nonexistent-id"))
    }

    @Test
    fun `parseReminderDuration handles all units`() {
        assertEquals(30.seconds, parseReminderDuration("30s"))
        assertEquals(10.minutes, parseReminderDuration("10m"))
        assertEquals(2.hours, parseReminderDuration("2h"))
        assertEquals(1.days, parseReminderDuration("1d"))
    }

    @Test
    fun `parseReminderDuration throws on invalid input`() {
        assertThrows(IllegalArgumentException::class.java) { parseReminderDuration("bad") }
        assertThrows(IllegalArgumentException::class.java) { parseReminderDuration("10x") }
    }
}
