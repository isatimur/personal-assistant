package com.assistant.reminder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun parseReminderDuration(s: String): Duration {
    val match = Regex("^(\\d+)([smhd])$").find(s)
        ?: throw IllegalArgumentException("Invalid duration: $s. Use format like 30m, 1h, 2d")
    val value = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "s" -> value.seconds
        "m" -> value.minutes
        "h" -> value.hours
        "d" -> value.days
        else -> throw IllegalArgumentException("Unknown time unit in: $s")
    }
}

@Serializable
data class Reminder(
    val id: String,
    val chatId: Long,
    val fireAt: Long,
    val text: String
)

class ReminderManager(
    private val persistFile: File,
    private val send: suspend (Long, String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val logger = Logger.getLogger(ReminderManager::class.java.name)
    private val json = Json { ignoreUnknownKeys = true }
    private val reminders = mutableListOf<Reminder>()

    fun schedule(chatId: Long, delay: Duration, text: String): String {
        val id = UUID.randomUUID().toString()
        val fireAt = System.currentTimeMillis() + delay.inWholeMilliseconds
        val reminder = Reminder(id, chatId, fireAt, text)
        synchronized(reminders) { reminders.add(reminder) }
        persist()
        scope.launch {
            delay(delay)
            fire(reminder)
        }
        return id
    }

    fun cancel(id: String): Boolean {
        val removed = synchronized(reminders) { reminders.removeIf { it.id == id } }
        if (removed) persist()
        return removed
    }

    fun loadAndReschedule() {
        if (!persistFile.exists()) return
        try {
            val loaded = json.decodeFromString<List<Reminder>>(persistFile.readText())
            val now = System.currentTimeMillis()
            val fiveMinsMs = 5 * 60 * 1000L
            synchronized(reminders) { reminders.addAll(loaded) }
            loaded.forEach { reminder ->
                val remaining = reminder.fireAt - now
                if (remaining <= 0) {
                    if (-remaining < fiveMinsMs) {
                        scope.launch { fire(reminder) }
                    }
                } else {
                    scope.launch {
                        delay(remaining.milliseconds)
                        fire(reminder)
                    }
                }
            }
        } catch (e: Exception) {
            logger.warning("Failed to load reminders: ${e.message}")
        }
    }

    private fun persist() {
        try {
            persistFile.parentFile?.mkdirs()
            val snapshot = synchronized(reminders) { reminders.toList() }
            persistFile.writeText(json.encodeToString(snapshot))
        } catch (e: Exception) {
            logger.warning("Failed to persist reminders: ${e.message}")
        }
    }

    private suspend fun fire(reminder: Reminder) {
        val stillActive = synchronized(reminders) { reminders.removeIf { it.id == reminder.id } }
        if (!stillActive) return  // was cancelled before firing
        persist()
        try {
            send(reminder.chatId, reminder.text)
        } catch (e: Exception) {
            logger.severe("Failed to fire reminder ${reminder.id}: ${e.message}")
        }
    }
}
